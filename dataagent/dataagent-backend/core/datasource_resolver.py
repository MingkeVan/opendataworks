from __future__ import annotations

from dataclasses import dataclass

import pymysql

from config import get_settings


class DatasourceResolutionError(RuntimeError):
    pass


@dataclass
class ResolvedDatasource:
    engine: str
    database: str
    host: str
    port: int
    user: str
    password: str
    cluster_id: int | None = None
    cluster_name: str = ""
    source_type: str = ""
    resolved_by: str = ""


def resolve_engine_for_database(database: str | None) -> str:
    target_database = str(database or "").strip()
    if not target_database:
        raise DatasourceResolutionError("database is required")

    platform_match = _resolve_platform_mysql("mysql", target_database)
    if platform_match is not None:
        return "mysql"

    cluster = _lookup_cluster_by_database(target_database) or _lookup_cluster_by_db_user(target_database)
    if cluster is None:
        raise DatasourceResolutionError(f"未在 opendataworks 平台表中找到数据库 `{target_database}` 的数据源类型")

    source_type = str(cluster.get("source_type") or "").strip().upper() or "DORIS"
    return "mysql" if source_type == "MYSQL" else "doris"


def resolve_query_datasource(engine: str, database: str | None) -> ResolvedDatasource:
    normalized_engine = _normalize_engine(engine)
    target_database = str(database or "").strip()

    platform_match = _resolve_platform_mysql(normalized_engine, target_database)
    if platform_match is not None:
        return platform_match

    if not target_database:
        raise DatasourceResolutionError("database is required")

    cluster = _lookup_cluster_by_database(target_database)
    if cluster is None and normalized_engine == "doris":
        cluster = _lookup_cluster_by_db_user(target_database)
    if cluster is None:
        raise DatasourceResolutionError(f"未在 opendataworks 平台表中找到数据库 `{target_database}` 的数据源信息")

    source_type = str(cluster.get("source_type") or "").strip().upper() or _source_type_for_engine(normalized_engine)
    expected_source_type = _source_type_for_engine(normalized_engine)
    if source_type != expected_source_type:
        raise DatasourceResolutionError(
            f"数据库 `{target_database}` 绑定的数据源类型为 {source_type}，与当前 {normalized_engine} 查询不匹配"
        )

    user = str(cluster.get("username") or "").strip()
    password = str(cluster.get("password") or "")
    resolved_by = str(cluster.get("resolved_by") or "data_table")

    if normalized_engine == "doris":
        readonly_credential = _lookup_doris_readonly_user(int(cluster.get("cluster_id") or 0), target_database)
        if readonly_credential is not None:
            readonly_user = str(readonly_credential.get("readonly_username") or "").strip()
            if readonly_user:
                user = readonly_user
                password = str(readonly_credential.get("readonly_password") or "")
                resolved_by = "doris_database_users"

    if not str(cluster.get("fe_host") or "").strip():
        raise DatasourceResolutionError(f"数据库 `{target_database}` 的数据源缺少主机地址")
    if not user:
        raise DatasourceResolutionError(f"数据库 `{target_database}` 的数据源缺少用户名")

    return ResolvedDatasource(
        engine=normalized_engine,
        database=target_database,
        host=str(cluster.get("fe_host") or "").strip(),
        port=int(cluster.get("fe_port") or _default_port_for_engine(normalized_engine)),
        user=user,
        password=password,
        cluster_id=int(cluster.get("cluster_id") or 0) or None,
        cluster_name=str(cluster.get("cluster_name") or ""),
        source_type=source_type,
        resolved_by=resolved_by,
    )


def _normalize_engine(engine: str) -> str:
    value = str(engine or "").strip().lower()
    if value not in {"mysql", "doris"}:
        raise DatasourceResolutionError(f"unsupported engine: {engine}")
    return value


def _source_type_for_engine(engine: str) -> str:
    return "MYSQL" if engine == "mysql" else "DORIS"


def _default_port_for_engine(engine: str) -> int:
    return 3306 if engine == "mysql" else 9030


def _platform_metadata_schema() -> str:
    cfg = get_settings()
    return str(cfg.mysql_database or "opendataworks").strip() or "opendataworks"


def _resolve_platform_mysql(engine: str, database: str) -> ResolvedDatasource | None:
    if engine != "mysql":
        return None

    cfg = get_settings()
    target_database = database or str(cfg.mysql_database or "").strip()
    platform_databases = {
        str(cfg.mysql_database or "").strip(),
        str(cfg.session_mysql_database or "").strip(),
    }
    if target_database not in platform_databases:
        return None

    return ResolvedDatasource(
        engine="mysql",
        database=target_database,
        host=str(cfg.mysql_host or "").strip(),
        port=int(cfg.mysql_port or 3306),
        user=str(cfg.mysql_user or "").strip(),
        password=str(cfg.mysql_password or ""),
        cluster_id=None,
        cluster_name="platform-mysql",
        source_type="MYSQL",
        resolved_by="platform_runtime",
    )


