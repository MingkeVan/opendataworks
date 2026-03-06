#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pymysql


def _read_env_file(path: Path) -> dict[str, str]:
    data: dict[str, str] = {}
    if not path.exists():
        return data
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, val = line.split("=", 1)
        data[key.strip()] = val.strip()
    return data


def _parse_args() -> argparse.Namespace:
    backend_root = Path(__file__).resolve().parents[1]
    env_file = backend_root / ".env"
    env = _read_env_file(env_file)

    parser = argparse.ArgumentParser(
        description="Export MySQL metadata/knowledge to DataAgent static skills bundle",
    )
    parser.add_argument("--mysql-host", default=os.getenv("MYSQL_HOST", env.get("MYSQL_HOST", "127.0.0.1")))
    parser.add_argument(
        "--mysql-port",
        type=int,
        default=int(os.getenv("MYSQL_PORT", env.get("MYSQL_PORT", "3306"))),
    )
    parser.add_argument("--mysql-user", default=os.getenv("MYSQL_USER", env.get("MYSQL_USER", "root")))
    parser.add_argument("--mysql-password", default=os.getenv("MYSQL_PASSWORD", env.get("MYSQL_PASSWORD", "")))
    parser.add_argument(
        "--metadata-schema",
        default=os.getenv("MYSQL_DATABASE", env.get("MYSQL_DATABASE", "opendataworks")),
        help="Schema that stores managed metadata tables: data_table/data_field/data_lineage",
    )
    parser.add_argument(
        "--knowledge-schema",
        default=os.getenv("KNOWLEDGE_MYSQL_DATABASE", env.get("KNOWLEDGE_MYSQL_DATABASE", "dataagent")),
    )
    parser.add_argument(
        "--include-mysql-schemas",
        default=os.getenv("INCLUDE_MYSQL_SCHEMAS", "opendataworks,dataagent"),
        help="Comma-separated schemas to include physical metadata from information_schema",
    )
    parser.add_argument(
        "--skills-root",
        default=str((backend_root / "../.claude/skills/dataagent-nl2sql").resolve()),
    )
    parser.add_argument("--default-engine", choices=["mysql", "doris"], default="doris")
    return parser.parse_args()


def _query(conn: pymysql.connections.Connection, sql: str, params: tuple[Any, ...] | list[Any] | None = None) -> list[dict[str, Any]]:
    with conn.cursor() as cur:
        cur.execute(sql, params or [])
        return list(cur.fetchall())


