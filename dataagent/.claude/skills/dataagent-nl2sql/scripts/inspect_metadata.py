from __future__ import annotations

import argparse

from _opendataworks_runtime import connect_odw, error_payload, print_json, query_rows, serializable_rows


def main():
    parser = argparse.ArgumentParser(description="Inspect OpenDataWorks metadata for a database/table")
    parser.add_argument("--database", default="")
    parser.add_argument("--table", default="")
    parser.add_argument("--keyword", default="")
    parser.add_argument("--table-limit", type=int, default=12)
    args = parser.parse_args()

    database = str(args.database or "").strip()
    table_name = str(args.table or "").strip()
    keyword = str(args.keyword or "").strip()
    like_keyword = f"%{keyword}%"
    like_table = f"%{table_name}%"

    conn = connect_odw()
    try:
        rows = query_rows(
            conn,
            """
            SELECT
                dt.id AS table_id,
                dt.cluster_id,
                dt.db_name,
                dt.table_name,
                dt.table_comment,
                df.field_name,
                df.field_type,
                df.field_comment,
                df.field_order
            FROM data_table dt
            LEFT JOIN data_field df
              ON df.table_id = dt.id
             AND df.deleted = 0
            WHERE dt.deleted = 0
              AND (dt.status IS NULL OR dt.status <> 'deprecated')
              AND (%s = '' OR dt.db_name = %s)
              AND (
                %s = ''
                OR dt.table_name = %s
                OR dt.table_name LIKE %s
                OR dt.table_comment LIKE %s
              )
              AND (
                %s = ''
                OR dt.table_name LIKE %s
                OR dt.table_comment LIKE %s
                OR df.field_name LIKE %s
                OR df.field_comment LIKE %s
              )
            ORDER BY dt.db_name, dt.table_name, df.field_order, df.id
            LIMIT 800
            """,
            (
                database,
                database,
                table_name,
                table_name,
                like_table,
                like_table,
                keyword,
                like_keyword,
                like_keyword,
                like_keyword,
                like_keyword,
            ),
        )

        tables_by_id: dict[int, dict] = {}
        ordered_ids: list[int] = []
        for row in rows:
            table_id = int(row.get("table_id") or 0)
            if table_id not in tables_by_id:
                tables_by_id[table_id] = {
                    "table_id": table_id,
                    "cluster_id": row.get("cluster_id"),
                    "db_name": row.get("db_name"),
                    "table_name": row.get("table_name"),
                    "table_comment": row.get("table_comment"),
                    "fields": [],
                }
                ordered_ids.append(table_id)

            field_name = row.get("field_name")
            if field_name:
                tables_by_id[table_id]["fields"].append(
                    {
                        "field_name": field_name,
                        "field_type": row.get("field_type"),
                        "field_comment": row.get("field_comment"),
                    }
                )

        selected_ids = ordered_ids[: max(1, int(args.table_limit))]
        selected_tables = [tables_by_id[table_id] for table_id in selected_ids]

        lineage = []
        if selected_ids:
            placeholders = ",".join(["%s"] * len(selected_ids))
            lineage = query_rows(
                conn,
                f"""
                SELECT
                    dl.id,
                    dl.lineage_type,
                    ut.db_name AS upstream_db,
                    ut.table_name AS upstream_table,
                    dt.db_name AS downstream_db,
                    dt.table_name AS downstream_table
                FROM data_lineage dl
                LEFT JOIN data_table ut
                  ON ut.id = dl.upstream_table_id
                 AND ut.deleted = 0
                LEFT JOIN data_table dt
                  ON dt.id = dl.downstream_table_id
                 AND dt.deleted = 0
                WHERE dl.deleted = 0
                  AND (dl.upstream_table_id IN ({placeholders}) OR dl.downstream_table_id IN ({placeholders}))
                ORDER BY dl.id
                LIMIT 120
                """,
                tuple(selected_ids + selected_ids),
            )

        print_json(
            {
                "kind": "metadata_snapshot",
                "database": database or None,
                "table": table_name or None,
                "keyword": keyword or None,
                "table_count": len(selected_tables),
                "tables": selected_tables,
                "lineage": serializable_rows(lineage),
                "error": None,
            }
        )
    except Exception as exc:
        print_json(
            error_payload(
                "metadata_snapshot",
                str(exc),
                database=database or None,
                table=table_name or None,
                keyword=keyword or None,
            )
        )
    finally:
        conn.close()


if __name__ == "__main__":
    main()
