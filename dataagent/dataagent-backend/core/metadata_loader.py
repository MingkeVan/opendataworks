from __future__ import annotations

"""
元数据加载器 — 从 MySQL 读取 data_table / data_field / data_lineage / domain 等
构建 DDL 文档索引供 Prompt 注入
"""

import logging
from typing import Any

from config import get_settings
from core.tool_runtime import invoke_tool
from models.schemas import FieldMeta, TableMeta

logger = logging.getLogger(__name__)


def _metadata_schema() -> str:
    cfg = get_settings()
    return cfg.mysql_database


def _mysql_query(sql: str, params: list[Any] | tuple[Any, ...] | None = None, database: str | None = None) -> list[dict]:
    result = invoke_tool(
        "mysql.query",
        {
            "database": database or _metadata_schema(),
            "sql": sql,
            "params": list(params or []),
        },
    )
    return result.get("rows", [])


def load_all_tables(db_name: str | None = None) -> list[TableMeta]:
    """加载所有表的元数据（含字段）"""
    sql = """
        SELECT id, table_name, table_comment, db_name, layer,
               business_domain, data_domain
        FROM data_table
        WHERE deleted = 0 AND status = 'active'
    """
    params: list[Any] = []
    if db_name:
        sql += " AND db_name = %s"
        params.append(db_name)
    sql += " ORDER BY layer, table_name"

    table_rows = _mysql_query(sql, params)
    if not table_rows:
        return []

    table_ids = [r["id"] for r in table_rows]
    placeholders = ",".join(["%s"] * len(table_ids))
    field_rows = _mysql_query(
        f"""
        SELECT table_id, field_name, field_type, field_comment,
               is_primary, is_partition
        FROM data_field
        WHERE deleted = 0 AND table_id IN ({placeholders})
        ORDER BY table_id, field_order
        """,
        table_ids,
    )

    # 按 table_id 分组字段
    fields_map: dict[int, list[FieldMeta]] = {}
    for f in field_rows:
        tid = f["table_id"]
        fields_map.setdefault(tid, []).append(
            FieldMeta(
                field_name=f["field_name"],
                field_type=f["field_type"],
                field_comment=f.get("field_comment"),
                is_primary=bool(f.get("is_primary", 0)),
                is_partition=bool(f.get("is_partition", 0)),
            )
        )

    result: list[TableMeta] = []
    for t in table_rows:
        result.append(
            TableMeta(
                table_id=t["id"],
                table_name=t["table_name"],
                table_comment=t.get("table_comment"),
                db_name=t.get("db_name"),
                layer=t.get("layer"),
                business_domain=t.get("business_domain"),
                data_domain=t.get("data_domain"),
                fields=fields_map.get(t["id"], []),
            )
        )
    return result


def load_lineage_edges(db_name: str | None = None) -> list[dict]:
    """加载血缘关系"""
    sql = """
        SELECT dl.upstream_table_id, dl.downstream_table_id,
               dl.lineage_type,
               ut.table_name AS upstream_table,
               dt2.table_name AS downstream_table
        FROM data_lineage dl
        LEFT JOIN data_table ut ON ut.id = dl.upstream_table_id AND ut.deleted = 0
        LEFT JOIN data_table dt2 ON dt2.id = dl.downstream_table_id AND dt2.deleted = 0
        WHERE dl.deleted = 0
    """
    params: list[Any] = []
    if db_name:
        sql += " AND (ut.db_name = %s OR dt2.db_name = %s)"
        params.extend([db_name, db_name])
    return _mysql_query(sql, params)


def load_domains() -> dict[str, list[dict]]:
    """加载业务域和数据域"""
    business = _mysql_query(
        "SELECT domain_code, domain_name, description "
        "FROM business_domain WHERE deleted = 0"
    )
    data = _mysql_query(
        "SELECT domain_code, domain_name, business_domain, description "
        "FROM data_domain WHERE deleted = 0"
    )
    return {"business_domains": business, "data_domains": data}


def table_to_ddl(table: TableMeta) -> str:
    """将 TableMeta 转换为人可读的 DDL 文本"""
    lines = []
    comment_part = f"  -- {table.table_comment}" if table.table_comment else ""
    lines.append(f"CREATE TABLE `{table.table_name}` ({comment_part}")

    for f in table.fields:
        nullable = "" if True else " NOT NULL"
        pk = " PRIMARY KEY" if f.is_primary else ""
        part = " PARTITION" if f.is_partition else ""
        fc = f" COMMENT '{f.field_comment}'" if f.field_comment else ""
        lines.append(f"  `{f.field_name}` {f.field_type}{nullable}{pk}{part}{fc},")

    if lines[-1].endswith(","):
        lines[-1] = lines[-1][:-1]
    lines.append(");")

    meta_parts = []
    if table.layer:
        meta_parts.append(f"层级: {table.layer}")
    if table.business_domain:
        meta_parts.append(f"业务域: {table.business_domain}")
    if table.data_domain:
        meta_parts.append(f"数据域: {table.data_domain}")
    if meta_parts:
        lines.append(f"-- {' | '.join(meta_parts)}")

    return "\n".join(lines)


def build_ddl_index(db_name: str | None = None) -> list[dict]:
    """构建 DDL 文档索引，供语义层检索使用"""
    tables = load_all_tables(db_name)
    index = []
    for t in tables:
        ddl_text = table_to_ddl(t)
        keywords = [t.table_name]
        if t.table_comment:
            keywords.append(t.table_comment)
        for f in t.fields:
            keywords.append(f.field_name)
            if f.field_comment:
                keywords.append(f.field_comment)

        index.append(
            {
                "table_name": t.table_name,
                "ddl": ddl_text,
                "keywords": " ".join(keywords),
                "meta": t,
            }
        )
    logger.info("Built DDL index with %d tables", len(index))
    return index
