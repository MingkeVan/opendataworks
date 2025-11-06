from __future__ import annotations

import json
import logging
import math
import time
from datetime import datetime
from typing import Dict, Iterable, Optional, List, Union, Any

import requests

from pydolphinscheduler.constants import TaskFlag, TaskPriority
from pydolphinscheduler.core.workflow import Workflow
from pydolphinscheduler.tasks.shell import Shell
from pydolphinscheduler.tasks.sql import Sql

from .config import Settings
from .models import (
    EnsureWorkflowRequest,
    EnsureWorkflowResponse,
    GetInstanceLogRequest,
    GetInstanceLogResponse,
    GetInstanceRequest,
    GetInstanceResponse,
    ListInstancesRequest,
    ListInstancesResponse,
    QueryProjectRequest,
    QueryProjectResponse,
    ReleaseWorkflowRequest,
    StartWorkflowRequest,
    StartWorkflowResponse,
    SyncWorkflowRequest,
    SyncWorkflowResponse,
    DatasourceItem,
    ListDatasourcesResponse,
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

    def _get_project_code(self, project_name: Optional[str] = None) -> Optional[int]:
        """
        Resolve DolphinScheduler project code by project name using the Java gateway.
        """
        from pydolphinscheduler.java_gateway import gateway

        target_project = project_name or self.settings.workflow_project
        user_name = self.settings.user_name

        try:
            project_info = gateway.query_project_by_name(user_name, target_project)
            if project_info is None:
                logger.error(
                    "DolphinScheduler project not found via gateway: user=%s project=%s",
                    user_name,
                    target_project,
                )
                return None
            project_code = int(project_info.getCode())
            return project_code
        except Exception as exc:  # pylint: disable=broad-except
            logger.error(
                "Failed to query project code: user=%s project=%s error=%s",
                user_name,
                target_project,
                exc,
            )
            return None

    def _login_and_get_token(self) -> Optional[str]:
        """
        Authenticate against DolphinScheduler REST API and obtain session token.
        """
        api_base = self.settings.api_base_url.rstrip("/")
        login_url = f"{api_base}/login"
        payload = {
            "userName": self.settings.user_name,
            "userPassword": self.settings.user_password,
        }

        try:
            response = requests.post(login_url, data=payload, timeout=10)
            response.raise_for_status()
            data = response.json()
        except Exception as exc:  # pylint: disable=broad-except
            logger.error("Failed to authenticate with DolphinScheduler API: %s", exc)
            return None

        if not data.get("success"):
            logger.error(
                "DolphinScheduler API login failed: %s",
                data.get("msg") or data.get("message"),
            )
            return None

        token = (data.get("data") or {}).get("sessionId")
        if not token:
            logger.error("DolphinScheduler API login did not return sessionId")
            return None
        return token

    def _perform_api_get(
        self,
        url: str,
        params: Optional[Dict[str, object]],
        token: str,
        timeout: int = 2,
    ) -> Optional[dict]:
        query_params = dict(params or {})
        query_params.setdefault("sessionId", token)
        try:
            response = requests.get(
                url,
                params=query_params,
                cookies={"sessionId": token},
                headers={"sessionId": token},
                timeout=timeout,
            )
            if response.status_code == 404:
                return None
            response.raise_for_status()
            payload = response.json()
        except Exception as exc:  # pylint: disable=broad-except
            logger.debug("GET %s failed: %s", url, exc)
            return None

        if not self._is_api_success(payload):
            logger.debug(
                "DolphinScheduler API reported failure for %s: code=%s msg=%s",
                url,
                payload.get("code"),
                payload.get("msg") or payload.get("message"),
            )
            return None

        return payload

    @staticmethod
    def _is_api_success(payload: Optional[dict]) -> bool:
        if not isinstance(payload, dict):
            return False
        success_flag = payload.get("success")
        if success_flag is False:
            return False
        code = payload.get("code")
        if code not in (None, 0):
            return False
        return True

    def _fetch_latest_instance_id(
        self,
        project_name: str,
        workflow_code: int,
        workflow_name: str,
        max_attempts: int = 2,
        poll_interval: float = 1.0,
        min_instance_id: Optional[int] = None,
    ) -> Optional[str]:
        """
        Poll DolphinScheduler REST API for the newest workflow instance ID.
        """
        project_code = self._get_project_code(project_name)
        if project_code is None:
            logger.warning(
                "Cannot resolve project code for project=%s when querying instances",
                project_name,
            )
            return None

        token = self._login_and_get_token()
        if not token:
            logger.warning("Cannot obtain DolphinScheduler session token to list instances")
            return None

        api_base = self.settings.api_base_url.rstrip("/")
        list_endpoints = [
            f"{api_base}/projects/{project_code}/workflow-instances",
            f"{api_base}/projects/{project_code}/process-instances",
        ]

        params = {
            "pageNo": 1,
            "pageSize": 10,
            # Support both legacy(process) and new (workflow) parameter names
            "workflowDefinitionCode": workflow_code,
            "processDefinitionCode": workflow_code,
        }
        if workflow_name:
            params["searchVal"] = workflow_name

        for attempt in range(1, max_attempts + 1):
            for list_url in list_endpoints:
                payload = self._perform_api_get(list_url, params, token)
                if payload is None:
                    continue

                data = payload.get("data") or {}
                raw_instances = self._extract_instance_payload(data)

                instance_id = self._extract_instance_id(
                    raw_instances,
                    workflow_code,
                    workflow_name,
                    min_instance_id=min_instance_id,
                )
                if instance_id:
                    return instance_id

            if attempt < max_attempts:
                time.sleep(poll_interval)

        logger.warning(
            "Unable to find newly created instance for workflow %s after %s attempts",
            workflow_code,
            max_attempts,
        )
        return None

    @staticmethod
    def _extract_instance_payload(data: object) -> List[dict]:
        if isinstance(data, list):
            return [row for row in data if isinstance(row, dict)]

        if isinstance(data, dict):
            raw_instances = (
                data.get("totalList")
                or data.get("records")
                or data.get("processInstanceList")
                or data.get("list")
                or data.get("items")
                or []
            )
            if isinstance(raw_instances, list):
                return [row for row in raw_instances if isinstance(row, dict)]

        return []

    def _fetch_instance_detail(
        self,
        project_code: int,
        instance_id: int,
        token: str,
    ) -> Optional[dict]:
        api_base = self.settings.api_base_url.rstrip("/")
        endpoints = [
            f"{api_base}/projects/{project_code}/workflow-instances/{instance_id}",
            f"{api_base}/projects/{project_code}/process-instances/{instance_id}",
        ]

        for url in endpoints:
            payload = self._perform_api_get(url, params=None, token=token)
            if payload is None:
                continue

            data = payload.get("data")
            if isinstance(data, dict) and data:
                return data
            if isinstance(data, list) and data:
                first = data[0]
                if isinstance(first, dict):
                    return first

        return None

    @staticmethod
    def _extract_instance_id(
        instances: Iterable[dict],
        workflow_code: int,
        workflow_name: str,
        min_instance_id: Optional[int] = None,
    ) -> Optional[str]:
        """
        Find the latest instance in the list that belongs to the workflow and return its ID.
        """
        latest_identifier: Optional[str] = None
        latest_numeric: Optional[int] = None
        latest_start: Optional[str] = None

        for row in instances:
            if not isinstance(row, dict):
                continue

            code = (
                row.get("processDefinitionCode")
                or row.get("workflowDefinitionCode")
                or row.get("processDefinitionId")
            )
            name = (
                row.get("processDefinitionName")
                or row.get("workflowName")
                or row.get("name")
            )

            if code is not None and int(code) != int(workflow_code):
                continue
            if code is None and name and workflow_name and name != workflow_name:
                continue

            raw_identifier = row.get("id") or row.get("processInstanceId")
            if raw_identifier is None:
                continue

            identifier = str(raw_identifier)
            numeric_id = None
            try:
                numeric_id = int(raw_identifier)
            except (TypeError, ValueError):
                pass

            if (
                min_instance_id is not None
                and numeric_id is not None
                and numeric_id <= min_instance_id
            ):
                continue

            start_time = str(row.get("startTime") or "")

            if latest_identifier is None:
                latest_identifier = identifier
                latest_numeric = numeric_id
                latest_start = start_time
                continue

            if numeric_id is not None and (latest_numeric is None or numeric_id > latest_numeric):
                latest_identifier = identifier
                latest_numeric = numeric_id
                latest_start = start_time
                continue

            if start_time and (not latest_start or start_time > latest_start):
                latest_identifier = identifier
                latest_start = start_time

        return latest_identifier

    @staticmethod
    def _parse_int(value: Any) -> Optional[int]:
        if value is None:
            return None
        try:
            return int(value)
        except (TypeError, ValueError):  # pylint: disable=broad-except
            return None

    def _map_instance_detail(
        self,
        raw: Dict[str, Any],
        fallback_workflow_code: int,
        fallback_workflow_name: str,
    ) -> Dict[str, Any]:
        instance_id = raw.get("id") or raw.get("processInstanceId")
        if instance_id is None:
            raise ValueError("Instance detail missing id field")

        workflow_code = self._parse_int(
            raw.get("workflowDefinitionCode") or raw.get("processDefinitionCode")
        ) or fallback_workflow_code

        workflow_name = (
            raw.get("workflowDefinitionName")
            or raw.get("workflowName")
            or raw.get("processDefinitionName")
            or raw.get("name")
            or fallback_workflow_name
        )

        state = raw.get("state") or raw.get("stateType") or raw.get("status")
        start_time = self._normalize_timestamp(raw.get("startTime") or raw.get("start_time"))
        end_time = self._normalize_timestamp(raw.get("endTime") or raw.get("end_time"))
        duration_value = raw.get("duration")
        if not isinstance(duration_value, (int, float)):
            duration_value = self._parse_int(duration_value)

        run_times = self._parse_int(raw.get("runTimes") or raw.get("run_times")) or 0
        host = raw.get("host")
        command_type = raw.get("commandType") or raw.get("command_type")

        return {
            "instanceId": int(instance_id),
            "workflowCode": workflow_code,
            "workflowName": workflow_name,
            "state": state or "UNKNOWN",
            "startTime": start_time,
            "endTime": end_time,
            "duration": int(duration_value) if duration_value is not None else None,
            "runTimes": run_times,
            "host": host,
            "commandType": command_type,
        }

    @staticmethod
    def _normalize_timestamp(value: Any) -> Optional[str]:
        if value is None:
            return None
        if isinstance(value, (int, float)):
            try:
                dt = datetime.fromtimestamp(value / (1000 if value > 1e12 else 1))
                return dt.strftime("%Y-%m-%dT%H:%M:%S")
            except (OverflowError, OSError, ValueError):  # pylint: disable=broad-except
                return None
        if not isinstance(value, str):
            return None
        candidate = value.strip()
        if not candidate:
            return None
        if "T" in candidate:
            return candidate
        for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M:%S.%f"):
            try:
                dt = datetime.strptime(candidate, fmt)
                return dt.strftime("%Y-%m-%dT%H:%M:%S")
            except ValueError:
                continue
        return candidate

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

    def list_datasources(
        self,
        ds_type: Optional[str] = None,
        keyword: Optional[str] = None,
        page_size: int = 100,
    ) -> ListDatasourcesResponse:
        """
        Query DolphinScheduler REST API for available datasources.
        """
        token = self._login_and_get_token()
        if not token:
            raise ValueError("Failed to authenticate with DolphinScheduler API")

        api_base = self.settings.api_base_url.rstrip("/")
        list_url = f"{api_base}/datasources"

        params = {
            "pageNo": 1,
            "pageSize": max(1, page_size),
        }
        if keyword:
            params["searchVal"] = keyword
        if ds_type:
            params["type"] = ds_type.upper()

        try:
            response = requests.get(
                list_url,
                params=params,
                cookies={"sessionId": token},
                timeout=10,
            )
            response.raise_for_status()
            payload = response.json()
        except Exception as exc:  # pylint: disable=broad-except
            logger.error("Failed to query datasources from DolphinScheduler API: %s", exc)
            raise ValueError("Failed to query datasources from DolphinScheduler") from exc

        if not payload.get("success"):
            message = payload.get("msg") or payload.get("message") or "unknown error"
            logger.error("DolphinScheduler API responded with error: %s", message)
            raise ValueError(f"DolphinScheduler API error: {message}")

        data = payload.get("data") or {}
        raw_list = (
            data.get("totalList")
            or data.get("list")
            or data.get("datasources")
            or []
        )

        normalized_type = ds_type.lower() if ds_type else None
        items: List[DatasourceItem] = []

        for row in raw_list:
            if not isinstance(row, dict):
                continue
            name = row.get("name") or row.get("datasourceName")
            if not name:
                continue
            dtype = row.get("type") or row.get("datasourceType")
            if normalized_type:
                if not dtype:
                    continue
                if dtype.lower() != normalized_type:
                    continue

            item = DatasourceItem(
                id=row.get("id"),
                name=name,
                type=dtype,
                db_name=row.get("database") or row.get("dbName") or row.get("dbname"),
                description=row.get("note") or row.get("description"),
            )
            items.append(item)

        return ListDatasourcesResponse(datasources=items)

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

        tasks_map: Dict[int, Union[Shell, Sql]] = {}
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
        baseline_instance = self._fetch_latest_instance_id(
            project_name,
            workflow_code,
            workflow_name,
            max_attempts=1,
            poll_interval=0.5,
        )
        baseline_numeric = self._parse_int(baseline_instance)

        workflow.start()
        instance_id = self._fetch_latest_instance_id(
            project_name,
            workflow_code,
            workflow_name,
            max_attempts=8,
            poll_interval=1.5,
            min_instance_id=baseline_numeric,
        )

        if instance_id:
            logger.info(
                "Workflow execution started with instanceId=%s for workflow_code=%s",
                instance_id,
                workflow_code,
            )
            message = "submitted"
        else:
            message = "submitted but instanceId not available"

        return StartWorkflowResponse(instanceId=instance_id, message=message)

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
    ) -> Union[Shell, Sql]:
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
        self, tasks_map: Dict[int, Union[Shell, Sql]], relation: TaskRelationPayload
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

    def get_workflow_instance(
        self, workflow_code: int, request: GetInstanceRequest
    ) -> GetInstanceResponse:
        """
        Get workflow instance information from DolphinScheduler.

        Args:
            workflow_code: Workflow code
            request: Request with project name, workflow name, and optional instance ID

        Returns:
            Instance information including state, start/end time, duration
        """
        project_name = request.project_name or self.settings.workflow_project
        workflow_name = self._ensure_workflow_name(
            request.workflow_name or self.settings.workflow_name or self.settings.workflow_project
        )

        if request.instance_id is None:
            raise ValueError("instanceId is required to query workflow instance status")

        logger.info(
            "Getting workflow instance: workflow_code=%s project=%s workflow=%s instance_id=%s",
            workflow_code,
            project_name,
            workflow_name,
            request.instance_id,
        )

        project_code = self._get_project_code(project_name)
        if project_code is None:
            raise ValueError(f"Failed to resolve project {project_name} in DolphinScheduler")

        token = self._login_and_get_token()
        if not token:
            raise ValueError("Failed to authenticate with DolphinScheduler API")

        detail = self._fetch_instance_detail(project_code, int(request.instance_id), token)
        if detail is None:
            raise ValueError(
                f"Workflow instance {request.instance_id} not found in project {project_name}"
            )

        parsed = self._map_instance_detail(detail, workflow_code, workflow_name)
        return GetInstanceResponse(**parsed)

    def list_workflow_instances(
        self, workflow_code: int, request: ListInstancesRequest
    ) -> ListInstancesResponse:
        """
        List workflow instances with pagination.

        Args:
            workflow_code: Workflow code
            request: Request with pagination and filter parameters

        Returns:
            Paginated list of workflow instances
        """
        project_name = request.project_name or self.settings.workflow_project
        workflow_name = self._ensure_workflow_name(
            request.workflow_name or self.settings.workflow_name or self.settings.workflow_project
        )

        logger.info(
            "Listing workflow instances: workflow_code=%s project=%s page=%s size=%s",
            workflow_code,
            project_name,
            request.page_num,
            request.page_size,
        )

        project_code = self._get_project_code(project_name)
        if project_code is None:
            raise ValueError(f"Failed to resolve project {project_name} in DolphinScheduler")

        token = self._login_and_get_token()
        if not token:
            raise ValueError("Failed to authenticate with DolphinScheduler API")

        params: Dict[str, Any] = {
            "pageNo": max(1, request.page_num),
            "pageSize": max(1, request.page_size),
            "workflowDefinitionCode": workflow_code,
            "processDefinitionCode": workflow_code,
        }
        if request.state:
            params["stateType"] = request.state.upper()
        if request.workflow_name:
            params["searchVal"] = request.workflow_name
        if request.start_date:
            params["startDate"] = request.start_date
        if request.end_date:
            params["endDate"] = request.end_date
        if request.user:
            params["executorName"] = request.user

        api_base = self.settings.api_base_url.rstrip("/")
        endpoints = [
            f"{api_base}/projects/{project_code}/workflow-instances",
            f"{api_base}/projects/{project_code}/process-instances",
        ]

        payload = None
        for url in endpoints:
            payload = self._perform_api_get(url, params, token)
            if payload:
                break

        if not payload:
            raise ValueError("Failed to query workflow instances from DolphinScheduler API")

        data = payload.get("data") or {}
        raw_instances = self._extract_instance_payload(data)
        normalized_instances = [
            self._map_instance_detail(row, workflow_code, workflow_name)
            for row in raw_instances
        ]

        total = (
            self._parse_int(data.get("total"))
            or self._parse_int(data.get("totalCount"))
            or self._parse_int(data.get("totalNum"))
            or len(normalized_instances)
        )
        page_num = (
            self._parse_int(data.get("pageNo"))
            or self._parse_int(data.get("currPage"))
            or request.page_num
        )
        page_size = (
            self._parse_int(data.get("pageSize"))
            or self._parse_int(data.get("size"))
            or request.page_size
        )

        return ListInstancesResponse(
            total=total,
            pageNum=page_num,
            pageSize=page_size,
            instances=normalized_instances,
        )

    def get_instance_log(
        self, workflow_code: int, request: GetInstanceLogRequest
    ) -> GetInstanceLogResponse:
        """
        Get workflow instance execution log from DolphinScheduler.

        Args:
            workflow_code: Workflow code
            request: Request with instance ID and optional task name

        Returns:
            Log content for the instance or specific task
        """
        from pydolphinscheduler.java_gateway import gateway

        project_name = request.project_name
        user_name = request.user or self.settings.user_name

        logger.info(
            "Getting instance log: workflow_code=%s instance_id=%s task=%s",
            workflow_code,
            request.instance_id,
            request.task_name
        )

        try:
            # Placeholder implementation
            # Actual implementation would query DolphinScheduler API for logs
            log_content = f"Log for instance {request.instance_id}"
            if request.task_name:
                log_content += f" task {request.task_name}"

            return GetInstanceLogResponse(
                logContent=log_content,
                taskInstanceId=None
            )

        except Exception as e:
            logger.error(
                "Failed to get instance log: workflow_code=%s instance_id=%s error=%s",
                workflow_code,
                request.instance_id,
                str(e)
            )
            raise ValueError(f"Failed to get instance log: {str(e)}") from e

    def delete_workflow(self, workflow_code: int, project_name: str) -> None:
        """
        Delete a workflow definition from DolphinScheduler via REST API.

        Args:
            workflow_code: Workflow code to delete
            project_name: Project name

        Note: This is used for cleaning up temporary test workflows.
        """
        logger.info(
            "Deleting workflow: code=%s project=%s",
            workflow_code,
            project_name,
        )

        try:
            from pydolphinscheduler.java_gateway import gateway
            import requests

            # Get project code first
            user_name = self.settings.user_name
            try:
                project_info = gateway.query_project_by_name(user_name, project_name)
                project_code = int(project_info.getCode())
            except Exception as e:
                logger.error(
                    "Failed to get project code for deletion: project=%s error=%s",
                    project_name,
                    str(e)
                )
                # Still try to remove from cache
                if workflow_code in self._workflow_cache:
                    del self._workflow_cache[workflow_code]
                return

            # Use DolphinScheduler REST API to delete workflow
            # First, we need to authenticate and get the token
            api_base = self.settings.api_base_url
            login_url = f"{api_base}/login"

            # Login to get token
            login_response = requests.post(
                login_url,
                data={
                    "userName": self.settings.user_name,
                    "userPassword": self.settings.user_password
                }
            )

            if login_response.status_code != 200:
                logger.warning(
                    "Failed to login to DolphinScheduler API for deletion: status=%s",
                    login_response.status_code
                )
                # Still remove from cache
                if workflow_code in self._workflow_cache:
                    del self._workflow_cache[workflow_code]
                return

            response_data = login_response.json()
            if not response_data.get("success"):
                logger.warning(
                    "DolphinScheduler API login failed: %s",
                    response_data.get("msg")
                )
                if workflow_code in self._workflow_cache:
                    del self._workflow_cache[workflow_code]
                return

            token = response_data.get("data", {}).get("sessionId")
            if not token:
                logger.warning("No sessionId returned from DolphinScheduler API login")
                if workflow_code in self._workflow_cache:
                    del self._workflow_cache[workflow_code]
                return

            # Delete the workflow using REST API
            delete_url = f"{api_base}/projects/{project_code}/process-definition/{workflow_code}"
            delete_response = requests.delete(
                delete_url,
                params={"token": token}
            )

            if delete_response.status_code == 200:
                delete_data = delete_response.json()
                if delete_data.get("success"):
                    logger.info(
                        "Successfully deleted workflow from DolphinScheduler: code=%s project=%s",
                        workflow_code,
                        project_name
                    )
                else:
                    logger.warning(
                        "DolphinScheduler API delete failed: %s",
                        delete_data.get("msg")
                    )
            else:
                logger.warning(
                    "Failed to delete workflow via API: status=%s code=%s",
                    delete_response.status_code,
                    workflow_code
                )

            # Remove from cache regardless of API success
            if workflow_code in self._workflow_cache:
                del self._workflow_cache[workflow_code]
                logger.info(
                    "Removed workflow from cache: code=%s project=%s",
                    workflow_code,
                    project_name,
                )

        except Exception as e:
            logger.error(
                "Failed to delete workflow: code=%s project=%s error=%s",
                workflow_code,
                project_name,
                str(e)
            )
            # Don't raise - deletion is best-effort for cleanup
            # Still try to remove from cache
            try:
                if workflow_code in self._workflow_cache:
                    del self._workflow_cache[workflow_code]
            except:
                pass
