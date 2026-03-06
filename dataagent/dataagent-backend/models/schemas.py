"""
Pydantic 数据模型
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class FieldMeta(BaseModel):
    field_name: str
    field_type: str
    field_comment: Optional[str] = None
    is_primary: bool = False
    is_partition: bool = False


class TableMeta(BaseModel):
    table_id: int
    table_name: str
    table_comment: Optional[str] = None
    db_name: Optional[str] = None
    layer: Optional[str] = None
    business_domain: Optional[str] = None
    data_domain: Optional[str] = None
    fields: List[FieldMeta] = Field(default_factory=list)


class SemanticEntry(BaseModel):
    business_name: str
    table_name: Optional[str] = None
    field_name: Optional[str] = None
    synonyms: List[str] = Field(default_factory=list)
    description: Optional[str] = None


class BusinessRule(BaseModel):
    term: str
    synonyms: List[str] = Field(default_factory=list)
    definition: Optional[str] = None


class QAExample(BaseModel):
    question: str
    answer: str
    tags: List[str] = Field(default_factory=list)


class SendMessageRequest(BaseModel):
    content: str
    provider_id: Optional[str] = None
    model: Optional[str] = None
    stream: bool = True
    debug: bool = False
    database: Optional[str] = None


class SettingsUpdateRequest(BaseModel):
    provider_id: Optional[str] = None
    model: Optional[str] = None
    anthropic_api_key: Optional[str] = None
    anthropic_auth_token: Optional[str] = None
    anthropic_base_url: Optional[str] = None
    mysql_host: Optional[str] = None
    mysql_port: Optional[int] = None
    mysql_user: Optional[str] = None
    mysql_password: Optional[str] = None
    mysql_database: Optional[str] = None
    doris_host: Optional[str] = None
    doris_port: Optional[int] = None
    doris_user: Optional[str] = None
    doris_password: Optional[str] = None
    doris_database: Optional[str] = None
    skills_output_dir: Optional[str] = None


class SqlExecutionResult(BaseModel):
    sql: str
    columns: List[str] = Field(default_factory=list)
    rows: List[Dict[str, Any]] = Field(default_factory=list)
    row_count: int = 0
    has_more: bool = False
    duration_ms: int = 0
    error: Optional[str] = None


class MessageBlock(BaseModel):
    block_id: str
    type: str
    status: str = "success"
    text: Optional[str] = None
    tool_name: Optional[str] = None
    tool_id: Optional[str] = None
    input: Any = None
    output: Any = None
    payload: Dict[str, Any] = Field(default_factory=dict)


class SessionMessage(BaseModel):
    message_id: str
    role: str
    content: str = ""
    status: str = "success"
    run_id: Optional[str] = None
    blocks: List[MessageBlock] = Field(default_factory=list)
    sql: str = ""
    execution: Optional[SqlExecutionResult] = None
    error: Optional[Dict[str, Any]] = None
    resolved_database: Optional[str] = None
    provider_id: Optional[str] = None
    model: Optional[str] = None
    created_at: str = ""


class AssistantMessageResponse(BaseModel):
    role: str = "assistant"
    message_id: str
    run_id: str
    status: str = "success"
    content: str
    blocks: List[MessageBlock] = Field(default_factory=list)
    sql: str = ""
    execution: Optional[SqlExecutionResult] = None
    error: Optional[Dict[str, Any]] = None
    resolved_database: Optional[str] = None
    provider_id: str
    model: str
    created_at: str = ""


class ProviderConfig(BaseModel):
    provider_id: str
    display_name: str
    base_url: str = ""
    api_key_set: bool = False
    auth_token_set: bool = False
    models: List[str] = Field(default_factory=list)
    default_model: str = ""


class SettingsResponse(BaseModel):
    default_provider_id: str
    default_model: str
    providers: List[ProviderConfig] = Field(default_factory=list)
    skills_output_dir: str = ""
    mysql_host: str = ""
    mysql_port: int = 3306
    mysql_database: str = ""
    doris_host: str = ""
    doris_port: int = 9030
    doris_database: str = ""


class SessionSummary(BaseModel):
    session_id: str
    title: str
    message_count: int = 0
    last_message_preview: str = ""
    created_at: str = ""
    updated_at: str = ""


class SessionDetail(BaseModel):
    session_id: str
    title: str
    messages: List[SessionMessage] = Field(default_factory=list)
    created_at: str = ""
    updated_at: str = ""


class StreamEvent(BaseModel):
    run_id: str
    session_id: str
    message_id: str
    seq: int
    type: str
    ts: str
    payload: Dict[str, Any] = Field(default_factory=dict)


TableMeta.model_rebuild()
