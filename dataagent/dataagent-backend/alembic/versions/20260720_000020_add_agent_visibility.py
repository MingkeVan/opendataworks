"""add visibility scope to agent profiles

Revision ID: 20260720_000020
Revises: 20260701_000019
Create Date: 2026-07-20 10:00:00
"""
from __future__ import annotations

import json

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect


revision = "20260720_000020"
down_revision = "20260701_000019"
branch_labels = None
depends_on = None


def _has_table(table_name: str) -> bool:
    return inspect(op.get_bind()).has_table(table_name)


def _has_column(table_name: str, column_name: str) -> bool:
    inspector = inspect(op.get_bind())
    return any(column.get("name") == column_name for column in inspector.get_columns(table_name))


def upgrade() -> None:
    if not _has_table("da_agent_profile"):
        return
    if not _has_column("da_agent_profile", "visibility_json"):
        op.add_column(
            "da_agent_profile",
            sa.Column("visibility_json", sa.Text(), nullable=True),
        )

    default_visibility = json.dumps(
        {"mode": "all", "allowed_users": [], "allowed_groups": []},
        ensure_ascii=False,
        sort_keys=True,
    )
    bind = op.get_bind()
    bind.execute(
        sa.text(
            """
            UPDATE da_agent_profile
            SET visibility_json = :default_visibility
            WHERE visibility_json IS NULL OR visibility_json = ''
            """
        ),
        {"default_visibility": default_visibility},
    )


def downgrade() -> None:
    if _has_table("da_agent_profile") and _has_column("da_agent_profile", "visibility_json"):
        op.drop_column("da_agent_profile", "visibility_json")
