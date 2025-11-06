import logging
import os
from functools import lru_cache

from dotenv import load_dotenv
from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import Optional


load_dotenv()


class ServiceMeta(BaseModel):
    name: str = "dolphinscheduler-service"
    version: str = "0.1.0"
    environment: str = Field(default="local", alias="DS_ENV")


class Settings(BaseSettings):
    """Application settings loaded from environment variables or `.env` files."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="",
        case_sensitive=False,
        extra="ignore",
    )

    meta: ServiceMeta = Field(default_factory=ServiceMeta)
    service_host: str = Field(default="0.0.0.0", alias="DS_SERVICE_HOST")
    service_port: int = Field(default=8000, alias="DS_SERVICE_PORT")

    api_base_url: str = Field(
        default="http://localhost:12345/dolphinscheduler", alias="DS_API_BASE_URL"
    )

    java_gateway_host: str = Field(
        default="127.0.0.1", alias="PYDS_JAVA_GATEWAY_ADDRESS"
    )
    java_gateway_port: int = Field(default=25333, alias="PYDS_JAVA_GATEWAY_PORT")
    java_gateway_auth_token: Optional[str] = Field(
        default=None, alias="PYDS_JAVA_GATEWAY_AUTH_TOKEN"
    )

    user_name: str = Field(default="admin", alias="PYDS_USER_NAME")
    user_password: str = Field(
        default="dolphinscheduler123", alias="PYDS_USER_PASSWORD"
    )
    user_email: str = Field(
        default="pyds-service@example.com", alias="PYDS_USER_EMAIL"
    )
    user_phone: str = Field(default="13000000000", alias="PYDS_USER_PHONE")
    user_tenant: str = Field(default="default", alias="PYDS_USER_TENANT")
    user_queue: str = Field(default="queuePythonGateway", alias="PYDS_WORKFLOW_QUEUE")

    workflow_project: str = Field(default="opendataworks", alias="PYDS_WORKFLOW_PROJECT")
    workflow_name: Optional[str] = Field(default=None, alias="PYDS_WORKFLOW_NAME")
    workflow_worker_group: str = Field(
        default="default", alias="PYDS_WORKFLOW_WORKER_GROUP"
    )
    workflow_warning_type: str = Field(
        default="NONE", alias="PYDS_WORKFLOW_WARNING_TYPE"
    )
    workflow_execution_type: str = Field(
        default="PARALLEL", alias="PYDS_WORKFLOW_EXECUTION_TYPE"
    )
    workflow_release_state: str = Field(
        default="offline", alias="PYDS_WORKFLOW_RELEASE_STATE"
    )

    default_timeout_seconds: int = Field(default=0, alias="DS_DEFAULT_TIMEOUT_SECONDS")
    default_retry_times: int = Field(default=0, alias="DS_DEFAULT_RETRY_TIMES")
    default_retry_interval: int = Field(
        default=1, alias="DS_DEFAULT_RETRY_INTERVAL"
    )

    log_level: str = Field(default="INFO", alias="LOG_LEVEL")

    def configure_logging(self) -> None:
        logging.basicConfig(
            level=self.log_level.upper(),
            format="%(asctime)s %(levelname)s %(name)s %(message)s",
        )

    def configure_sdk_environment(self) -> None:
        """Apply environment variables and runtime configuration for `apache-dolphinscheduler`."""
        os.environ["PYDS_JAVA_GATEWAY_ADDRESS"] = self.java_gateway_host
        os.environ["PYDS_JAVA_GATEWAY_PORT"] = str(self.java_gateway_port)
        if self.java_gateway_auth_token:
            os.environ["PYDS_JAVA_GATEWAY_AUTH_TOKEN"] = self.java_gateway_auth_token

        os.environ["PYDS_USER_NAME"] = self.user_name
        os.environ["PYDS_USER_PASSWORD"] = self.user_password
        os.environ["PYDS_USER_EMAIL"] = self.user_email
        os.environ["PYDS_USER_PHONE"] = self.user_phone
        os.environ["PYDS_USER_TENANT"] = self.user_tenant

        os.environ["PYDS_WORKFLOW_PROJECT"] = self.workflow_project
        os.environ["PYDS_WORKFLOW_USER"] = self.user_name
        os.environ["PYDS_WORKFLOW_QUEUE"] = self.user_queue
        os.environ["PYDS_WORKFLOW_WORKER_GROUP"] = self.workflow_worker_group
        os.environ["PYDS_WORKFLOW_WARNING_TYPE"] = self.workflow_warning_type
        os.environ["PYDS_WORKFLOW_EXECUTION_TYPE"] = self.workflow_execution_type
        os.environ["PYDS_WORKFLOW_RELEASE_STATE"] = self.workflow_release_state.lower()

        from pydolphinscheduler import configuration

        configuration.JAVA_GATEWAY_ADDRESS = self.java_gateway_host
        configuration.JAVA_GATEWAY_PORT = self.java_gateway_port
        if self.java_gateway_auth_token:
            configuration.JAVA_GATEWAY_AUTH_TOKEN = self.java_gateway_auth_token

        configuration.USER_NAME = self.user_name
        configuration.USER_PASSWORD = self.user_password
        configuration.USER_EMAIL = self.user_email
        configuration.USER_PHONE = self.user_phone
        configuration.USER_TENANT = self.user_tenant

        configuration.WORKFLOW_PROJECT = self.workflow_project
        configuration.WORKFLOW_USER = self.user_name
        configuration.WORKFLOW_QUEUE = self.user_queue
        configuration.WORKFLOW_WORKER_GROUP = self.workflow_worker_group
        configuration.WORKFLOW_WARNING_TYPE = self.workflow_warning_type
        configuration.WORKFLOW_EXECUTION_TYPE = self.workflow_execution_type
        configuration.WORKFLOW_RELEASE_STATE = self.workflow_release_state.lower()


@lru_cache
def get_settings() -> Settings:
    settings = Settings()
    settings.configure_logging()
    settings.configure_sdk_environment()
    return settings
