from __future__ import annotations

import json
import re
from collections import defaultdict
from datetime import datetime, timezone
from typing import Any

import pymysql


def query_rows(
    conn: pymysql.connections.Connection,
    sql: str,
    params: tuple[Any, ...] | list[Any] | None = None,
) -> list[dict[str, Any]]:
    with conn.cursor() as cur:
        cur.execute(sql, params or [])
        return list(cur.fetchall())


def table_exists(conn: pymysql.connections.Connection, schema: str, table: str) -> bool:
    rows = query_rows(
        conn,
        """
        SELECT 1
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s
        LIMIT 1
        """,
        (schema, table),
    )
    return bool(rows)


def column_exists(conn: pymysql.connections.Connection, schema: str, table: str, column: str) -> bool:
    rows = query_rows(
        conn,
        """
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s AND COLUMN_NAME=%s
        LIMIT 1
        """,
        (schema, table, column),
    )
    return bool(rows)


def to_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return False
    if isinstance(value, (int, float)):
        return value != 0
    text = str(value).strip().lower()
    return text in {"1", "true", "yes", "y"}


def dedup(values: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in values:
        if item in seen:
            continue
        seen.add(item)
        result.append(item)
    return result


def parse_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return dedup([str(item).strip() for item in value if str(item).strip()])

    text = str(value).strip()
    if not text:
        return []

    if text.startswith("[") and text.endswith("]"):
        try:
            payload = json.loads(text)
            if isinstance(payload, list):
                return dedup([str(item).strip() for item in payload if str(item).strip()])
        except Exception:
            pass

    return dedup([item.strip() for item in re.split(r"[,;，、\n]", text) if item.strip()])


def load_registry_metadata(conn: pymysql.connections.Connection, metadata_schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, metadata_schema, "data_table") or not table_exists(conn, metadata_schema, "data_field"):
        return []

    has_status = column_exists(conn, metadata_schema, "data_table", "status")
    sql = f"""
        SELECT id, table_name, table_comment, db_name, layer, business_domain, data_domain
        FROM `{metadata_schema}`.`data_table`
        WHERE deleted = 0
    """
    if has_status:
        sql += " AND status = 'active'"
    sql += " ORDER BY id"

    table_rows = query_rows(conn, sql)
    if not table_rows:
        return []

    table_ids = [row["id"] for row in table_rows]
    placeholders = ",".join(["%s"] * len(table_ids))
    field_rows = query_rows(
        conn,
        f"""
        SELECT table_id, field_name, field_type, field_comment, is_primary, is_partition
        FROM `{metadata_schema}`.`data_field`
        WHERE deleted = 0 AND table_id IN ({placeholders})
        ORDER BY table_id, field_order, id
        """,
        table_ids,
    )

    field_map: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for row in field_rows:
        field_map[int(row["table_id"])].append(
            {
                "field_name": row.get("field_name"),
                "field_type": row.get("field_type"),
                "field_comment": row.get("field_comment"),
                "is_primary": to_bool(row.get("is_primary")),
                "is_partition": to_bool(row.get("is_partition")),
            }
        )

    items: list[dict[str, Any]] = []
    for row in table_rows:
        item = {
            "table_id": row.get("id"),
            "table_name": row.get("table_name"),
            "table_comment": row.get("table_comment") or "",
            "db_name": row.get("db_name") or metadata_schema,
            "layer": row.get("layer"),
            "business_domain": row.get("business_domain"),
            "data_domain": row.get("data_domain"),
            "fields": field_map.get(int(row["id"]), []),
        }
        if item["table_name"]:
            items.append(item)
    return items


def load_physical_metadata(conn: pymysql.connections.Connection, schemas: list[str]) -> list[dict[str, Any]]:
    normalized = [item for item in dedup([str(schema).strip() for schema in schemas if str(schema).strip()]) if item]
    if not normalized:
        return []

    placeholders = ",".join(["%s"] * len(normalized))
    table_rows = query_rows(
        conn,
        f"""
        SELECT TABLE_SCHEMA AS db_name, TABLE_NAME AS table_name, TABLE_COMMENT AS table_comment
        FROM information_schema.TABLES
        WHERE TABLE_TYPE='BASE TABLE' AND TABLE_SCHEMA IN ({placeholders})
        ORDER BY TABLE_SCHEMA, TABLE_NAME
        """,
        normalized,
    )
    column_rows = query_rows(
        conn,
        f"""
        SELECT TABLE_SCHEMA AS db_name,
               TABLE_NAME AS table_name,
               COLUMN_NAME AS field_name,
               COLUMN_TYPE AS field_type,
               COLUMN_COMMENT AS field_comment,
               COLUMN_KEY AS column_key
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA IN ({placeholders})
        ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION
        """,
        normalized,
    )

    field_map: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in column_rows:
        key = (str(row["db_name"]), str(row["table_name"]))
        field_map[key].append(
            {
                "field_name": row.get("field_name"),
                "field_type": row.get("field_type"),
                "field_comment": row.get("field_comment") or "",
                "is_primary": str(row.get("column_key") or "").upper() == "PRI",
                "is_partition": False,
            }
        )

    items: list[dict[str, Any]] = []
    for row in table_rows:
        db_name = str(row.get("db_name") or "")
        table_name = str(row.get("table_name") or "")
        if not db_name or not table_name:
            continue
        items.append(
            {
                "table_id": None,
                "table_name": table_name,
                "table_comment": row.get("table_comment") or "",
                "db_name": db_name,
                "layer": "MYSQL",
                "business_domain": None,
                "data_domain": None,
                "fields": field_map.get((db_name, table_name), []),
            }
        )
    return items


def merge_metadata(registry_items: list[dict[str, Any]], physical_items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    existing_keys: dict[tuple[str, str], int] = {}

    for item in registry_items:
        db_name = str(item.get("db_name") or "").strip()
        table_name = str(item.get("table_name") or "").strip()
        if not db_name or not table_name:
            continue
        existing_keys[(db_name, table_name)] = len(merged)
        merged.append(item)

    for item in physical_items:
        db_name = str(item.get("db_name") or "").strip()
        table_name = str(item.get("table_name") or "").strip()
        if not db_name or not table_name:
            continue
        key = (db_name, table_name)
        if key not in existing_keys:
            existing_keys[key] = len(merged)
            merged.append(item)
            continue

        index = existing_keys[key]
        if not merged[index].get("fields"):
            merged[index]["fields"] = item.get("fields") or []
        if not merged[index].get("table_comment"):
            merged[index]["table_comment"] = item.get("table_comment") or ""

    merged.sort(key=lambda item: (str(item.get("db_name") or ""), str(item.get("table_name") or "")))
    return merged


def load_lineage(conn: pymysql.connections.Connection, metadata_schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, metadata_schema, "data_lineage") or not table_exists(conn, metadata_schema, "data_table"):
        return []

    rows = query_rows(
        conn,
        f"""
        SELECT dl.id,
               dl.upstream_table_id,
               dl.downstream_table_id,
               dl.lineage_type,
               ut.db_name AS upstream_db,
               ut.table_name AS upstream_table,
               dt.db_name AS downstream_db,
               dt.table_name AS downstream_table
        FROM `{metadata_schema}`.`data_lineage` dl
        LEFT JOIN `{metadata_schema}`.`data_table` ut
          ON ut.id = dl.upstream_table_id AND ut.deleted = 0
        LEFT JOIN `{metadata_schema}`.`data_table` dt
          ON dt.id = dl.downstream_table_id AND dt.deleted = 0
        WHERE dl.deleted = 0
        ORDER BY dl.id
        """,
    )
    return [
        {
            "lineage_id": row.get("id"),
            "lineage_type": row.get("lineage_type"),
            "upstream_table_id": row.get("upstream_table_id"),
            "upstream_db": row.get("upstream_db"),
            "upstream_table": row.get("upstream_table"),
            "downstream_table_id": row.get("downstream_table_id"),
            "downstream_db": row.get("downstream_db"),
            "downstream_table": row.get("downstream_table"),
        }
        for row in rows
    ]


def load_few_shots(conn: pymysql.connections.Connection, knowledge_schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, knowledge_schema, "aq_knowledge_qa"):
        return []
    has_enabled = column_exists(conn, knowledge_schema, "aq_knowledge_qa", "enabled")
    sql = f"""
        SELECT id, question, answer, tags
        FROM `{knowledge_schema}`.`aq_knowledge_qa`
    """
    if has_enabled:
        sql += " WHERE enabled = 1"
    sql += " ORDER BY id"
    rows = query_rows(conn, sql)
    items: list[dict[str, Any]] = []
    for row in rows:
        question = str(row.get("question") or "").strip()
        answer = str(row.get("answer") or "").strip()
        if not question or not answer:
            continue
        items.append(
            {
                "id": row.get("id"),
                "question": question,
                "answer": answer,
                "tags": parse_list(row.get("tags")),
            }
        )
    return items


def load_business_rules(conn: pymysql.connections.Connection, knowledge_schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, knowledge_schema, "aq_knowledge_business"):
        return []
    has_enabled = column_exists(conn, knowledge_schema, "aq_knowledge_business", "enabled")
    sql = f"""
        SELECT id, term, synonyms, definition
        FROM `{knowledge_schema}`.`aq_knowledge_business`
    """
    if has_enabled:
        sql += " WHERE enabled = 1"
    sql += " ORDER BY id"
    rows = query_rows(conn, sql)
    items: list[dict[str, Any]] = []
    for row in rows:
        term = str(row.get("term") or "").strip()
        if not term:
            continue
        items.append(
            {
                "id": row.get("id"),
                "term": term,
                "synonyms": parse_list(row.get("synonyms")),
                "definition": str(row.get("definition") or "").strip(),
            }
        )
    return items


def load_semantic_mappings(conn: pymysql.connections.Connection, knowledge_schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, knowledge_schema, "aq_knowledge_semantic"):
        return []
    has_enabled = column_exists(conn, knowledge_schema, "aq_knowledge_semantic", "enabled")
    sql = f"""
        SELECT id, business_name, table_name, field_name, synonyms, description
        FROM `{knowledge_schema}`.`aq_knowledge_semantic`
    """
    if has_enabled:
        sql += " WHERE enabled = 1"
    sql += " ORDER BY id"
    rows = query_rows(conn, sql)
    items: list[dict[str, Any]] = []
    for row in rows:
        business_name = str(row.get("business_name") or "").strip()
        if not business_name:
            continue
        items.append(
            {
                "id": row.get("id"),
                "business_name": business_name,
                "table_name": str(row.get("table_name") or "").strip() or None,
                "field_name": str(row.get("field_name") or "").strip() or None,
                "synonyms": parse_list(row.get("synonyms")),
                "description": str(row.get("description") or "").strip() or None,
            }
        )
    return items


def infer_engine(db_name: str, mysql_schemas: set[str]) -> str:
    name = db_name.strip().lower()
    if not name:
        return "mysql"
    if name in mysql_schemas:
        return "mysql"
    if name.startswith("doris") or "doris" in name:
        return "doris"
    return "mysql"


def build_source_mapping(metadata_items: list[dict[str, Any]], include_mysql_schemas: list[str]) -> list[dict[str, str]]:
    mysql_schemas = {schema.strip().lower() for schema in include_mysql_schemas if schema.strip()}
    db_names = sorted(
        {
            str(item.get("db_name") or "").strip()
            for item in metadata_items
            if str(item.get("db_name") or "").strip()
        }
    )
    return [{"database": name, "engine": infer_engine(name, mysql_schemas)} for name in db_names]


def build_bundle_payloads(
    conn: pymysql.connections.Connection,
    *,
    metadata_schema: str,
    knowledge_schema: str,
    include_mysql_schemas: list[str],
    default_engine: str = "doris",
    existing_manifest: dict[str, Any] | None = None,
) -> dict[str, Any]:
    normalized_schemas = dedup(
        [
            *[str(item).strip() for item in include_mysql_schemas if str(item).strip()],
            metadata_schema,
            knowledge_schema,
        ]
    )

    registry_metadata = load_registry_metadata(conn, metadata_schema)
    physical_metadata = load_physical_metadata(conn, normalized_schemas)
    metadata_items = merge_metadata(registry_metadata, physical_metadata)
    lineage_items = load_lineage(conn, metadata_schema)
    semantic_items = load_semantic_mappings(conn, knowledge_schema)
    few_shot_items = load_few_shots(conn, knowledge_schema)
    business_rule_items = load_business_rules(conn, knowledge_schema)
    source_mapping_items = build_source_mapping(metadata_items, normalized_schemas)

    manifest = dict(existing_manifest or {})
    manifest.update(
        {
            "schema_version": "1.0",
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "description": manifest.get("description") or "DataAgent static skill bundle manifest",
            "entrypoint": "SKILL.md",
            "workflow": "methodology/nl2semantic2lf2sql.md",
            "stats": {
                "metadata_tables": len(metadata_items),
                "lineage_edges": len(lineage_items),
                "semantic_mappings": len(semantic_items),
                "few_shots": len(few_shot_items),
                "business_rules": len(business_rule_items),
            },
            "source": {
                "metadata_schema": metadata_schema,
                "metadata_include_schemas": normalized_schemas,
                "knowledge_schema": knowledge_schema,
            },
        }
    )

    return {
        "files": {
            "metadata/metadata_catalog.json": {"schema_version": "1.0", "items": metadata_items},
            "metadata/lineage_catalog.json": {"schema_version": "1.0", "items": lineage_items},
            "metadata/semantic_mappings.json": {"schema_version": "1.0", "items": semantic_items},
            "metadata/source_mapping.json": {
                "schema_version": "1.0",
                "default_engine": default_engine,
                "items": source_mapping_items,
            },
            "knowledge/few_shots.json": {"schema_version": "1.0", "items": few_shot_items},
            "knowledge/business_rules.json": {"schema_version": "1.0", "items": business_rule_items},
            "manifest.json": manifest,
        },
        "stats": manifest["stats"],
        "source": manifest["source"],
    }
