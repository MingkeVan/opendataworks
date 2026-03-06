from __future__ import annotations

import argparse
import json
import os

import pymysql


def main():
    parser = argparse.ArgumentParser(description="Query OpenDataWorks metadata tables")
    parser.add_argument("--kind", choices=["tables", "lineage", "datasource"], required=True)
    parser.add_argument("--database", default="")
    parser.add_argument("--host", default=os.getenv("ODW_MYSQL_HOST", "localhost"))
    parser.add_argument("--port", type=int, default=int(os.getenv("ODW_MYSQL_PORT", "3306")))
    parser.add_argument("--user", default=os.getenv("ODW_MYSQL_USER", "root"))
    parser.add_argument("--password", default=os.getenv("ODW_MYSQL_PASSWORD", ""))
    parser.add_argument("--schema", default=os.getenv("ODW_MYSQL_DATABASE", "opendataworks"))
    args = parser.parse_args()

    conn = pymysql.connect(
        host=args.host,
        port=args.port,
        user=args.user,
        password=args.password,
        database=args.schema,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with conn.cursor() as cur:
            if args.kind == "tables":
                cur.execute(
                    """
                    SELECT dt.id, dt.cluster_id, dt.db_name, dt.table_name, dt.table_comment,
                           df.field_name, df.field_type, df.field_comment
                    FROM data_table dt
                    LEFT JOIN data_field df ON df.table_id = dt.id AND df.deleted = 0
                    WHERE dt.deleted = 0
                      AND (dt.status IS NULL OR dt.status <> 'deprecated')
                    ORDER BY dt.db_name, dt.table_name, df.field_order, df.id
                    """
                )
            elif args.kind == "lineage":
                cur.execute(
                    """
                    SELECT dl.id, dl.lineage_type,
                           ut.db_name AS upstream_db, ut.table_name AS upstream_table,
                           dt.db_name AS downstream_db, dt.table_name AS downstream_table
                    FROM data_lineage dl
                    LEFT JOIN data_table ut ON ut.id = dl.upstream_table_id AND ut.deleted = 0
                    LEFT JOIN data_table dt ON dt.id = dl.downstream_table_id AND dt.deleted = 0
                    WHERE dl.deleted = 0
                    ORDER BY dl.id
                    """
                )
            else:
                cur.execute(
                    """
                    SELECT dt.db_name, dt.cluster_id, dc.source_type, dc.fe_host, dc.fe_port, dc.username, dc.password,
                           du.readonly_username, du.readonly_password
                    FROM data_table dt
                    LEFT JOIN doris_cluster dc ON dc.id = dt.cluster_id AND dc.deleted = 0
                    LEFT JOIN doris_database_users du ON du.cluster_id = dt.cluster_id AND du.database_name = dt.db_name
                    WHERE dt.deleted = 0
                      AND (%s = '' OR dt.db_name = %s)
                    ORDER BY dt.db_name, dt.cluster_id
                    """,
                    (args.database, args.database),
                )
            print(json.dumps(cur.fetchall(), ensure_ascii=False, indent=2))
    finally:
        conn.close()


if __name__ == "__main__":
    main()
