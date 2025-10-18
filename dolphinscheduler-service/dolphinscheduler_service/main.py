from __future__ import annotations

import logging
from typing import Callable, TypeVar

import anyio
from fastapi import FastAPI
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


def create_app(settings: Settings | None = None) -> FastAPI:
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

    def success(data: dict | None = None, message: str = "ok"):
        return ApiResponse.ok(data=data, message=message).model_dump()

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
            result = service.sync_workflow(workflow_code, payload)
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
            service.release_workflow(workflow_code, payload)
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
            result = service.start_workflow(workflow_code, payload)
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
            result = service.list_workflow_instances(workflow_code, payload)
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

    return app


settings = get_settings()
app = create_app(settings=settings)
