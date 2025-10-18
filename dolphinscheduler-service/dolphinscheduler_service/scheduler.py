from __future__ import annotations

import json
import logging
import math
from typing import Dict, Iterable, Optional

from pydolphinscheduler.constants import TaskFlag, TaskPriority
from pydolphinscheduler.core.workflow import Workflow
from pydolphinscheduler.tasks.shell import Shell
from pydolphinscheduler.tasks.sql import Sql

from .config import Settings
from .models import (
    EnsureWorkflowRequest,
    EnsureWorkflowResponse,
    QueryProjectRequest,
    QueryProjectResponse,
    ReleaseWorkflowRequest,
    StartWorkflowRequest,
    StartWorkflowResponse,
    SyncWorkflowRequest,
    SyncWorkflowResponse,
    TaskDefinitionPayload,
    TaskRelationPayload,
)

logger = logging.getLogger(__name__)


class DolphinSchedulerServiceCore:
    """Encapsulates calls to apache-dolphinscheduler SDK and auxiliary utilities."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        # Cache of workflow definitions for release operations
        self._workflow_cache: Dict[int, SyncWorkflowRequest] = {}

    def ensure_workflow(self, request: EnsureWorkflowRequest) -> EnsureWorkflowResponse:
        """
        Deprecated: Use sync_workflow directly instead.

        This method just returns a placeholder response.
        The actual workflow creation happens in sync_workflow with tasks.
        """
        logger.warning(
            "ensure_workflow is deprecated. Use sync_workflow directly. "
            "workflow=%s project=%s",
            request.workflow_name,
            request.project_name,
        )
        # Return a placeholder code, actual code will be assigned in sync_workflow
        return EnsureWorkflowResponse(workflowCode=0, created=False)

    def sync_workflow(
        self, workflow_code: int, request: SyncWorkflowRequest
    ) -> SyncWorkflowResponse:
        """
        Create or update workflow with tasks and relations.

        Args:
            workflow_code: Workflow code (0 for new workflow, or existing code for update)
            request: Workflow definition with tasks and relations

        Returns:
            Response with actual workflow code and task count
        """
        workflow = self._build_workflow(
            workflow_name=request.workflow_name,
            project_name=request.project_name,
            description=request.description,
            user=request.user,
            worker_group=request.worker_group,
            execution_type=request.execution_type,
            release_state=request.release_state,
        )

        # Set the workflow code if provided (0 means create new)
        if workflow_code > 0:
            workflow._workflow_code = workflow_code  # type: ignore[attr-defined]
            logger.info(
                "Updating existing workflow: workflow=%s code=%s tasks=%d relations=%d",
                workflow.name,
                workflow_code,
                len(request.tasks),
                len(request.relations),
            )
        else:
            logger.info(
                "Creating new workflow: workflow=%s tasks=%d relations=%d",
                workflow.name,
                len(request.tasks),
                len(request.relations),
            )

        tasks_map: Dict[int, Shell | Sql] = {}
        for payload in request.tasks:
            task = self._build_task(workflow, payload)
            tasks_map[payload.code] = task

        for relation in request.relations:
            self._link_tasks(tasks_map, relation)

        actual_workflow_code = workflow.submit()
        logger.info(
            "Workflow %s submitted successfully with code %s and %d tasks",
            workflow.name,
            actual_workflow_code,
            len(tasks_map),
        )

        # Cache the workflow definition for future release operations
        self._workflow_cache[actual_workflow_code] = request

        return SyncWorkflowResponse(workflowCode=actual_workflow_code, taskCount=len(tasks_map))

    def release_workflow(
        self, workflow_code: int, request: ReleaseWorkflowRequest
    ) -> None:
        """
        Update workflow release state by resubmitting with new release_state.

        Note: pydolphinscheduler doesn't provide a standalone release API.
        We update release state by resubmitting the workflow definition with
        the new release_state, using the cached workflow definition from sync.
        """
        desired_state = request.release_state.upper()
        if desired_state not in {"ONLINE", "OFFLINE"}:
            raise ValueError("releaseState must be ONLINE or OFFLINE")

        # Get cached workflow definition
        if workflow_code not in self._workflow_cache:
            raise ValueError(
                f"Workflow {workflow_code} not found in cache. "
                "Please sync the workflow first before releasing it."
            )

        cached_request = self._workflow_cache[workflow_code]

        logger.info(
            "Updating workflow %s (code=%s) release state to %s",
            cached_request.workflow_name,
            workflow_code,
            desired_state,
        )

        # Resync the workflow with the new release state
        updated_request = SyncWorkflowRequest(
            workflow_name=cached_request.workflow_name,
            project_name=cached_request.project_name,
            description=cached_request.description,
            user=cached_request.user,
            worker_group=cached_request.worker_group,
            execution_type=cached_request.execution_type,
            release_state=desired_state,
            tasks=cached_request.tasks,
            relations=cached_request.relations,
            locations=cached_request.locations,
        )

        # Resubmit with new release state
        self.sync_workflow(workflow_code, updated_request)

    def start_workflow(
        self, workflow_code: int, request: StartWorkflowRequest
    ) -> StartWorkflowResponse:
        """
        Start a workflow execution by workflow code.

        This method queries the workflow info from DolphinScheduler and starts it,
        without needing to rebuild the entire workflow definition from cache.
        """
        from pydolphinscheduler.java_gateway import gateway

        workflow_name = self._ensure_workflow_name(request.workflow_name)
        project_name = request.project_name or self.settings.workflow_project
        user_name = request.user or self.settings.user_name

        # Query workflow info from DolphinScheduler to verify it exists
        try:
            workflow_info = gateway.get_workflow_info(user_name, project_name, workflow_name)
            actual_code = workflow_info.get('code')
            if actual_code != workflow_code:
                logger.warning(
                    "Workflow code mismatch: requested=%s, actual=%s",
                    workflow_code,
                    actual_code
                )
        except Exception as e:
            logger.error(
                "Failed to query workflow info: workflow=%s project=%s user=%s error=%s",
                workflow_name,
                project_name,
                user_name,
                str(e)
            )
            raise ValueError(
                f"Workflow {workflow_name} not found in DolphinScheduler project {project_name}"
            ) from e

        # Create a minimal workflow object just for starting the execution
        # No need to rebuild tasks - DolphinScheduler already has the workflow definition
        workflow = Workflow(
            name=workflow_name,
            project=project_name,
            user=user_name,
            release_state="online",
        )
        workflow._workflow_code = workflow_code  # type: ignore[attr-defined]
        workflow.user.tenant = self.settings.user_tenant

        logger.info(
            "Starting workflow execution: project=%s workflow=%s code=%s user=%s",
            project_name,
            workflow_name,
            workflow_code,
            user_name,
        )
        workflow.start()
        return StartWorkflowResponse(instanceId=None, message="submitted")

    def query_project(self, request: QueryProjectRequest) -> QueryProjectResponse:
        """
        Query project information by name.

        Args:
            request: Query project request with project name and optional user

        Returns:
            Response with project code, name, and description
        """
        from pydolphinscheduler.java_gateway import gateway

        project_name = request.project_name
        user_name = request.user or self.settings.user_name

        logger.info(
            "Querying project: name=%s user=%s",
            project_name,
            user_name,
        )

        try:
            project_info = gateway.query_project_by_name(user_name, project_name)
            # The Java gateway returns a JavaObject - use getattr to access properties
            project_code = int(project_info.getCode())
            description = project_info.getDescription() if hasattr(project_info, 'getDescription') else ''

            logger.info(
                "Project found: name=%s code=%s",
                project_name,
                project_code,
            )

            return QueryProjectResponse(
                projectCode=project_code,
                projectName=project_name,
                description=description
            )
        except Exception as e:
            logger.error(
                "Failed to query project: name=%s user=%s error=%s",
                project_name,
                user_name,
                str(e)
            )
            raise ValueError(
                f"Project {project_name} not found or error querying DolphinScheduler"
            ) from e

    def _build_workflow(
        self,
        workflow_name: str,
        project_name: Optional[str],
        description: Optional[str] = None,
        user: Optional[str] = None,
        worker_group: Optional[str] = None,
        execution_type: Optional[str] = None,
        release_state: Optional[str] = None,
    ) -> Workflow:
        workflow = Workflow(
            name=workflow_name,
            description=description or "",
            user=user or self.settings.user_name,
            project=project_name or self.settings.workflow_project,
            worker_group=worker_group or self.settings.workflow_worker_group,
            execution_type=execution_type or self.settings.workflow_execution_type,
            release_state=(release_state or self.settings.workflow_release_state).lower(),
        )
        workflow.user.tenant = self.settings.user_tenant
        return workflow

    def _ensure_workflow_name(self, candidate: Optional[str]) -> str:
        if candidate and candidate.strip():
            return candidate.strip()
        raise ValueError("workflowName is required for this operation")

    def _build_task(
        self, workflow: Workflow, payload: TaskDefinitionPayload
    ) -> Shell | Sql:
        task_params = self._parse_task_params(payload.task_params)
        timeout_minutes = self._to_minutes(payload.timeout)
        task_priority = payload.task_priority or TaskPriority.MEDIUM
        task_type = payload.task_type.upper() if payload.task_type else "SHELL"

        if task_type == "SQL":
            # Build SQL task
            datasource_name = task_params.get("datasource", "default_datasource")
            sql_statement = task_params.get("sql", "SELECT 1")
            sql_type = task_params.get("sqlType", 0)  # 0=NON_QUERY, 1=QUERY
            # Don't pass datasource_type to avoid type mismatch issues
            # Let DolphinScheduler find datasource by name only
            datasource_type = None

            task = Sql(
                name=payload.name,
                datasource_name=datasource_name,
                sql=sql_statement,
                datasource_type=datasource_type,
                sql_type=sql_type,
                workflow=workflow,
                description=payload.description or "",
                task_priority=task_priority,
                worker_group=payload.worker_group or self.settings.workflow_worker_group,
                fail_retry_times=payload.fail_retry_times,
                fail_retry_interval=payload.fail_retry_interval,
                timeout=timeout_minutes,
            )
        else:
            # Build Shell task (default)
            raw_script = task_params.get("rawScript", "#!/bin/bash\necho 'No script'")
            task = Shell(
                name=payload.name,
                command=raw_script,
                workflow=workflow,
                description=payload.description or "",
                task_priority=task_priority,
                worker_group=payload.worker_group or self.settings.workflow_worker_group,
                fail_retry_times=payload.fail_retry_times,
                fail_retry_interval=payload.fail_retry_interval,
                timeout=timeout_minutes,
            )

        task.code = int(payload.code)
        task.version = int(payload.version)
        task.flag = payload.flag or TaskFlag.YES
        return task

    def _link_tasks(
        self, tasks_map: Dict[int, Shell | Sql], relation: TaskRelationPayload
    ) -> None:
        # Handle root tasks (preTaskCode=0 means no upstream dependency)
        if relation.pre_task_code == 0:
            # Root task - no linkage needed, task will run without dependencies
            return

        upstream = tasks_map.get(relation.pre_task_code)
        downstream = tasks_map.get(relation.post_task_code)
        if upstream is None or downstream is None:
            logger.warning(
                "Skipping relation pre_task=%s post_task=%s due to missing task definitions",
                relation.pre_task_code,
                relation.post_task_code,
            )
            return
        upstream >> downstream

    @staticmethod
    def _parse_task_params(params: object) -> Dict[str, object]:
        if params is None:
            return {}
        if isinstance(params, dict):
            return params
        if isinstance(params, str):
            try:
                return json.loads(params)
            except json.JSONDecodeError:
                logger.warning("Failed to parse task params: %s", params)
                return {}
        return dict(params) if isinstance(params, Iterable) else {}

    @staticmethod
    def _to_minutes(timeout_seconds: Optional[int]) -> int:
        if not timeout_seconds or timeout_seconds <= 0:
            return 0
        return max(1, math.ceil(timeout_seconds / 60))
