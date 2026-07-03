"""add topic auth owner columns

门户登录用户归属字段：让 da_agent_topic 能区分「匿名 portal 会话」与
「登录用户会话」。旧数据默认 auth_user_id='' 自然进入匿名池；auth 关闭时
谓词不看 owner，恢复原共享池语义。
见 docs/design/2026-07-01-dataagent-auth-design.md 第 4 节。

Revision ID: 20260701_000019
Revises: 20260613_000018
Create Date: 2026-07-01 12:00:00
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect


revision = "20260701_000019"
down_revision = "20260613_000018"
branch_labels = None
depends_on = None


def _has_column(table_name: str, column_name: str) -> bool:
    inspector = inspect(op.get_bind())
    return any(column.get("name") == column_name for column in inspector.get_columns(table_name))


def _has_index(table_name: str, index_name: str) -> bool:
    inspector = inspect(op.get_bind())
    return any(index.get("name") == index_name for index in inspector.get_indexes(table_name))


def upgrade() -> None:
    # auth_user_id 为命名空间化稳定标识（local:<username> / <provider>:<sub>），
    # OIDC sub 可能较长，取 255。
    if not _has_column("da_agent_topic", "auth_user_id"):
        op.add_column("da_agent_topic", sa.Column("auth_user_id", sa.String(length=255), nullable=False, server_default=""))
    if not _has_column("da_agent_topic", "auth_username"):
        op.add_column("da_agent_topic", sa.Column("auth_username", sa.String(length=255), nullable=False, server_default=""))

    # source 前导列匹配主要谓词 source='portal' AND auth_user_id=? ORDER BY updated_at。
    if not _has_index("da_agent_topic", "idx_da_agent_topic_auth_updated"):
        op.create_index(
            "idx_da_agent_topic_auth_updated",
            "da_agent_topic",
            ["source", "auth_user_id", "updated_at"],
        )


def downgrade() -> None:
    if _has_index("da_agent_topic", "idx_da_agent_topic_auth_updated"):
        op.drop_index("idx_da_agent_topic_auth_updated", table_name="da_agent_topic")
    for column_name in ("auth_username", "auth_user_id"):
        if _has_column("da_agent_topic", column_name):
            op.drop_column("da_agent_topic", column_name)
