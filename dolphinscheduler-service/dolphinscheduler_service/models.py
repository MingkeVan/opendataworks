from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, ConfigDict, Field


class ApiResponse(BaseModel):
    success: bool = True
    code: str = "OK"
    message: str = "ok"
    data: Dict[str, Any] = Field(default_factory=dict)

    model_config = ConfigDict(populate_by_name=True)

    @staticmethod
    def ok(
        data: Optional[Dict[str, Any]] = None, message: str = "ok"
    ) -> "ApiResponse":
        return ApiResponse(success=True, code="OK", message=message, data=data or {})

    @staticmethod
    def fail(code: str, message: str) -> "ApiResponse":
        return ApiResponse(success=False, code=code, message=message, data={})


class EnsureWorkflowRequest(BaseModel):
    workflow_name: str = Field(alias="workflowName")
    project_name: Optional[str] = Field(default=None, alias="projectName")
    tenant_code: Optional[str] = Field(default=None, alias="tenantCode")
    user: Optional[str] = None
    description: Optional[str] = None
    execution_type: Optional[str] = Field(default=None, alias="executionType")
    worker_group: Optional[str] = Field(default=None, alias="workerGroup")

    model_config = ConfigDict(populate_by_name=True)


class EnsureWorkflowResponse(BaseModel):
    workflow_code: int = Field(alias="workflowCode")
    created: bool

    model_config = ConfigDict(populate_by_name=True)


class TaskDefinitionPayload(BaseModel):
    code: int
    name: str
    version: int = 1
    description: Optional[str] = ""

    task_type: str = Field(default="SHELL", alias="taskType")
    task_params: Any = Field(alias="taskParams")
    task_priority: str = Field(default="MEDIUM", alias="taskPriority")
    flag: str = "YES"
    worker_group: Optional[str] = Field(default=None, alias="workerGroup")
    environment_code: Optional[int] = Field(
        default=None, alias="environmentCode"
    )
    fail_retry_times: int = Field(default=0, alias="failRetryTimes")
    fail_retry_interval: int = Field(default=1, alias="failRetryInterval")
    timeout: int = 0
    timeout_flag: Optional[str] = Field(default=None, alias="timeoutFlag")
    timeout_notify_strategy: Optional[str] = Field(
        default=None, alias="timeoutNotifyStrategy"
    )

    model_config = ConfigDict(populate_by_name=True, extra="allow")


class TaskRelationPayload(BaseModel):
    pre_task_code: int = Field(alias="preTaskCode")
    post_task_code: int = Field(alias="postTaskCode")

    model_config = ConfigDict(populate_by_name=True, extra="allow")


class TaskLocationPayload(BaseModel):
    task_code: int = Field(alias="taskCode")
    x: int
    y: int

    model_config = ConfigDict(populate_by_name=True, extra="allow")


class SyncWorkflowRequest(BaseModel):
    workflow_name: str = Field(alias="workflowName")
    project_name: Optional[str] = Field(default=None, alias="projectName")
    tenant_code: Optional[str] = Field(default=None, alias="tenantCode")
    user: Optional[str] = None
    description: Optional[str] = None
    execution_type: Optional[str] = Field(default=None, alias="executionType")
    worker_group: Optional[str] = Field(default=None, alias="workerGroup")
    release_state: Optional[str] = Field(default=None, alias="releaseState")
    tasks: List[TaskDefinitionPayload] = Field(default_factory=list)
    relations: List[TaskRelationPayload] = Field(default_factory=list)
    locations: List[TaskLocationPayload] = Field(default_factory=list)

    model_config = ConfigDict(populate_by_name=True)


class SyncWorkflowResponse(BaseModel):
    workflow_code: int = Field(alias="workflowCode")
    task_count: int = Field(alias="taskCount")

    model_config = ConfigDict(populate_by_name=True)


