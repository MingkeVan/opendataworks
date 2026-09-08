from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Dict, List, Literal, Optional
from pydantic import BaseModel, Field


class AgentEventType(str, Enum):
    RUN_STARTED = "run.started"
    TURN_STARTED = "turn.started"
    CONTENT_STARTED = "content.started"
    CONTENT_DELTA = "content.delta"
    CONTENT_COMPLETED = "content.completed"
    TOOL_STARTED = "tool.started"
    TOOL_PROGRESS = "tool.progress"
    TOOL_COMPLETED = "tool.completed"
    INTERACTION_REQUESTED = "interaction.requested"
    INTERACTION_RESOLVED = "interaction.resolved"
    USAGE_UPDATED = "usage.updated"
    TURN_COMPLETED = "turn.completed"
    RUN_COMPLETED = "run.completed"
    RUN_FAILED = "run.failed"
    RUN_CANCELLED = "run.cancelled"
    RUN_SUSPENDED = "run.suspended"


class AgentEvent(BaseModel):
    event_id: str
    run_id: str
    task_id: str
    task_attempt_id: str
    sequence: int = Field(..., ge=1)
    timestamp: str = Field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    type: AgentEventType
    payload: Dict[str, Any] = Field(default_factory=dict)


class ConversationMessage(BaseModel):
    message_id: Optional[str] = None
    role: Literal["user", "assistant", "system", "custom"]
    content: str
    attachments: List[Dict[str, Any]] = Field(default_factory=list)
    created_at: Optional[str] = None


class ContextBundle(BaseModel):
    context_snapshot_id: str
    history_watermark: Optional[str] = None
    policy_version: str = "v1"
    renderer_target: Literal["pi_agent_core"] = "pi_agent_core"
    system_instructions: str
    messages: List[ConversationMessage]
    attachments: List[Dict[str, Any]] = Field(default_factory=list)
    artifacts: List[Dict[str, Any]] = Field(default_factory=list)
    enabled_skills: List[str] = Field(default_factory=list)
    tool_catalog_digest: Optional[str] = None
    data_scope: Dict[str, Any] = Field(default_factory=dict)
    locale: str = "zh-CN"
    timezone: str = "Asia/Shanghai"
    content_digest: Optional[str] = None


class ModelTarget(BaseModel):
    provider_id: str
    model_id: str
    endpoint_ref: Optional[str] = None
    region: Optional[str] = None
    options: Dict[str, Any] = Field(default_factory=dict)


class WorkspaceSpec(BaseModel):
    workspace_root: str
    scratch_root: Optional[str] = None
    read_only: bool = False


class SkillSpec(BaseModel):
    name: str
    root_path: str


class McpServerSpec(BaseModel):
    name: str
    transport: Literal["stdio", "streamable_http"]
    endpoint: Optional[str] = None
    token: Optional[str] = None
    tool_allowlist: List[str] = Field(default_factory=list)


class ExecutionPolicySnapshot(BaseModel):
    policy_version: str = "v1"
    require_plan_approval: bool = False
    require_write_confirmation: bool = True
    allowed_tools: Optional[List[str]] = None


class RunLimits(BaseModel):
    timeout_seconds: int = 360
    idle_timeout_seconds: int = 120
    max_turns: int = 30
    max_tool_calls: int = 50
    max_output_tokens: int = 4096


class SecretEnvelope(BaseModel):
    api_key: Optional[str] = None
    mcp_tokens: Dict[str, str] = Field(default_factory=dict)
    custom_headers: Dict[str, str] = Field(default_factory=dict)


class AgentRunRequest(BaseModel):
    runtime_protocol_version: Literal[1] = 1
    agent_event_protocol_version: Literal[1] = 1
    run_id: str
    task_id: str
    task_attempt_id: str
    topic_id: str
    purpose: Literal["interactive", "background", "eval"] = "interactive"
    context: ContextBundle
    model: ModelTarget
    workspace: WorkspaceSpec
    skills: List[SkillSpec] = Field(default_factory=list)
    mcp_servers: List[McpServerSpec] = Field(default_factory=list)
    policy: ExecutionPolicySnapshot = Field(default_factory=ExecutionPolicySnapshot)
    limits: RunLimits = Field(default_factory=RunLimits)
    secret_envelope: SecretEnvelope = Field(default_factory=SecretEnvelope)


class RuntimeFeatures(BaseModel):
    streaming: bool = True
    reasoning: bool = True
    tools: bool = True
    tool_progress: bool = True
    permission_interaction: bool = True
    question_interaction: bool = True
    plan_interaction: bool = True
    cancel: bool = True
    steer: bool = True
    follow_up: bool = True
    mcp: bool = True
    skills: bool = True


class RuntimeManifest(BaseModel):
    runtime_kind: Literal["pi_agent_core"] = "pi_agent_core"
    runtime_version: str
    pi_agent_core_version: str
    pi_ai_version: str
    node_version: str
    runtime_protocol_versions: List[int] = Field(default_factory=lambda: [1])
    agent_event_protocol_versions: List[int] = Field(default_factory=lambda: [1])
    context_renderer_versions: List[int] = Field(default_factory=lambda: [1])
    providers: List[str]
    features: RuntimeFeatures = Field(default_factory=RuntimeFeatures)
    limits: Dict[str, Any] = Field(default_factory=dict)
    artifact_digest: str


class CellProtocolFrame(BaseModel):
    protocol_version: Literal[1] = 1
    cell_id: str
    run_id: str
    task_attempt_id: str
    frame_id: str
    type: str
    payload: Dict[str, Any] = Field(default_factory=dict)


class AgentInteraction(BaseModel):
    interaction_id: str
    run_id: str
    task_id: str
    tool_call_id: Optional[str] = None
    canonical_tool_id: Optional[str] = None
    kind: Literal["permission", "question", "plan"]
    status: Literal["pending", "resolved", "expired", "cancelled"] = "pending"
    request: Dict[str, Any]
    response: Optional[Dict[str, Any]] = None
    created_at: str = Field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    updated_at: Optional[str] = None


class CanonicalToolDefinition(BaseModel):
    canonical_id: str
    initial_alias: str
    description: str
    parameters_schema: Optional[Dict[str, Any]] = None
    side_effect: Literal["none", "read", "write", "admin"] = "none"
    parallel_safe: bool = False
    execution_mode: Literal["sequential", "parallel"] = "sequential"
