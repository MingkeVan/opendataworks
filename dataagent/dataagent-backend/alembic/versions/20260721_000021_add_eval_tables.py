"""add evaluation dataset and run tables

Revision ID: 20260721_000021
Revises: 20260720_000020
Create Date: 2026-07-21 12:00:00
"""
from __future__ import annotations

from alembic import op
from sqlalchemy import inspect


revision = "20260721_000021"
down_revision = "20260720_000020"
branch_labels = None
depends_on = None


def _has_table(table_name: str) -> bool:
    return inspect(op.get_bind()).has_table(table_name)


def upgrade() -> None:
    if not _has_table("eval_dataset"):
        op.execute(
            """
            CREATE TABLE eval_dataset (
                dataset_id VARCHAR(128) NOT NULL PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                description TEXT NULL,
                category VARCHAR(128) NOT NULL DEFAULT '',
                suite_tags JSON NULL,
                case_count INT NOT NULL DEFAULT 0,
                dataset_hash CHAR(64) NOT NULL DEFAULT '',
                status VARCHAR(32) NOT NULL DEFAULT 'active',
                created_by VARCHAR(255) NOT NULL DEFAULT '',
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_eval_dataset_status (status),
                INDEX idx_eval_dataset_updated (updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """
        )

    if not _has_table("eval_case"):
        op.execute(
            """
            CREATE TABLE eval_case (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                dataset_id VARCHAR(128) NOT NULL,
                case_id VARCHAR(255) NOT NULL,
                case_type VARCHAR(64) NOT NULL DEFAULT '',
                category VARCHAR(128) NOT NULL DEFAULT '',
                suite_tags JSON NULL,
                question TEXT NULL,
                turns JSON NULL,
                case_json LONGTEXT NOT NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_eval_case_dataset_case (dataset_id, case_id),
                INDEX idx_eval_case_dataset (dataset_id),
                INDEX idx_eval_case_type (case_type)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """
        )

    if not _has_table("eval_run"):
        op.execute(
            """
            CREATE TABLE eval_run (
                run_id VARCHAR(128) NOT NULL PRIMARY KEY,
                dataset_id VARCHAR(128) NOT NULL DEFAULT '',
                dataset_hash CHAR(64) NOT NULL DEFAULT '',
                run_label VARCHAR(255) NOT NULL DEFAULT '',
                environment_label VARCHAR(255) NOT NULL DEFAULT '',
                evaluation_engine VARCHAR(64) NOT NULL DEFAULT '',
                engine_version VARCHAR(64) NOT NULL DEFAULT '',
                model VARCHAR(255) NOT NULL DEFAULT '',
                judge_model VARCHAR(255) NOT NULL DEFAULT '',
                judge_prompt_version VARCHAR(128) NOT NULL DEFAULT '',
                metric_semantics_version VARCHAR(64) NOT NULL DEFAULT '',
                concurrency INT NOT NULL DEFAULT 1,
                run_status VARCHAR(64) NOT NULL DEFAULT '',
                passed TINYINT(1) NOT NULL DEFAULT 0,
                recommendation VARCHAR(128) NOT NULL DEFAULT '',
                total_cases INT NOT NULL DEFAULT 0,
                passed_cases INT NOT NULL DEFAULT 0,
                failed_cases INT NOT NULL DEFAULT 0,
                veto_count INT NOT NULL DEFAULT 0,
                judge_failed_count INT NOT NULL DEFAULT 0,
                average_score DECIMAL(6,4) NOT NULL DEFAULT 0,
                metrics_json LONGTEXT NULL,
                summary_json LONGTEXT NULL,
                started_at DATETIME NULL,
                ingested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_eval_run_dataset (dataset_id),
                INDEX idx_eval_run_engine (evaluation_engine),
                INDEX idx_eval_run_started (started_at),
                INDEX idx_eval_run_ingested (ingested_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """
        )

    if not _has_table("eval_run_case"):
        op.execute(
            """
            CREATE TABLE eval_run_case (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                run_id VARCHAR(128) NOT NULL,
                case_id VARCHAR(255) NOT NULL,
                category VARCHAR(128) NOT NULL DEFAULT '',
                score DECIMAL(6,4) NOT NULL DEFAULT 0,
                case_passed TINYINT(1) NOT NULL DEFAULT 0,
                task_status VARCHAR(64) NOT NULL DEFAULT '',
                hallucination TINYINT(1) NOT NULL DEFAULT 0,
                dimension_scores_json JSON NULL,
                case_json LONGTEXT NULL,
                INDEX idx_eval_run_case_run (run_id),
                INDEX idx_eval_run_case_case (case_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """
        )


def downgrade() -> None:
    for table in ("eval_run_case", "eval_run", "eval_case", "eval_dataset"):
        if _has_table(table):
            op.execute(f"DROP TABLE {table}")