def _table_exists(conn: pymysql.connections.Connection, schema: str, table: str) -> bool:
    rows = _query(
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


def _column_exists(conn: pymysql.connections.Connection, schema: str, table: str, column: str) -> bool:
    rows = _query(
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


def _to_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return False
    if isinstance(value, (int, float)):
        return value != 0
    text = str(value).strip().lower()
    return text in {"1", "true", "yes", "y"}


def _parse_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        vals = [str(x).strip() for x in value if str(x).strip()]
        return _dedup(vals)

    text = str(value).strip()
    if not text:
        return []

    if text.startswith("[") and text.endswith("]"):
        try:
            payload = json.loads(text)
            if isinstance(payload, list):
                vals = [str(x).strip() for x in payload if str(x).strip()]
                return _dedup(vals)
        except Exception:
            pass

    vals = [x.strip() for x in re.split(r"[,;，、\n]", text) if x.strip()]
    return _dedup(vals)


def _dedup(values: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for item in values:
        if item in seen:
            continue
        seen.add(item)
        out.append(item)
    return out


def _load_registry_metadata(conn: pymysql.connections.Connection, metadata_schema: str) -> list[dict[str, Any]]:
    if not _table_exists(conn, metadata_schema, "data_table") or not _table_exists(conn, metadata_schema, "data_field"):
        return []

    has_status = _column_exists(conn, metadata_schema, "data_table", "status")
    sql = f"""
        SELECT id, table_name, table_comment, db_name, layer, business_domain, data_domain
        FROM `{metadata_schema}`.`data_table`
        WHERE deleted = 0
    """
    if has_status:
        sql += " AND status = 'active'"
    sql += " ORDER BY id"

    table_rows = _query(conn, sql)
    if not table_rows:
        return []

    ids = [row["id"] for row in table_rows]
    placeholders = ",".join(["%s"] * len(ids))
    field_rows = _query(
        conn,
        f"""
        SELECT table_id, field_name, field_type, field_comment, is_primary, is_partition
        FROM `{metadata_schema}`.`data_field`
        WHERE deleted = 0 AND table_id IN ({placeholders})
        ORDER BY table_id, field_order, id
        """,
        ids,
    )

    field_map: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for row in field_rows:
        field_map[int(row["table_id"])].append(
            {
                "field_name": row.get("field_name"),
                "field_type": row.get("field_type"),
                "field_comment": row.get("field_comment"),
                "is_primary": _to_bool(row.get("is_primary")),
                "is_partition": _to_bool(row.get("is_partition")),
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


def _load_physical_metadata(conn: pymysql.connections.Connection, schemas: list[str]) -> list[dict[str, Any]]:
    schemas = [x for x in _dedup([s.strip() for s in schemas if s.strip()]) if x]
    if not schemas:
        return []

    placeholders = ",".join(["%s"] * len(schemas))
    table_rows = _query(
        conn,
        f"""
        SELECT TABLE_SCHEMA AS db_name, TABLE_NAME AS table_name, TABLE_COMMENT AS table_comment
        FROM information_schema.TABLES
        WHERE TABLE_TYPE='BASE TABLE' AND TABLE_SCHEMA IN ({placeholders})
        ORDER BY TABLE_SCHEMA, TABLE_NAME
        """,
        schemas,
    )

    column_rows = _query(
        conn,
        f"""
        SELECT
            TABLE_SCHEMA AS db_name,
            TABLE_NAME AS table_name,
            COLUMN_NAME AS field_name,
            COLUMN_TYPE AS field_type,
            COLUMN_COMMENT AS field_comment,
            COLUMN_KEY AS column_key,
            ORDINAL_POSITION AS ordinal_pos
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA IN ({placeholders})
        ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION
        """,
        schemas,
    )

    fields_map: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in column_rows:
        key = (str(row["db_name"]), str(row["table_name"]))
        fields_map[key].append(
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
                "fields": fields_map.get((db_name, table_name), []),
            }
        )
    return items


def _merge_metadata(
    registry_items: list[dict[str, Any]],
    physical_items: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    key_map: dict[tuple[str, str], int] = {}

    for item in registry_items:
        db_name = str(item.get("db_name") or "").strip()
        table_name = str(item.get("table_name") or "").strip()
        if not db_name or not table_name:
            continue
        key = (db_name, table_name)
        key_map[key] = len(merged)
        merged.append(item)

    for item in physical_items:
        db_name = str(item.get("db_name") or "").strip()
        table_name = str(item.get("table_name") or "").strip()
        if not db_name or not table_name:
            continue
        key = (db_name, table_name)
        if key not in key_map:
            key_map[key] = len(merged)
            merged.append(item)
            continue

        # Existing managed metadata wins; only fill missing fields if absent.
        idx = key_map[key]
        if not merged[idx].get("fields"):
            merged[idx]["fields"] = item.get("fields") or []
        if not merged[idx].get("table_comment"):
            merged[idx]["table_comment"] = item.get("table_comment") or ""

    merged.sort(key=lambda x: (str(x.get("db_name") or ""), str(x.get("table_name") or "")))
    return merged


def _load_lineage(conn: pymysql.connections.Connection, metadata_schema: str) -> list[dict[str, Any]]:
    if not _table_exists(conn, metadata_schema, "data_lineage") or not _table_exists(conn, metadata_schema, "data_table"):
        return []

    rows = _query(
        conn,
        f"""
        SELECT
            dl.id,
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

    items: list[dict[str, Any]] = []
    for row in rows:
        items.append(
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
        )
    return items


def _load_few_shots(conn: pymysql.connections.Connection, knowledge_schema: str) -> list[dict[str, Any]]:
    if not _table_exists(conn, knowledge_schema, "aq_knowledge_qa"):
        return []
    has_enabled = _column_exists(conn, knowledge_schema, "aq_knowledge_qa", "enabled")
    sql = f"""
        SELECT id, question, answer, tags
        FROM `{knowledge_schema}`.`aq_knowledge_qa`
    """
    if has_enabled:
        sql += " WHERE enabled = 1"
    sql += " ORDER BY id"
    rows = _query(conn, sql)
    items: list[dict[str, Any]] = []
    for row in rows:
        q = str(row.get("question") or "").strip()
        a = str(row.get("answer") or "").strip()
        if not q or not a:
            continue
        items.append(
            {
                "id": row.get("id"),
                "question": q,
                "answer": a,
                "tags": _parse_list(row.get("tags")),
            }
        )
    return items


def _load_business_rules(conn: pymysql.connections.Connection, knowledge_schema: str) -> list[dict[str, Any]]:
    if not _table_exists(conn, knowledge_schema, "aq_knowledge_business"):
        return []
    has_enabled = _column_exists(conn, knowledge_schema, "aq_knowledge_business", "enabled")
    sql = f"""
        SELECT id, term, synonyms, definition
        FROM `{knowledge_schema}`.`aq_knowledge_business`
    """
    if has_enabled:
        sql += " WHERE enabled = 1"
    sql += " ORDER BY id"
    rows = _query(conn, sql)
    items: list[dict[str, Any]] = []
    for row in rows:
        term = str(row.get("term") or "").strip()
        if not term:
            continue
        items.append(
            {
                "id": row.get("id"),
                "term": term,
                "synonyms": _parse_list(row.get("synonyms")),
                "definition": str(row.get("definition") or "").strip(),
            }
        )
    return items


def _load_semantic_mappings(conn: pymysql.connections.Connection, knowledge_schema: str) -> list[dict[str, Any]]:
    if not _table_exists(conn, knowledge_schema, "aq_knowledge_semantic"):
        return []
    has_enabled = _column_exists(conn, knowledge_schema, "aq_knowledge_semantic", "enabled")
    sql = f"""
        SELECT id, business_name, table_name, field_name, synonyms, description
        FROM `{knowledge_schema}`.`aq_knowledge_semantic`
    """
    if has_enabled:
        sql += " WHERE enabled = 1"
    sql += " ORDER BY id"
    rows = _query(conn, sql)
    items: list[dict[str, Any]] = []
    for row in rows:
        name = str(row.get("business_name") or "").strip()
        if not name:
            continue
        items.append(
            {
                "id": row.get("id"),
                "business_name": name,
                "table_name": str(row.get("table_name") or "").strip() or None,
                "field_name": str(row.get("field_name") or "").strip() or None,
                "synonyms": _parse_list(row.get("synonyms")),
                "description": str(row.get("description") or "").strip() or None,
            }
        )
    return items


def _infer_engine(db_name: str, mysql_schemas: set[str]) -> str:
    db = db_name.strip().lower()
    if not db:
        return "mysql"
    if db in mysql_schemas:
        return "mysql"
    if db.startswith("doris") or "doris" in db:
        return "doris"
    return "mysql"


def _build_source_mapping(metadata_items: list[dict[str, Any]], include_mysql_schemas: list[str]) -> list[dict[str, str]]:
    mysql_schemas = {x.strip().lower() for x in include_mysql_schemas if x.strip()}
    db_names = sorted(
        {
            str(item.get("db_name") or "").strip()
            for item in metadata_items
            if str(item.get("db_name") or "").strip()
        }
    )
    return [{"database": db, "engine": _infer_engine(db, mysql_schemas)} for db in db_names]


def _write_json(path: Path, payload: dict[str, Any]):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    args = _parse_args()

    include_mysql_schemas = _dedup([x.strip() for x in str(args.include_mysql_schemas).split(",") if x.strip()])
    if not include_mysql_schemas:
        include_mysql_schemas = [args.metadata_schema, args.knowledge_schema]
    include_mysql_schemas = _dedup([*include_mysql_schemas, args.metadata_schema, args.knowledge_schema])

    skills_root = Path(args.skills_root).resolve()
    knowledge_dir = skills_root / "knowledge"
    metadata_dir = skills_root / "metadata"

    conn = pymysql.connect(
        host=args.mysql_host,
        port=int(args.mysql_port),
        user=args.mysql_user,
        password=args.mysql_password,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        connect_timeout=10,
        read_timeout=60,
        write_timeout=30,
    )
    try:
        registry_meta = _load_registry_metadata(conn, args.metadata_schema)
        physical_meta = _load_physical_metadata(conn, include_mysql_schemas)
        metadata_items = _merge_metadata(registry_meta, physical_meta)

        lineage_items = _load_lineage(conn, args.metadata_schema)
        semantic_items = _load_semantic_mappings(conn, args.knowledge_schema)
        few_shot_items = _load_few_shots(conn, args.knowledge_schema)
        business_rule_items = _load_business_rules(conn, args.knowledge_schema)
        source_mapping_items = _build_source_mapping(metadata_items, include_mysql_schemas)

        _write_json(
            metadata_dir / "metadata_catalog.json",
            {"schema_version": "1.0", "items": metadata_items},
        )
        _write_json(
            metadata_dir / "lineage_catalog.json",
            {"schema_version": "1.0", "items": lineage_items},
        )
        _write_json(
            metadata_dir / "semantic_mappings.json",
            {"schema_version": "1.0", "items": semantic_items},
        )
        _write_json(
            metadata_dir / "source_mapping.json",
            {
                "schema_version": "1.0",
                "default_engine": args.default_engine,
                "items": source_mapping_items,
            },
        )
        _write_json(
            knowledge_dir / "few_shots.json",
            {"schema_version": "1.0", "items": few_shot_items},
        )
        _write_json(
            knowledge_dir / "business_rules.json",
            {"schema_version": "1.0", "items": business_rule_items},
        )

        manifest_path = skills_root / "manifest.json"
        manifest: dict[str, Any]
        if manifest_path.exists():
            try:
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                if not isinstance(manifest, dict):
                    manifest = {}
            except Exception:
                manifest = {}
        else:
            manifest = {}

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
                    "metadata_schema": args.metadata_schema,
                    "metadata_include_schemas": include_mysql_schemas,
                    "knowledge_schema": args.knowledge_schema,
                },
            }
        )
        _write_json(manifest_path, manifest)

        db_dist: dict[str, int] = defaultdict(int)
        for row in metadata_items:
            db = str(row.get("db_name") or "").strip() or "<empty>"
            db_dist[db] += 1

        print("skills_root=", str(skills_root))
        print("metadata_schema=", args.metadata_schema)
        print("knowledge_schema=", args.knowledge_schema)
        print("include_mysql_schemas=", ",".join(include_mysql_schemas))
        print("metadata_tables=", len(metadata_items))
        print("lineage_edges=", len(lineage_items))
        print("semantic_mappings=", len(semantic_items))
        print("few_shots=", len(few_shot_items))
        print("business_rules=", len(business_rule_items))
        print("source_mapping=", len(source_mapping_items))
        for db_name in sorted(db_dist.keys()):
            print(f"db[{db_name}]={db_dist[db_name]}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
