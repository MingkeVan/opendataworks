"""
Pydantic 数据模型 — 请求/响应 + 内部对象
"""
from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, Field


# ---------- 枚举 ----------

class StepStatus(str, Enum):
    pending = "pending"
    running = "running"
    success = "success"
    failed = "failed"
    skipped = "skipped"


# ---------- 内部模型 ----------

class TableMeta(BaseModel):
    """从 data_table + data_field 构建的表元数据"""
    table_id: int
    table_name: str
    table_comment: str | None = None
    db_name: str | None = None
    layer: str | None = None
    business_domain: str | None = None
    data_domain: str | None = None
    fields: list[FieldMeta] = Field(default_factory=list)


class FieldMeta(BaseModel):
    field_name: str
    field_type: str
    field_comment: str | None = None
    is_primary: bool = False
    is_partition: bool = False


class SemanticEntry(BaseModel):
    """语义知识条目"""
    business_name: str
    table_name: str | None = None
    field_name: str | None = None
    synonyms: list[str] = Field(default_factory=list)
    description: str | None = None


class BusinessRule(BaseModel):
    """业务术语 / 计算口径"""
    term: str
    synonyms: list[str] = Field(default_factory=list)
    definition: str | None = None


class QAExample(BaseModel):
    """Prompt-SQL 示例对"""
    question: str
    answer: str  # SQL
    tags: list[str] = Field(default_factory=list)


class ThinkingStep(BaseModel):
    """思考过程中的一个步骤"""
    step_key: str
    step_name: str
    status: StepStatus = StepStatus.pending
    summary: str | None = None
    detail: Any = None
    timestamp: datetime = Field(default_factory=datetime.now)


# ---------- 请求模型 ----------

class NL2SqlRequest(BaseModel):
    """自然语言转 SQL 请求"""
    session_id: str | None = None
    question: str
    database: str | None = None
    cluster_id: int | None = None
    model: str | None = None
    history: list[dict[str, str]] = Field(
        default_factory=list,
        description="多轮对话历史 [{role, content}]"
    )


class ExecuteSqlRequest(BaseModel):
    """执行 SQL 请求"""
    sql: str
    database: str | None = None
    cluster_id: int | None = None
    limit: int = 100


class SettingsUpdateRequest(BaseModel):
    """前端 Settings 页面更新配置"""
    anthropic_api_key: str | None = None
    claude_model: str | None = None
    mysql_host: str | None = None
    mysql_port: int | None = None
    mysql_user: str | None = None
    mysql_password: str | None = None
    mysql_database: str | None = None
    knowledge_mysql_database: str | None = None
    session_mysql_database: str | None = None
    doris_host: str | None = None
    doris_port: int | None = None
    doris_user: str | None = None
    doris_password: str | None = None
    doris_database: str | None = None
    tool_runtime_mode: str | None = None
    mcp_http_endpoint: str | None = None
    mcp_http_timeout_seconds: int | None = None
    skills_output_dir: str | None = None


# ---------- 响应模型 ----------

class NL2SqlResponse(BaseModel):
    """NL2SQL 生成结果"""
    session_id: str | None = None
    question: str
    sql: str
    explanation: str | None = None
    thinking_steps: list[ThinkingStep] = Field(default_factory=list)
    matched_tables: list[str] = Field(default_factory=list)
    matched_rules: list[str] = Field(default_factory=list)
    confidence: float | None = None


class SqlExecutionResult(BaseModel):
    """SQL 执行结果"""
    sql: str
    columns: list[str] = Field(default_factory=list)
    rows: list[dict[str, Any]] = Field(default_factory=list)
    row_count: int = 0
    has_more: bool = False
    duration_ms: int = 0
    error: str | None = None


class SettingsResponse(BaseModel):
    """配置响应（隐去密码）"""
    anthropic_api_key_set: bool = False
    claude_model: str = ""
    mysql_host: str = ""
    mysql_port: int = 3306
    mysql_database: str = ""
    knowledge_mysql_database: str = ""
    session_mysql_database: str = ""
    doris_host: str = ""
    doris_port: int = 9030
    doris_database: str = ""
    tool_runtime_mode: str = "native"
    mcp_http_endpoint: str = ""
    mcp_http_timeout_seconds: int = 20
    skills_output_dir: str = ""


class ToolInvokeRequest(BaseModel):
    """工具调用请求（Tool Use / MCP 调试入口）"""
    tool_name: str
    payload: dict[str, Any] = Field(default_factory=dict)


# 解决前向引用
TableMeta.model_rebuild()
