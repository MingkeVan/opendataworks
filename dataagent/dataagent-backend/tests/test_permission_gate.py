"""Unit tests for session permission gating policy."""
from __future__ import annotations

from core import permission_gate as pg

PUBLISH = "mcp__portal__portal_publish_workflow"
SCHED_ONLINE = "mcp__portal__portal_workflow_schedule_online"
CREATE_TASK = "mcp__portal__portal_create_task"
CREATE_TABLE = "mcp__portal__portal_create_table"
PREVIEW_CREATE_TABLE = "mcp__portal__portal_preview_create_table"
UPDATE_TABLE_METADATA = "mcp__portal__portal_update_table_metadata"
ANALYZE = "mcp__portal__portal_analyze_sql"
READ = "mcp__portal__portal_search_tables"


def test_classification_by_bare_and_qualified_names() -> None:
    assert pg.is_high_risk_tool(PUBLISH)
    assert pg.is_high_risk_tool("portal_publish_workflow")
    assert pg.is_write_tool(CREATE_TASK)
    assert not pg.is_high_risk_tool(CREATE_TASK)
    assert not pg.is_write_tool(ANALYZE)
    assert not pg.is_write_tool(READ)


def test_bypass_never_confirms() -> None:
    for tool in (PUBLISH, CREATE_TASK, READ):
        assert pg.requires_confirmation(tool, "bypassPermissions") is False


def test_default_confirms_all_writes() -> None:
    assert pg.requires_confirmation(PUBLISH, "default") is True
    assert pg.requires_confirmation(CREATE_TASK, "default") is True
    assert pg.requires_confirmation(ANALYZE, "default") is False
    assert pg.requires_confirmation(READ, "default") is False


def test_accept_edits_confirms_only_high_risk() -> None:
    assert pg.requires_confirmation(PUBLISH, "acceptEdits") is True
    assert pg.requires_confirmation(SCHED_ONLINE, "acceptEdits") is True
    assert pg.requires_confirmation(CREATE_TASK, "acceptEdits") is False


def test_plan_denies_writes_and_never_confirms() -> None:
    # plan resolves write tools to outright denial, not confirmation.
    assert pg.requires_confirmation(PUBLISH, "plan") is False
    assert pg.plan_denies_tool(PUBLISH) is True
    assert pg.plan_denies_tool(CREATE_TASK) is True
    assert pg.plan_denies_tool(READ) is False


def test_plan_denies_builtin_file_write_tools() -> None:
    # Built-in file-mutation tools must be plan-denied, not silently allowed.
    for tool in ("Write", "Edit", "MultiEdit", "NotebookEdit"):
        assert pg.plan_denies_tool(tool) is True
    # Read-only research tools and Bash stay allowed under plan.
    for tool in ("Read", "LS", "Glob", "Grep", "Bash", "Skill"):
        assert pg.plan_denies_tool(tool) is False


def test_legacy_mode_normalizes_to_default() -> None:
    # legacy 'inherit' / unknown -> default policy
    assert pg.requires_confirmation(CREATE_TASK, "inherit") is True
    assert pg.requires_confirmation(CREATE_TASK, "junk") is True


def test_is_exit_plan_mode() -> None:
    assert pg.is_exit_plan_mode("ExitPlanMode") is True
    # bare-name reduction also matches an MCP-qualified form, defensively.
    assert pg.is_exit_plan_mode("mcp__x__ExitPlanMode") is True
    assert pg.is_exit_plan_mode("portal_create_task") is False
    assert pg.is_exit_plan_mode(READ) is False


def test_post_plan_mode_is_accept_edits() -> None:
    # Approving a plan switches the run to acceptEdits: drafts auto-run, high-risk
    # still confirms.
    assert pg.post_plan_mode() == "acceptEdits"
    assert pg.requires_confirmation(CREATE_TASK, pg.post_plan_mode()) is False
    assert pg.requires_confirmation(PUBLISH, pg.post_plan_mode()) is True


def test_update_table_metadata_is_draft_write_not_high_risk() -> None:
    # Reversible metadata completion: draft-level write (confirm under default,
    # auto under acceptEdits, denied under plan), not high-risk like create_table.
    assert pg.is_write_tool(UPDATE_TABLE_METADATA)
    assert pg.is_write_tool("portal_update_table_metadata")
    assert not pg.is_high_risk_tool(UPDATE_TABLE_METADATA)
    assert pg.requires_confirmation(UPDATE_TABLE_METADATA, "default") is True
    assert pg.requires_confirmation(UPDATE_TABLE_METADATA, "acceptEdits") is False
    assert pg.plan_denies_tool(UPDATE_TABLE_METADATA) is True


def test_create_table_is_high_risk_and_preview_is_read_only() -> None:
    # portal_create_table executes irreversible CREATE TABLE DDL -> high-risk write:
    # confirmed in default AND acceptEdits, denied under plan.
    assert pg.is_write_tool(CREATE_TABLE)
    assert pg.is_high_risk_tool(CREATE_TABLE)
    assert pg.requires_confirmation(CREATE_TABLE, "default") is True
    assert pg.requires_confirmation(CREATE_TABLE, "acceptEdits") is True
    assert pg.requires_confirmation(CREATE_TABLE, "bypassPermissions") is False
    assert pg.plan_denies_tool(CREATE_TABLE) is True
    # The preview tool is read-only: not a write tool, never confirmed, allowed under plan.
    assert not pg.is_write_tool(PREVIEW_CREATE_TABLE)
    assert not pg.is_high_risk_tool(PREVIEW_CREATE_TABLE)
    assert pg.requires_confirmation(PREVIEW_CREATE_TABLE, "default") is False
    assert pg.plan_denies_tool(PREVIEW_CREATE_TABLE) is False


def test_strip_card_annotations_drops_only_annotation_keys() -> None:
    raw = {"workflow_id": 7, "operation": "deploy", "preview_token": "tok", "title": "t", "summary": "s"}
    assert pg.strip_card_annotations(raw) == {
        "workflow_id": 7,
        "operation": "deploy",
        "preview_token": "tok",
    }
    # No annotation keys -> unchanged copy.
    payload = {"workflow_id": 1, "operation": "offline"}
    assert pg.strip_card_annotations(payload) == payload