def _connect_platform_metadata():
    cfg = get_settings()
    return pymysql.connect(
        host=cfg.mysql_host,
        port=cfg.mysql_port,
        user=cfg.mysql_user,
        password=cfg.mysql_password,
        database=_platform_metadata_schema(),
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        connect_timeout=10,
        read_timeout=30,
        write_timeout=30,
    )


def _lookup_cluster_by_database(database: str) -> dict | None:
    metadata_schema = _platform_metadata_schema()
    conn = _connect_platform_metadata()
    try:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT
                    dt.cluster_id,
                    dc.cluster_name,
                    COALESCE(NULLIF(dc.source_type, ''), 'DORIS') AS source_type,
                    dc.fe_host,
                    dc.fe_port,
                    dc.username,
                    dc.password,
                    COALESCE(dc.is_default, 0) AS is_default,
                    COALESCE(dc.status, '') AS cluster_status
                FROM `{metadata_schema}`.`data_table` dt
                INNER JOIN `{metadata_schema}`.`doris_cluster` dc
                    ON dc.id = dt.cluster_id
                WHERE dt.deleted = 0
                  AND dc.deleted = 0
                  AND dt.db_name = %s
                  AND (dt.status IS NULL OR dt.status <> 'deprecated')
                GROUP BY
                    dt.cluster_id,
                    dc.cluster_name,
                    dc.source_type,
                    dc.fe_host,
                    dc.fe_port,
                    dc.username,
                    dc.password,
                    dc.is_default,
                    dc.status
                ORDER BY
                    CASE WHEN COALESCE(dc.status, '') = 'active' THEN 0 ELSE 1 END,
                    COALESCE(dc.is_default, 0) DESC,
                    dt.cluster_id ASC
                LIMIT 2
                """,
                (database,),
            )
            rows = list(cur.fetchall() or [])
    finally:
        conn.close()

    if not rows:
        return None
    if len(rows) > 1:
        raise DatasourceResolutionError(f"数据库 `{database}` 在 data_table 中命中了多个 cluster_id，请先收敛唯一数据源")

    row = dict(rows[0])
    row["resolved_by"] = "data_table"
    return row


def _lookup_cluster_by_db_user(database: str) -> dict | None:
    metadata_schema = _platform_metadata_schema()
    conn = _connect_platform_metadata()
    try:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT
                    du.cluster_id,
                    dc.cluster_name,
                    COALESCE(NULLIF(dc.source_type, ''), 'DORIS') AS source_type,
                    dc.fe_host,
                    dc.fe_port,
                    dc.username,
                    dc.password,
                    COALESCE(dc.is_default, 0) AS is_default,
                    COALESCE(dc.status, '') AS cluster_status
                FROM `{metadata_schema}`.`doris_database_users` du
                INNER JOIN `{metadata_schema}`.`doris_cluster` dc
                    ON dc.id = du.cluster_id
                WHERE dc.deleted = 0
                  AND du.database_name = %s
                GROUP BY
                    du.cluster_id,
                    dc.cluster_name,
                    dc.source_type,
                    dc.fe_host,
                    dc.fe_port,
                    dc.username,
                    dc.password,
                    dc.is_default,
                    dc.status
                ORDER BY
                    CASE WHEN COALESCE(dc.status, '') = 'active' THEN 0 ELSE 1 END,
                    COALESCE(dc.is_default, 0) DESC,
                    du.cluster_id ASC
                LIMIT 2
                """,
                (database,),
            )
            rows = list(cur.fetchall() or [])
    finally:
        conn.close()

    if not rows:
        return None
    if len(rows) > 1:
        raise DatasourceResolutionError(f"数据库 `{database}` 在 doris_database_users 中命中了多个 cluster_id，请先收敛唯一数据源")

    row = dict(rows[0])
    row["resolved_by"] = "doris_database_users"
    return row


def _lookup_doris_readonly_user(cluster_id: int, database: str) -> dict | None:
    if not cluster_id:
        return None

    metadata_schema = _platform_metadata_schema()
    conn = _connect_platform_metadata()
    try:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT readonly_username, readonly_password
                FROM `{metadata_schema}`.`doris_database_users`
                WHERE cluster_id = %s AND database_name = %s
                ORDER BY id ASC
                LIMIT 1
                """,
                (cluster_id, database),
            )
            row = cur.fetchone()
    finally:
        conn.close()
    return dict(row) if row else None
