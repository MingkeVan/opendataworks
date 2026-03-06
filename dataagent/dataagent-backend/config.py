"""
DataAgent Backend 配置管理
支持环境变量和运行时动态更新
"""
from __future__ import annotations

import threading

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # ---- 服务 ----
    app_name: str = "dataagent-backend"
    host: str = "0.0.0.0"
    port: int = 8900
    debug: bool = False

    # ---- LLM Provider / Model ----
    llm_provider: str = "openrouter"  # anthropic | openrouter | anyrouter | anthropic_compatible
    claude_model: str = "anthropic/claude-sonnet-4.5"
    claude_max_tokens: int = 4096
    agent_timeout_seconds: int = 180
    agent_max_turns: int = 12

    # ---- Anthropic 兼容认证 ----
    anthropic_api_key: str = ""
    anthropic_auth_token: str = ""
    anthropic_base_url: str = ""

    # ---- MySQL（会话存储 + MySQL 查询工具）----
    mysql_host: str = "localhost"
    mysql_port: int = 3306
    mysql_user: str = "root"
    mysql_password: str = ""
    mysql_database: str = "opendataworks"
    session_mysql_database: str = "dataagent"

    # ---- Doris（查询工具）----
    doris_host: str = "localhost"
    doris_port: int = 9030
    doris_user: str = "root"
    doris_password: str = ""
    doris_database: str = ""

    # ---- Skills ----
    skills_output_dir: str = "../.claude/skills/dataagent-nl2sql"

    # ---- 运行策略 ----
    max_few_shot_examples: int = 5
    max_schema_tables: int = 10
    max_business_rules: int = 5
    query_result_limit: int = 100

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"
        extra = "ignore"


_settings = Settings()
_lock = threading.Lock()


def get_settings() -> Settings:
    return _settings


def update_settings(patch: dict) -> Settings:
    """运行时更新配置"""
    global _settings
    with _lock:
        current = _settings.model_dump()
        current.update({k: v for k, v in patch.items() if v is not None})
        _settings = Settings(**current)
    return _settings
