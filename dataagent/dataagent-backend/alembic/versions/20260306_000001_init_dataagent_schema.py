"""init dataagent schema

Revision ID: 20260306_000001
Revises:
Create Date: 2026-03-06 16:40:00
"""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = "20260306_000001"
down_revision = None
branch_labels = None
depends_on = None

LEGACY_MESSAGE_COLUMNS = ("sql_text", "execution_json", "resolved_database")


def _current_schema() -> str:
    bind = op.get_bind()
    return str(bind.execute(sa.text("SELECT DATABASE()")).scalar() or "").strip()


def _table_exists(table_name: str) -> bool:
    bind = op.get_bind()
    schema = _current_schema()
    row = bind.execute(
        sa.text(
            """
            SELECT 1
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = :schema
              AND TABLE_NAME = :table_name
            LIMIT 1
            """
        ),
        {"schema": schema, "table_name": table_name},
    ).first()
    return row is not None


def _column_exists(table_name: str, column_name: str) -> bool:
    bind = op.get_bind()
    schema = _current_schema()
    row = bind.execute(
        sa.text(
            """
            SELECT 1
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = :schema
              AND TABLE_NAME = :table_name
              AND COLUMN_NAME = :column_name
            LIMIT 1
            """
        ),
        {"schema": schema, "table_name": table_name, "column_name": column_name},
    ).first()
    return row is not None


def _create_tables() -> None:
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS da_agent_settings (
            settings_key VARCHAR(32) NOT NULL PRIMARY KEY,
            provider_id VARCHAR(64) NOT NULL DEFAULT 'openrouter',
            model_name VARCHAR(255) NOT NULL DEFAULT 'anthropic/claude-sonnet-4.5',
            anthropic_api_key VARCHAR(512) NULL,
            anthropic_auth_token VARCHAR(512) NULL,
            anthropic_base_url VARCHAR(512) NULL,
            mysql_host VARCHAR(255) NULL,
            mysql_port INT NULL,
            mysql_user VARCHAR(255) NULL,
            mysql_password VARCHAR(255) NULL,
            mysql_database VARCHAR(255) NULL,
            doris_host VARCHAR(255) NULL,
            doris_port INT NULL,
            doris_user VARCHAR(255) NULL,
            doris_password VARCHAR(255) NULL,
            doris_database VARCHAR(255) NULL,
            skills_output_dir VARCHAR(512) NULL,
            raw_json LONGTEXT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """
    )
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS da_skill_document (
            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            relative_path VARCHAR(255) NOT NULL,
            file_name VARCHAR(128) NOT NULL,
            category VARCHAR(64) NOT NULL,
            content_type VARCHAR(32) NOT NULL,
            current_content LONGTEXT NOT NULL,
            current_hash CHAR(64) NOT NULL,
            current_version_id BIGINT NULL,
            version_count INT NOT NULL DEFAULT 0,
            last_change_source VARCHAR(32) NOT NULL DEFAULT 'import',
            last_change_summary VARCHAR(255) NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            UNIQUE KEY uk_skill_document_path (relative_path),
            KEY idx_skill_document_category (category),
            KEY idx_skill_document_updated (updated_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """
    )
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS da_skill_document_version (
            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            document_id BIGINT NOT NULL,
            version_no INT NOT NULL,
            change_source VARCHAR(32) NOT NULL,
            change_summary VARCHAR(255) NULL,
            actor VARCHAR(64) NULL,
            content LONGTEXT NOT NULL,
            content_hash CHAR(64) NOT NULL,
            file_size INT NOT NULL DEFAULT 0,
            metadata_json LONGTEXT NULL,
            parent_version_id BIGINT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE KEY uk_skill_doc_version (document_id, version_no),
            KEY idx_skill_doc_version_created (document_id, created_at),
            CONSTRAINT fk_da_skill_document_version_document
                FOREIGN KEY (document_id) REFERENCES da_skill_document(id)
                ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """
    )
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS da_chat_session (
            session_id VARCHAR(64) NOT NULL PRIMARY KEY,
            title VARCHAR(255) NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            KEY idx_updated_at (updated_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """
    )
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS da_chat_message (
            message_id VARCHAR(64) NOT NULL PRIMARY KEY,
            session_id VARCHAR(64) NOT NULL,
            role VARCHAR(16) NOT NULL,
            status VARCHAR(32) NOT NULL DEFAULT 'success',
            run_id VARCHAR(64) NULL,
            content LONGTEXT NOT NULL,
            error_json LONGTEXT NULL,
            provider_id VARCHAR(64) NULL,
            model_name VARCHAR(255) NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            KEY idx_session_created (session_id, created_at, message_id),
            KEY idx_run_id (run_id),
            CONSTRAINT fk_da_message_session
                FOREIGN KEY (session_id) REFERENCES da_chat_session(session_id)
                ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """
    )
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS da_chat_block (
            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            message_id VARCHAR(64) NOT NULL,
            block_id VARCHAR(64) NOT NULL,
            block_type VARCHAR(64) NOT NULL,
            status VARCHAR(32) NOT NULL DEFAULT 'success',
            content_json LONGTEXT NULL,
            seq INT NOT NULL DEFAULT 0,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            KEY idx_message_seq (message_id, seq),
            CONSTRAINT fk_da_block_message
                FOREIGN KEY (message_id) REFERENCES da_chat_message(message_id)
                ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """
    )
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS da_chat_event (
            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            run_id VARCHAR(64) NOT NULL,
            session_id VARCHAR(64) NOT NULL,
            message_id VARCHAR(64) NOT NULL,
            seq INT NOT NULL,
            event_type VARCHAR(64) NOT NULL,
            payload_json LONGTEXT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            KEY idx_run_seq (run_id, seq),
            KEY idx_session_message (session_id, message_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """
    )


def _purge_and_drop_legacy_columns() -> None:
    if not _table_exists("da_chat_message"):
        return

    legacy_columns = [column for column in LEGACY_MESSAGE_COLUMNS if _column_exists("da_chat_message", column)]
    if not legacy_columns:
        return

    if _table_exists("da_chat_event"):
        op.execute("DELETE FROM da_chat_event")
    if _table_exists("da_chat_block"):
        op.execute("DELETE FROM da_chat_block")
    if _table_exists("da_chat_message"):
        op.execute("DELETE FROM da_chat_message")
    if _table_exists("da_chat_session"):
        op.execute("DELETE FROM da_chat_session")

    for column in legacy_columns:
        op.execute(f"ALTER TABLE da_chat_message DROP COLUMN `{column}`")


def upgrade() -> None:
    _create_tables()
    _purge_and_drop_legacy_columns()


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS da_chat_event")
    op.execute("DROP TABLE IF EXISTS da_chat_block")
    op.execute("DROP TABLE IF EXISTS da_chat_message")
    op.execute("DROP TABLE IF EXISTS da_chat_session")
    op.execute("DROP TABLE IF EXISTS da_skill_document_version")
    op.execute("DROP TABLE IF EXISTS da_skill_document")
    op.execute("DROP TABLE IF EXISTS da_agent_settings")
