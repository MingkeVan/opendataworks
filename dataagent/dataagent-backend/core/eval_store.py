"""Evaluation dataset & run persistence — reads/writes to the ``dataagent`` schema."""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import logging
import uuid
from contextlib import contextmanager
from typing import Any

import pymysql
import pymysql.cursors

from config import get_settings

logger = logging.getLogger(__name__)

_BATCH_SIZE = 200


def _now_str() -> str:
    return dt.datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")


class EvalStore:

    def _connect(self):
        cfg = get_settings()
        return pymysql.connect(
            host=cfg.mysql_host,
            port=cfg.mysql_port,
            user=cfg.mysql_user,
            password=cfg.mysql_password,
            database=cfg.session_mysql_database,
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
            autocommit=False,
        )

    @contextmanager
    def _conn(self):
        conn = self._connect()
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    # ---- datasets ----

    def list_datasets(self, *, status: str = "") -> list[dict[str, Any]]:
        sql = "SELECT * FROM eval_dataset"
        params: list[Any] = []
        if status:
            sql += " WHERE status = %s"
            params.append(status)
        sql += " ORDER BY updated_at DESC"
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute(sql, params)
                return cur.fetchall()

    def get_dataset(self, dataset_id: str) -> dict[str, Any] | None:
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT * FROM eval_dataset WHERE dataset_id = %s", (dataset_id,))
                return cur.fetchone()

    def create_dataset(self, dataset_id: str, name: str, *, description: str = "",
                       category: str = "", suite_tags: list[str] | None = None,
                       case_count: int = 0, dataset_hash: str = "",
                       created_by: str = "") -> dict[str, Any]:
        now = _now_str()
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """INSERT INTO eval_dataset
                       (dataset_id, name, description, category, suite_tags,
                        case_count, dataset_hash, created_by, created_at, updated_at)
                       VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                    (dataset_id, name, description, category,
                     json.dumps(suite_tags or [], ensure_ascii=False),
                     case_count, dataset_hash, created_by, now, now),
                )
        return self.get_dataset(dataset_id)  # type: ignore[return-value]

    def update_dataset(self, dataset_id: str, patch: dict[str, Any]) -> dict[str, Any] | None:
        allowed = {"name", "description", "category", "suite_tags", "status"}
        sets: list[str] = []
        params: list[Any] = []
        for key in allowed:
            if key in patch:
                value = patch[key]
                if key == "suite_tags":
                    value = json.dumps(value or [], ensure_ascii=False)
                sets.append(f"{key} = %s")
                params.append(value)
        if not sets:
            return self.get_dataset(dataset_id)
        params.append(dataset_id)
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute(f"UPDATE eval_dataset SET {', '.join(sets)} WHERE dataset_id = %s", params)
        return self.get_dataset(dataset_id)

    def delete_dataset(self, dataset_id: str) -> bool:
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM eval_case WHERE dataset_id = %s", (dataset_id,))
                cur.execute("DELETE FROM eval_dataset WHERE dataset_id = %s", (dataset_id,))
                return cur.rowcount > 0

    def _refresh_dataset_stats(self, conn, dataset_id: str, dataset_hash: str, case_count: int) -> None:
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE eval_dataset SET case_count = %s, dataset_hash = %s WHERE dataset_id = %s",
                (case_count, dataset_hash, dataset_id),
            )

    # ---- cases ----

    def list_cases(self, dataset_id: str) -> list[dict[str, Any]]:
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT id, dataset_id, case_id, case_type, category, suite_tags, question, updated_at "
                    "FROM eval_case WHERE dataset_id = %s ORDER BY id",
                    (dataset_id,),
                )
                return cur.fetchall()

    def get_case(self, dataset_id: str, case_id: str) -> dict[str, Any] | None:
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT * FROM eval_case WHERE dataset_id = %s AND case_id = %s",
                    (dataset_id, case_id),
                )
                return cur.fetchone()

    def get_case_full_json(self, dataset_id: str) -> list[dict[str, Any]]:
        """Return full case_json for all cases in a dataset, ordered deterministically."""
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT case_json FROM eval_case WHERE dataset_id = %s ORDER BY id",
                    (dataset_id,),
                )
                rows = cur.fetchall()
        result = []
        for row in rows:
            try:
                result.append(json.loads(row["case_json"]))
            except (json.JSONDecodeError, TypeError):
                pass
        return result

    def replace_cases(self, dataset_id: str, cases: list[dict[str, Any]], dataset_hash: str) -> int:
        """Delete all existing cases and insert new ones; refresh dataset stats."""
        now = _now_str()
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM eval_case WHERE dataset_id = %s", (dataset_id,))
                for i in range(0, len(cases), _BATCH_SIZE):
                    batch = cases[i:i + _BATCH_SIZE]
                    cur.executemany(
                        """INSERT INTO eval_case
                           (dataset_id, case_id, case_type, category, suite_tags,
                            question, turns, case_json, created_at, updated_at)
                           VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                        [
                            (
                                dataset_id,
                                str(c.get("case_id") or ""),
                                str(c.get("case_type") or ""),
                                str(c.get("category") or ""),
                                json.dumps(c.get("suite_tags") or [], ensure_ascii=False),
                                str(c.get("question") or ""),
                                json.dumps(c.get("turns"), ensure_ascii=False) if c.get("turns") else None,
                                json.dumps(c, ensure_ascii=False, sort_keys=True),
                                now, now,
                            )
                            for c in batch
                        ],
                    )
            self._refresh_dataset_stats(conn, dataset_id, dataset_hash, len(cases))
        return len(cases)

    def upsert_case(self, dataset_id: str, case: dict[str, Any], dataset_hash: str, case_count: int) -> dict[str, Any]:
        """Insert or update a single case; caller provides pre-computed hash/count."""
        now = _now_str()
        case_id = str(case.get("case_id") or "")
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """INSERT INTO eval_case
                       (dataset_id, case_id, case_type, category, suite_tags,
                        question, turns, case_json, created_at, updated_at)
                       VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                       ON DUPLICATE KEY UPDATE
                        case_type=VALUES(case_type), category=VALUES(category),
                        suite_tags=VALUES(suite_tags), question=VALUES(question),
                        turns=VALUES(turns), case_json=VALUES(case_json), updated_at=VALUES(updated_at)""",
                    (
                        dataset_id, case_id,
                        str(case.get("case_type") or ""),
                        str(case.get("category") or ""),
                        json.dumps(case.get("suite_tags") or [], ensure_ascii=False),
                        str(case.get("question") or ""),
                        json.dumps(case.get("turns"), ensure_ascii=False) if case.get("turns") else None,
                        json.dumps(case, ensure_ascii=False, sort_keys=True),
                        now, now,
                    ),
                )
            self._refresh_dataset_stats(conn, dataset_id, dataset_hash, case_count)
        return self.get_case(dataset_id, case_id)  # type: ignore[return-value]

    def delete_case(self, dataset_id: str, case_id: str, dataset_hash: str, case_count: int) -> bool:
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "DELETE FROM eval_case WHERE dataset_id = %s AND case_id = %s",
                    (dataset_id, case_id),
                )
                deleted = cur.rowcount > 0
            if deleted:
                self._refresh_dataset_stats(conn, dataset_id, dataset_hash, case_count)
        return deleted

    # ---- runs (ingestion) ----

    def _run_id_from_summary(self, summary: dict[str, Any]) -> str:
        raw = json.dumps(summary, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        return hashlib.sha256(raw).hexdigest()[:24]

    def get_run(self, run_id: str) -> dict[str, Any] | None:
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT * FROM eval_run WHERE run_id = %s", (run_id,))
                return cur.fetchone()

    def ingest_run(self, summary: dict[str, Any], cases: list[dict[str, Any]],
                   *, run_id: str = "", started_at: str = "") -> dict[str, Any]:
        """Idempotent ingestion of a run. Returns existing row on duplicate run_id."""
        if not run_id:
            run_id = self._run_id_from_summary(summary)

        existing = self.get_run(run_id)
        if existing:
            return existing

        metrics = summary.get("metrics") or {}
        avg_score = 0.0
        raw_avg = metrics.get("average_score")
        if isinstance(raw_avg, (int, float)):
            avg_score = float(raw_avg)

        resolved_started: str | None = None
        if started_at:
            resolved_started = started_at
        else:
            label = str(summary.get("run_label") or "").strip()
            if label:
                try:
                    dt.datetime.strptime(label, "%Y%m%d-%H%M%S")
                    resolved_started = f"{label[:4]}-{label[4:6]}-{label[6:8]} {label[9:11]}:{label[11:13]}:{label[13:15]}"
                except (ValueError, IndexError):
                    pass

        now = _now_str()
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """INSERT INTO eval_run
                       (run_id, dataset_id, dataset_hash, run_label, environment_label,
                        evaluation_engine, engine_version, model, judge_model,
                        judge_prompt_version, metric_semantics_version, concurrency,
                        run_status, passed, recommendation,
                        total_cases, passed_cases, failed_cases, veto_count, judge_failed_count,
                        average_score, metrics_json, summary_json, started_at, ingested_at)
                       VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                    (
                        run_id,
                        str(summary.get("dataset_id") or ""),
                        str(summary.get("dataset_hash") or ""),
                        str(summary.get("run_label") or ""),
                        str(summary.get("environment_label") or ""),
                        str(summary.get("evaluation_engine") or ""),
                        str(summary.get("engine_version") or ""),
                        str(summary.get("model") or ""),
                        str(summary.get("judge_model") or ""),
                        str(summary.get("judge_prompt_version") or ""),
                        str(summary.get("metric_semantics_version") or ""),
                        int(summary.get("concurrency") or 1),
                        str(summary.get("run_status") or ""),
                        1 if summary.get("passed") else 0,
                        str(summary.get("recommendation") or ""),
                        int(summary.get("total_cases") or 0),
                        int(summary.get("passed_cases") or 0),
                        int(summary.get("failed_cases") or 0),
                        int(summary.get("veto_count") or 0),
                        int(summary.get("judge_failed_count") or 0),
                        avg_score,
                        json.dumps(metrics, ensure_ascii=False),
                        json.dumps(summary, ensure_ascii=False),
                        resolved_started,
                        now,
                    ),
                )
                for i in range(0, len(cases), _BATCH_SIZE):
                    batch = cases[i:i + _BATCH_SIZE]
                    cur.executemany(
                        """INSERT INTO eval_run_case
                           (run_id, case_id, category, score, case_passed,
                            task_status, hallucination, dimension_scores_json, case_json)
                           VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                        [
                            (
                                run_id,
                                str(c.get("case_id") or ""),
                                str(c.get("category") or ""),
                                float((c.get("judge") or {}).get("score") or 0),
                                1 if c.get("case_passed") else 0,
                                str(c.get("task_status") or ""),
                                1 if (c.get("judge") or {}).get("hallucination") else 0,
                                json.dumps((c.get("judge") or {}).get("dimension_scores") or {}, ensure_ascii=False),
                                json.dumps(c, ensure_ascii=False),
                            )
                            for c in batch
                        ],
                    )
        return self.get_run(run_id)  # type: ignore[return-value]

    # ---- run queries ----

    def list_runs(self, *, dataset_id: str = "", evaluation_engine: str = "",
                  limit: int = 100, offset: int = 0) -> list[dict[str, Any]]:
        sql = """SELECT run_id, dataset_id, dataset_hash, run_label, environment_label,
                        evaluation_engine, engine_version, model, judge_model,
                        run_status, passed, recommendation,
                        total_cases, passed_cases, failed_cases, average_score,
                        started_at, ingested_at
                 FROM eval_run WHERE 1=1"""
        params: list[Any] = []
        if dataset_id:
            sql += " AND dataset_id = %s"
            params.append(dataset_id)
        if evaluation_engine:
            sql += " AND evaluation_engine = %s"
            params.append(evaluation_engine)
        sql += " ORDER BY COALESCE(started_at, ingested_at) DESC LIMIT %s OFFSET %s"
        params.extend([limit, offset])
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute(sql, params)
                return cur.fetchall()

    def list_run_cases(self, run_id: str) -> list[dict[str, Any]]:
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """SELECT id, run_id, case_id, category, score, case_passed,
                              task_status, hallucination, dimension_scores_json
                       FROM eval_run_case WHERE run_id = %s ORDER BY id""",
                    (run_id,),
                )
                return cur.fetchall()

    def get_run_case_detail(self, run_id: str, case_id: str) -> dict[str, Any] | None:
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT * FROM eval_run_case WHERE run_id = %s AND case_id = %s",
                    (run_id, case_id),
                )
                return cur.fetchone()

    def trend_series(self, *, dataset_id: str = "", evaluation_engine: str = "",
                     limit: int = 50) -> list[dict[str, Any]]:
        """Return time-ordered run summaries for trend charts."""
        sql = """SELECT run_id, run_label, evaluation_engine, model,
                        average_score, passed, total_cases, passed_cases, failed_cases,
                        metrics_json, started_at, ingested_at
                 FROM eval_run WHERE run_status = 'completed'"""
        params: list[Any] = []
        if dataset_id:
            sql += " AND dataset_id = %s"
            params.append(dataset_id)
        if evaluation_engine:
            sql += " AND evaluation_engine = %s"
            params.append(evaluation_engine)
        sql += " ORDER BY COALESCE(started_at, ingested_at) ASC LIMIT %s"
        params.append(limit)
        with self._conn() as conn:
            with conn.cursor() as cur:
                cur.execute(sql, params)
                rows = cur.fetchall()
        for row in rows:
            raw = row.pop("metrics_json", None)
            if raw:
                try:
                    metrics = json.loads(raw)
                except (json.JSONDecodeError, TypeError):
                    metrics = {}
            else:
                metrics = {}
            row["intent_accuracy"] = _ratio_value(metrics, "intent_accuracy")
            row["ontology_accuracy"] = _ratio_value(metrics, "ontology_accuracy")
            row["hallucination_rate"] = float(metrics.get("hallucination_rate") or 0)
            row["result_consistency_rate"] = _ratio_value(metrics, "result_consistency_rate")
            row["data_accuracy"] = _ratio_value(metrics, "data_accuracy")
            row["effective_pass_rate"] = _ratio_value(metrics, "effective_pass_rate")
            row["time"] = str(row.get("started_at") or row.get("ingested_at") or "")
        return rows


def _ratio_value(metrics: dict, key: str) -> float | None:
    raw = metrics.get(key)
    if raw is None:
        return None
    if isinstance(raw, dict):
        v = raw.get("value")
        return float(v) if v is not None else None
    return float(raw)


_instance: EvalStore | None = None


def get_eval_store() -> EvalStore:
    global _instance
    if _instance is None:
        _instance = EvalStore()
    return _instance
