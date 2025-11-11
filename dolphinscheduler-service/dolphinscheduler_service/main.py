from __future__ import annotations

import logging
from typing import Callable, Optional, TypeVar

import anyio
import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from .config import Settings, get_settings
from .models import (
    ApiResponse,
    EnsureWorkflowRequest,
    GetInstanceLogRequest,
    GetInstanceRequest,
    ListInstancesRequest,
    QueryProjectRequest,
    ReleaseWorkflowRequest,
    StartWorkflowRequest,
    SyncWorkflowRequest,
)
from .scheduler import DolphinSchedulerServiceCore

logger = logging.getLogger(__name__)

T = TypeVar("T")


def create_app(settings: Optional[Settings] = None) -> FastAPI:
    settings = settings or get_settings()
    service = DolphinSchedulerServiceCore(settings)

    app = FastAPI(
        title=settings.meta.name,
        version=settings.meta.version,
        docs_url="/docs",
        redoc_url="/redoc",
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.on_event("startup")
    async def _refresh_logging() -> None:
        # Uvicorn reconfigures logging after importing the app; run again so our loggers keep handlers.
        settings.configure_logging()

    def success(data: Optional[dict] = None, message: str = "ok"):
        return ApiResponse.ok(data=data, message=message).model_dump()

    def wrap_errors(fn: Callable[[], T]) -> T:
        try:
            return fn()
        except ValueError as exc:
            logger.error("Request failed: %s", exc)
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    async def run(blocking_fn: Callable[[], T]) -> T:
        return await anyio.to_thread.run_sync(blocking_fn)

    @app.get("/health")
    async def health() -> dict:
        return success({"status": "healthy"})

    @app.post("/api/v1/workflows/ensure")
    async def ensure_workflow(
        payload: EnsureWorkflowRequest,
    ) -> dict:
        logger.debug("ensure_workflow payload=%s", payload.model_dump(by_alias=True))

        def task():
            result = service.ensure_workflow(payload)
            return success(result.model_dump(by_alias=True))

        return await run(task)

    @app.post("/api/v1/workflows/{workflow_code}/sync")
    async def sync_workflow(
        workflow_code: int,
        payload: SyncWorkflowRequest,
    ) -> dict:
        logger.debug(
            "sync_workflow workflow_code=%s payload=%s",
            workflow_code,
            payload.model_dump(by_alias=True),
        )

        def task():
            result = wrap_errors(lambda: service.sync_workflow(workflow_code, payload))
            return success(result.model_dump(by_alias=True))

        return await run(task)

    @app.post("/api/v1/workflows/{workflow_code}/release")
    async def release_workflow(
        workflow_code: int,
        payload: ReleaseWorkflowRequest,
    ) -> dict:
        logger.debug(
            "release_workflow workflow_code=%s payload=%s",
            workflow_code,
            payload.model_dump(by_alias=True),
        )

        def task():
            wrap_errors(lambda: service.release_workflow(workflow_code, payload))
            return success({"workflowCode": workflow_code})

        return await run(task)

    @app.post("/api/v1/workflows/{workflow_code}/start")
    async def start_workflow(
        workflow_code: int,
        payload: StartWorkflowRequest,
    ) -> dict:
        logger.debug(
            "start_workflow workflow_code=%s payload=%s",
            workflow_code,
            payload.model_dump(by_alias=True),
        )

        def task():
            result = wrap_errors(lambda: service.start_workflow(workflow_code, payload))
            return success(result.model_dump(by_alias=True))

        return await run(task)

    @app.post("/api/v1/projects/query")
    async def query_project(
        payload: QueryProjectRequest,
    ) -> dict:
        logger.debug("query_project payload=%s", payload.model_dump(by_alias=True))

        def task():
            result = service.query_project(payload)
            return success(result.model_dump(by_alias=True))

        return await run(task)

    @app.get("/api/v1/dolphin/datasources")
    async def list_datasources(
        type: Optional[str] = None,
        keyword: Optional[str] = None,
    ) -> dict:
        logger.debug("list_datasources type=%s keyword=%s", type, keyword)

        def task():
            result = service.list_datasources(ds_type=type, keyword=keyword)
            return success(result.model_dump(by_alias=True))

        return await run(task)

    @app.post("/api/v1/workflows/{workflow_code}/instances/get")
    async def get_workflow_instance(
        workflow_code: int,
        payload: GetInstanceRequest,
    ) -> dict:
        logger.debug(
            "get_workflow_instance workflow_code=%s payload=%s",
            workflow_code,
            payload.model_dump(by_alias=True),
        )

        def task():
            result = service.get_workflow_instance(workflow_code, payload)
            return success(result.model_dump(by_alias=True))

        return await run(task)

    @app.post("/api/v1/workflows/{workflow_code}/instances/list")
    async def list_workflow_instances(
        workflow_code: int,
        payload: ListInstancesRequest,
    ) -> dict:
        logger.debug(
            "list_workflow_instances workflow_code=%s payload=%s",
            workflow_code,
            payload.model_dump(by_alias=True),
        )

        def task():
            result = wrap_errors(lambda: service.list_workflow_instances(workflow_code, payload))
            return success(result.model_dump(by_alias=True))

        return await run(task)

    @app.post("/api/v1/workflows/{workflow_code}/instances/log")
    async def get_instance_log(
        workflow_code: int,
        payload: GetInstanceLogRequest,
    ) -> dict:
        logger.debug(
            "get_instance_log workflow_code=%s payload=%s",
            workflow_code,
            payload.model_dump(by_alias=True),
        )

        def task():
            result = service.get_instance_log(workflow_code, payload)
            return success(result.model_dump(by_alias=True))

        return await run(task)

    @app.post("/api/v1/workflows/{workflow_code}/instances/{instance_id}")
    async def get_workflow_instance_by_path(
        workflow_code: int,
        instance_id: str,
        payload: Optional[GetInstanceRequest] = None,
    ) -> dict:
        try:
            resolved_instance_id = int(instance_id)
        except ValueError as exc:  # pragma: no cover - explicit validation
            raise HTTPException(status_code=400, detail="instanceId must be an integer") from exc

        logger.debug(
            "get_workflow_instance_by_path workflow_code=%s instance_id=%s payload=%s",
            workflow_code,
            instance_id,
            payload.model_dump(by_alias=True) if payload else None,
        )

        def task():
            request_payload = payload or GetInstanceRequest(projectName=settings.workflow_project)
            if request_payload.project_name is None:
                request_payload.project_name = settings.workflow_project
            if request_payload.instance_id is None:
                request_payload.instance_id = resolved_instance_id
            result = service.get_workflow_instance(workflow_code, request_payload)
            return success(result.model_dump(by_alias=True))

        return await run(task)

    @app.post("/api/v1/workflows/{workflow_code}/delete")
    async def delete_workflow(
        workflow_code: int,
        payload: dict,
    ) -> dict:
        logger.debug(
            "delete_workflow workflow_code=%s payload=%s",
            workflow_code,
            payload,
        )

        def task():
            project_name = payload.get("projectName")
            service.delete_workflow(workflow_code, project_name)
            return success({"workflowCode": workflow_code, "deleted": True})

        return await run(task)

    return app


settings = get_settings()
app = create_app(settings=settings)


def run() -> None:
    """Start Uvicorn with settings-defined host/port."""
    uvicorn.run(
        app,
        host=settings.service_host,
        port=settings.service_port,
        log_level=settings.log_level.lower(),
    )


if __name__ == "__main__":
    run()
