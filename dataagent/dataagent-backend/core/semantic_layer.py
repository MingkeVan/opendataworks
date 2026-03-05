from __future__ import annotations

"""
语义层 — DDL 索引 + QA 索引 + 业务规则索引
使用 BM25 / TF-IDF 轻量检索匹配用户问题到最相关的 Schema 和规则
"""

import logging
import math
import re
from collections import Counter
from typing import Any

import pymysql

from config import get_settings
from core.metadata_loader import build_ddl_index, load_domains, load_lineage_edges
from core.skills_sync import sync_semantic_layer_to_skills
from core.tool_runtime import invoke_tool
from models.schemas import BusinessRule, QAExample, SemanticEntry

logger = logging.getLogger(__name__)


# ---------- 简易 BM25 检索器 ----------


def _safe_ident(name: str) -> str:
    return (name or "").replace("`", "").strip()


def _ensure_knowledge_tables(cfg):
    schema = _safe_ident(cfg.knowledge_mysql_database)
    if not schema:
        return

    admin_conn = pymysql.connect(
        host=cfg.mysql_host,
        port=cfg.mysql_port,
        user=cfg.mysql_user,
        password=cfg.mysql_password,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with admin_conn.cursor() as cur:
            cur.execute(
                f"CREATE DATABASE IF NOT EXISTS `{schema}` "
                "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
            )
        admin_conn.commit()
    finally:
        admin_conn.close()

    conn = pymysql.connect(
        host=cfg.mysql_host,
        port=cfg.mysql_port,
        user=cfg.mysql_user,
        password=cfg.mysql_password,
        database=schema,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS aq_knowledge_qa (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    question TEXT NOT NULL,
                    answer LONGTEXT NOT NULL,
                    tags VARCHAR(255) DEFAULT NULL,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS aq_knowledge_semantic (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    business_name VARCHAR(255) NOT NULL,
                    table_name VARCHAR(255) DEFAULT NULL,
                    field_name VARCHAR(255) DEFAULT NULL,
                    synonyms TEXT DEFAULT NULL,
                    description TEXT DEFAULT NULL,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS aq_knowledge_business (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    term VARCHAR(255) NOT NULL,
                    synonyms TEXT DEFAULT NULL,
                    definition TEXT DEFAULT NULL,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """
            )
        conn.commit()
    finally:
        conn.close()

def _tokenize(text: str) -> list[str]:
    """中英文混合分词"""
    try:
        import jieba
        tokens = list(jieba.cut(text.lower()))
    except ImportError:
        tokens = re.findall(r"[\w]+", text.lower())
    return [t.strip() for t in tokens if t.strip() and len(t.strip()) > 1]


class BM25Index:
    """轻量级 BM25 索引"""

    def __init__(self, k1: float = 1.5, b: float = 0.75):
        self.k1 = k1
        self.b = b
        self.docs: list[dict] = []
        self.doc_tokens: list[list[str]] = []
        self.doc_freqs: dict[str, int] = {}
        self.avg_dl: float = 0.0

    def add_documents(self, documents: list[dict], text_key: str = "keywords"):
        self.docs = documents
        self.doc_tokens = []
        self.doc_freqs = Counter()

        for doc in documents:
            tokens = _tokenize(doc.get(text_key, ""))
            self.doc_tokens.append(tokens)
            unique = set(tokens)
            for t in unique:
                self.doc_freqs[t] += 1

        total = sum(len(t) for t in self.doc_tokens)
        self.avg_dl = total / len(self.doc_tokens) if self.doc_tokens else 1.0

    def search(self, query: str, top_k: int = 5) -> list[dict]:
        if not self.docs:
            return []

        query_tokens = _tokenize(query)
        n = len(self.docs)
        scores: list[float] = []

        for i, doc_toks in enumerate(self.doc_tokens):
            score = 0.0
            dl = len(doc_toks)
            tf_map = Counter(doc_toks)
            for qt in query_tokens:
                tf = tf_map.get(qt, 0)
                df = self.doc_freqs.get(qt, 0)
                if df == 0:
                    continue
                idf = math.log((n - df + 0.5) / (df + 0.5) + 1.0)
                numerator = tf * (self.k1 + 1)
                denominator = tf + self.k1 * (1 - self.b + self.b * dl / self.avg_dl)
                score += idf * numerator / denominator
            scores.append(score)

        ranked = sorted(range(n), key=lambda x: scores[x], reverse=True)
        results = []
        for idx in ranked[:top_k]:
            if scores[idx] > 0:
                doc = {**self.docs[idx], "_score": round(scores[idx], 4)}
                results.append(doc)
        return results


# ---------- 语义层管理器 ----------

class SemanticLayer:
    """管理所有知识索引并提供统一的检索接口"""

    def __init__(self):
        self.ddl_index = BM25Index()
        self.qa_index = BM25Index()
        self.rule_index = BM25Index()
        self.semantic_entries: list[SemanticEntry] = []
        self.domains: dict[str, list[dict]] = {}
        self.lineage: list[dict] = []
        self.skills_sync_info: dict[str, Any] = {}
        self._loaded = False

    def load(self, db_name: str | None = None):
        """加载所有知识索引"""
        logger.info("Loading semantic layer...")

        # 1. DDL 索引
        ddl_docs = build_ddl_index(db_name)
        self.ddl_index.add_documents(ddl_docs, text_key="keywords")

        # 2. QA / 语义 / 业务规则从独立知识 schema 加载
        cfg = get_settings()
        try:
            _ensure_knowledge_tables(cfg)
            knowledge_db = _safe_ident(cfg.knowledge_mysql_database)
            # QA 示例
            qa_rows = invoke_tool(
                "mysql.query",
                {
                    "database": knowledge_db,
                    "sql": "SELECT question, answer, tags FROM aq_knowledge_qa "
                           "WHERE enabled = 1 ORDER BY id DESC LIMIT 200",
                },
            ).get("rows", [])
            qa_docs = []
            for r in qa_rows:
                tags = [t.strip() for t in (r.get("tags") or "").split(",") if t.strip()]
                qa_docs.append({
                    "question": r["question"],
                    "answer": r["answer"],
                    "tags": tags,
                    "keywords": r["question"] + " " + " ".join(tags),
                })
            self.qa_index.add_documents(qa_docs, text_key="keywords")

            # 语义知识
            sem_rows = invoke_tool(
                "mysql.query",
                {
                    "database": knowledge_db,
                    "sql": "SELECT business_name, table_name, field_name, synonyms, description "
                           "FROM aq_knowledge_semantic WHERE enabled = 1",
                },
            ).get("rows", [])
            self.semantic_entries = []
            for r in sem_rows:
                syns = [s.strip() for s in (r.get("synonyms") or "").split(",") if s.strip()]
                self.semantic_entries.append(SemanticEntry(
                    business_name=r["business_name"],
                    table_name=r.get("table_name"),
                    field_name=r.get("field_name"),
                    synonyms=syns,
                    description=r.get("description"),
                ))

            # 业务规则
            biz_rows = invoke_tool(
                "mysql.query",
                {
                    "database": knowledge_db,
                    "sql": "SELECT term, synonyms, definition FROM aq_knowledge_business WHERE enabled = 1",
                },
            ).get("rows", [])
            rule_docs = []
            for r in biz_rows:
                syns = [s.strip() for s in (r.get("synonyms") or "").split(",") if s.strip()]
                rule_docs.append({
                    "term": r["term"],
                    "synonyms": syns,
                    "definition": r.get("definition", ""),
                    "keywords": r["term"] + " " + " ".join(syns) + " " + (r.get("definition") or ""),
                })
            self.rule_index.add_documents(rule_docs, text_key="keywords")
        except Exception as e:
            logger.warning(
                "Failed to load knowledge from MySQL schema=%s: %s (continuing with DDL only)",
                cfg.knowledge_mysql_database,
                e,
            )

        # 3. 域信息和血缘
        try:
            self.domains = load_domains()
            self.lineage = load_lineage_edges(db_name)
        except Exception as e:
            logger.warning("Failed to load domains/lineage: %s", e)

        try:
            self.skills_sync_info = sync_semantic_layer_to_skills(self)
        except Exception as e:
            self.skills_sync_info = {}
            logger.warning("Failed to sync skills files: %s", e)

        self._loaded = True
        logger.info(
            "Semantic layer loaded: %d DDL docs, %d QA docs, %d rules, %d semantic entries",
            len(self.ddl_index.docs),
            len(self.qa_index.docs),
            len(self.rule_index.docs),
            len(self.semantic_entries),
        )

    def search_schema(self, query: str, top_k: int | None = None) -> list[dict]:
        """检索最相关的表 DDL"""
        cfg = get_settings()
        k = top_k or cfg.max_schema_tables
        return self.ddl_index.search(query, top_k=k)

    def search_examples(self, query: str, top_k: int | None = None) -> list[dict]:
        """检索 Few-shot QA 示例"""
        cfg = get_settings()
        k = top_k or cfg.max_few_shot_examples
        return self.qa_index.search(query, top_k=k)

    def search_rules(self, query: str, top_k: int | None = None) -> list[dict]:
        """检索业务规则"""
        cfg = get_settings()
        k = top_k or cfg.max_business_rules
        return self.rule_index.search(query, top_k=k)

    def resolve_semantics(self, query: str) -> list[SemanticEntry]:
        """在语义条目中查找与 query 匹配的业务概念"""
        query_lower = query.lower()
        matched = []
        for entry in self.semantic_entries:
            names = [entry.business_name.lower()] + [s.lower() for s in entry.synonyms]
            if any(n in query_lower for n in names):
                matched.append(entry)
        return matched

    def reload(self, db_name: str | None = None):
        """重新加载索引"""
        self.load(db_name)

    def sync_skills(self) -> dict[str, Any]:
        self.skills_sync_info = sync_semantic_layer_to_skills(self)
        return self.skills_sync_info


# 全局单例
_semantic_layer = SemanticLayer()


def get_semantic_layer() -> SemanticLayer:
    return _semantic_layer
