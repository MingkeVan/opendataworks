from __future__ import annotations

"""
语义层（静态 Skills 驱动）
- schema/规则/few-shot/语义映射全部来自 skills 文件
- 不再从 MySQL 知识表读取
"""

import logging
import math
import re
from collections import Counter
from typing import Any

from config import get_settings
from core.skills_loader import SkillsBundle, get_skills_bundle, validate_skills_bundle
from models.schemas import BusinessRule, QAExample, SemanticEntry

logger = logging.getLogger(__name__)


def _tokenize(text: str) -> list[str]:
    try:
        import jieba  # type: ignore

        tokens = list(jieba.cut(text.lower()))
    except Exception:
        tokens = re.findall(r"[\w]+", text.lower())
    return [t.strip() for t in tokens if t.strip() and len(t.strip()) > 1]


class BM25Index:
    def __init__(self, k1: float = 1.5, b: float = 0.75):
        self.k1 = k1
        self.b = b
        self.docs: list[dict[str, Any]] = []
        self.doc_tokens: list[list[str]] = []
        self.doc_freqs: dict[str, int] = {}
        self.avg_dl: float = 0.0

    def add_documents(self, documents: list[dict[str, Any]], text_key: str = "keywords"):
        self.docs = documents
        self.doc_tokens = []
        self.doc_freqs = Counter()
        for doc in documents:
            tokens = _tokenize(str(doc.get(text_key) or ""))
            self.doc_tokens.append(tokens)
            for t in set(tokens):
                self.doc_freqs[t] += 1
        total = sum(len(tokens) for tokens in self.doc_tokens)
        self.avg_dl = total / len(self.doc_tokens) if self.doc_tokens else 1.0

    def search(self, query: str, top_k: int = 5) -> list[dict[str, Any]]:
        if not self.docs:
            return []
        query_tokens = _tokenize(query)
        n = len(self.docs)
        scores: list[float] = []

        for doc_tokens in self.doc_tokens:
            score = 0.0
            dl = len(doc_tokens)
            tf_map = Counter(doc_tokens)
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

        ranked_idx = sorted(range(n), key=lambda i: scores[i], reverse=True)
        result = []
        for idx in ranked_idx[:top_k]:
            if scores[idx] <= 0:
                continue
            row = {**self.docs[idx], "_score": round(scores[idx], 4)}
            result.append(row)
        return result


class SemanticLayer:
    def __init__(self):
        self.ddl_index = BM25Index()
        self.qa_index = BM25Index()
        self.rule_index = BM25Index()
        self.semantic_entries: list[SemanticEntry] = []
        self.business_rules: list[BusinessRule] = []
        self.few_shots: list[QAExample] = []
        self.metrics: list[dict[str, Any]] = []
        self.lineage: list[dict[str, Any]] = []
        self.constraints: dict[str, Any] = {}
        self.table_fields_map: dict[str, set[str]] = {}
        self.available_databases: set[str] = set()
        self.skills_state: dict[str, Any] = {}
        self._bundle: SkillsBundle | None = None
        self._loaded = False

    def load(self, db_name: str | None = None, *, force_reload_skills: bool = False):
        bundle = get_skills_bundle(force_reload=force_reload_skills)
        self._bundle = bundle

        ddl_docs = _build_ddl_docs(bundle.metadata_catalog, db_name=db_name)
        self.ddl_index.add_documents(ddl_docs, text_key="keywords")

        qa_docs = [
            {
                "question": x.question,
                "answer": x.answer,
                "tags": x.tags,
                "keywords": f"{x.question} {' '.join(x.tags)}".strip(),
            }
            for x in bundle.few_shots
        ]
        self.qa_index.add_documents(qa_docs, text_key="keywords")

        rule_docs = [
            {
                "term": x.term,
                "synonyms": x.synonyms,
                "definition": x.definition or "",
                "keywords": f"{x.term} {' '.join(x.synonyms)} {x.definition or ''}".strip(),
            }
            for x in bundle.business_rules
        ]
        self.rule_index.add_documents(rule_docs, text_key="keywords")

        self.semantic_entries = bundle.semantic_mappings
        self.business_rules = bundle.business_rules
        self.few_shots = bundle.few_shots
        self.metrics = bundle.metrics
        self.constraints = bundle.constraints
        self.available_databases = bundle.available_databases
        self.table_fields_map = _build_table_fields_map(bundle.metadata_catalog)
        self.lineage = _filter_lineage(bundle.lineage_catalog, db_name)
        self.skills_state = validate_skills_bundle()
        self._loaded = True

        logger.info(
            "Semantic layer loaded from skills: tables=%d qa=%d rules=%d semantics=%d",
            len(self.ddl_index.docs),
            len(self.qa_index.docs),
            len(self.rule_index.docs),
            len(self.semantic_entries),
        )

    def reload(self, db_name: str | None = None):
        self.load(db_name=db_name, force_reload_skills=True)

    def search_schema(self, query: str, top_k: int | None = None) -> list[dict[str, Any]]:
        cfg = get_settings()
        k = top_k or cfg.max_schema_tables
        return self.ddl_index.search(query, top_k=k)

    def search_examples(self, query: str, top_k: int | None = None) -> list[dict[str, Any]]:
        cfg = get_settings()
        k = top_k or cfg.max_few_shot_examples
        return self.qa_index.search(query, top_k=k)

    def search_rules(self, query: str, top_k: int | None = None) -> list[dict[str, Any]]:
        cfg = get_settings()
        k = top_k or cfg.max_business_rules
        return self.rule_index.search(query, top_k=k)

    def resolve_semantics(self, query: str) -> list[SemanticEntry]:
        query_lower = query.lower()
        matched: list[SemanticEntry] = []
        for entry in self.semantic_entries:
            names = [entry.business_name.lower()] + [s.lower() for s in entry.synonyms]
            if any(name in query_lower for name in names):
                matched.append(entry)
        return matched

    def infer_database(self, query: str, top_k: int = 8) -> tuple[str | None, list[dict[str, Any]]]:
        docs = self.search_schema(query, top_k=max(1, top_k))
        if not docs:
            return None, []

        score_map: dict[str, float] = {}
        hit_map: dict[str, int] = {}
        for doc in docs:
            meta = doc.get("meta") or {}
            db_name = str(meta.get("db_name") or "").strip()
            if not db_name:
                continue
            score = float(doc.get("_score") or 0.0)
            score_map[db_name] = score_map.get(db_name, 0.0) + score
            hit_map[db_name] = hit_map.get(db_name, 0) + 1

        if not score_map:
            return None, []

        ranked = sorted(
            score_map.items(),
            key=lambda x: (x[1], hit_map.get(x[0], 0)),
            reverse=True,
        )
        candidates = [
            {"db_name": db, "score": round(score, 4), "hits": hit_map.get(db, 0)}
            for db, score in ranked
        ]

        query_lower = query.lower()
        for db_name, _ in ranked:
            if db_name.lower() in query_lower:
                return db_name, candidates

        return ranked[0][0], candidates

    def resolve_engine(self, database: str | None) -> str:
        if not self._bundle:
            self.load()
        assert self._bundle is not None
        return self._bundle.resolve_engine(database)


