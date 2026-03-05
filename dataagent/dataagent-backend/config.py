"""
DataAgent Backend 配置管理
支持环境变量和运行时动态更新（如前端 Settings 页面设置 API Key）
"""
import os
import threading
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """应用配置，优先从环境变量读取"""

    # ---- 服务 ----
    app_name: str = "dataagent-backend"
    host: str = "0.0.0.0"
    port: int = 8900
    debug: bool = False

    # ---- LLM (Anthropic 兼容) ----
    anthropic_api_key: str = ""
    anthropic_base_url: str = ""
    claude_model: str = "glm-4"
    claude_max_tokens: int = 4096

    # ---- MySQL (元数据库) ----
    mysql_host: str = "localhost"
    mysql_port: int = 3306
    mysql_user: str = "root"
    mysql_password: str = ""
    mysql_database: str = "opendataworks"
    knowledge_mysql_database: str = "dataagent"
    session_mysql_database: str = "dataagent"

    # ---- Doris (业务数据查询) ----
    doris_host: str = "localhost"
    doris_port: int = 9030
    doris_user: str = "root"
    doris_password: str = ""
    doris_database: str = ""

    # ---- Tool Runtime / MCP ----
    tool_runtime_mode: str = "native"  # native | mcp_http
    mcp_http_endpoint: str = ""
    mcp_http_timeout_seconds: int = 20

    # ---- Skills 同步 ----
    skills_output_dir: str = "../skills/dataagent"

    # ---- 语义层 ----
    knowledge_version_tag: str = "latest"
    meta_snapshot_version: str = "latest"
    max_few_shot_examples: int = 5
    max_schema_tables: int = 10
    max_business_rules: int = 5
    query_result_limit: int = 100

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"


# 全局单例
_settings = Settings()
_lock = threading.Lock()


def get_settings() -> Settings:
    return _settings


def update_settings(patch: dict) -> Settings:
    """运行时更新配置（前端 Settings 页面调用）"""
    global _settings
    with _lock:
        current = _settings.model_dump()
        current.update({k: v for k, v in patch.items() if v is not None})
        _settings = Settings(**current)
    return _settings