class ReleaseWorkflowRequest(BaseModel):
    project_name: Optional[str] = Field(default=None, alias="projectName")
    workflow_name: Optional[str] = Field(default=None, alias="workflowName")
    release_state: str = Field(alias="releaseState")

    model_config = ConfigDict(populate_by_name=True)


class StartWorkflowRequest(BaseModel):
    project_name: Optional[str] = Field(default=None, alias="projectName")
    workflow_name: Optional[str] = Field(default=None, alias="workflowName")
    user: Optional[str] = None
    worker_group: Optional[str] = Field(default=None, alias="workerGroup")

    model_config = ConfigDict(populate_by_name=True)


class StartWorkflowResponse(BaseModel):
    instance_id: Optional[str] = Field(default=None, alias="instanceId")
    message: str = ""

    model_config = ConfigDict(populate_by_name=True)


class QueryProjectRequest(BaseModel):
    project_name: str = Field(alias="projectName")
    user: Optional[str] = None

    model_config = ConfigDict(populate_by_name=True)


class QueryProjectResponse(BaseModel):
    project_code: int = Field(alias="projectCode")
    project_name: str = Field(alias="projectName")
    description: Optional[str] = None

    model_config = ConfigDict(populate_by_name=True)


class GetInstanceRequest(BaseModel):
    project_name: str = Field(alias="projectName")
    workflow_name: Optional[str] = Field(default=None, alias="workflowName")
    instance_id: Optional[int] = Field(default=None, alias="instanceId")
    user: Optional[str] = None

    model_config = ConfigDict(populate_by_name=True)


class GetInstanceResponse(BaseModel):
    instance_id: int = Field(alias="instanceId")
    workflow_code: int = Field(alias="workflowCode")
    workflow_name: str = Field(alias="workflowName")
    state: str
    start_time: Optional[str] = Field(default=None, alias="startTime")
    end_time: Optional[str] = Field(default=None, alias="endTime")
    duration: Optional[int] = None
    run_times: int = Field(default=0, alias="runTimes")
    host: Optional[str] = None
    command_type: Optional[str] = Field(default=None, alias="commandType")

    model_config = ConfigDict(populate_by_name=True)


class GetInstanceLogRequest(BaseModel):
    project_name: str = Field(alias="projectName")
    workflow_name: Optional[str] = Field(default=None, alias="workflowName")
    instance_id: int = Field(alias="instanceId")
    task_name: Optional[str] = Field(default=None, alias="taskName")
    user: Optional[str] = None

    model_config = ConfigDict(populate_by_name=True)


class GetInstanceLogResponse(BaseModel):
    log_content: str = Field(alias="logContent")
    task_instance_id: Optional[int] = Field(default=None, alias="taskInstanceId")

    model_config = ConfigDict(populate_by_name=True)


class ListInstancesRequest(BaseModel):
    project_name: str = Field(alias="projectName")
    workflow_name: Optional[str] = Field(default=None, alias="workflowName")
    user: Optional[str] = None
    page_num: int = Field(default=1, alias="pageNum")
    page_size: int = Field(default=10, alias="pageSize")
    start_date: Optional[str] = Field(default=None, alias="startDate")
    end_date: Optional[str] = Field(default=None, alias="endDate")
    state: Optional[str] = None

    model_config = ConfigDict(populate_by_name=True)


class ListInstancesResponse(BaseModel):
    total: int
    page_num: int = Field(alias="pageNum")
    page_size: int = Field(alias="pageSize")
    instances: List[Dict[str, Any]]

    model_config = ConfigDict(populate_by_name=True)


class DatasourceItem(BaseModel):
    id: Optional[int] = None
    name: str
    type: Optional[str] = None
    db_name: Optional[str] = Field(default=None, alias="dbName")
    description: Optional[str] = None

    model_config = ConfigDict(populate_by_name=True, extra="allow")


class ListDatasourcesResponse(BaseModel):
    datasources: List[DatasourceItem] = Field(default_factory=list)

    model_config = ConfigDict(populate_by_name=True)