def _build_ddl_docs(catalog: list[dict[str, Any]], db_name: str | None = None) -> list[dict[str, Any]]:
    docs: list[dict[str, Any]] = []
    for table in catalog:
        table_name = str(table.get("table_name") or "").strip()
        if not table_name:
            continue
        table_db = str(table.get("db_name") or "").strip()
        if db_name and table_db and table_db != db_name:
            continue

        table_comment = str(table.get("table_comment") or "").strip()
        fields = table.get("fields") or []
        if not isinstance(fields, list):
            fields = []

        ddl_lines = [f"CREATE TABLE `{table_name}` ("]
        keywords = [table_name]
        if table_comment:
            keywords.append(table_comment)

        for field in fields:
            if not isinstance(field, dict):
                continue
            field_name = str(field.get("field_name") or "").strip()
            field_type = str(field.get("field_type") or "varchar").strip()
            field_comment = str(field.get("field_comment") or "").strip()
            if not field_name:
                continue
            ddl_line = f"  `{field_name}` {field_type}"
            if field_comment:
                safe_comment = field_comment.replace("'", "''")
                ddl_line += f" COMMENT '{safe_comment}'"
                keywords.append(field_comment)
            ddl_line += ","
            ddl_lines.append(ddl_line)
            keywords.append(field_name)

        if ddl_lines[-1].endswith(","):
            ddl_lines[-1] = ddl_lines[-1][:-1]
        ddl_lines.append(");")
        if table_comment:
            ddl_lines.append(f"-- {table_comment}")

        docs.append(
            {
                "table_name": table_name,
                "ddl": "\n".join(ddl_lines),
                "keywords": " ".join(keywords),
                "meta": {
                    "table_name": table_name,
                    "table_comment": table_comment,
                    "db_name": table_db,
                    "layer": table.get("layer"),
                    "fields": fields,
                },
            }
        )
    return docs


def _build_table_fields_map(catalog: list[dict[str, Any]]) -> dict[str, set[str]]:
    mapping: dict[str, set[str]] = {}
    for table in catalog:
        name = str(table.get("table_name") or "").strip()
        if not name:
            continue
        fields = table.get("fields") or []
        field_names: set[str] = set()
        if isinstance(fields, list):
            for field in fields:
                if not isinstance(field, dict):
                    continue
                field_name = str(field.get("field_name") or "").strip()
                if field_name:
                    field_names.add(field_name)
        mapping[name] = field_names
    return mapping


def _filter_lineage(items: list[dict[str, Any]], db_name: str | None) -> list[dict[str, Any]]:
    if not db_name:
        return items
    result = []
    for item in items:
        up_db = str(item.get("upstream_db") or "").strip()
        down_db = str(item.get("downstream_db") or "").strip()
        if db_name in {up_db, down_db}:
            result.append(item)
    return result or items


_semantic_layer = SemanticLayer()


def get_semantic_layer() -> SemanticLayer:
    return _semantic_layer
