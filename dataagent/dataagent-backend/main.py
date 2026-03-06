"""
DataAgent Backend 入口 — FastAPI 应用
"""
import logging
import sys

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from api.routes import router
from config import get_settings
from core.skills_loader import resolve_agent_project_cwd, resolve_skills_root_dir, validate_skills_bundle
from core.skills_sync import ensure_static_skills_bundle

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    stream=sys.stdout,
)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="DataAgent Backend",
    description="智能问数服务后端 — 基于 Claude AI 的自然语言转 SQL",
    version="0.1.0",
)

# CORS — 允许前端直接对接
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 注册路由
app.include_router(router)


@app.get("/")
async def root():
    return {
        "service": "dataagent-backend",
        "version": "0.1.0",
        "docs": "/docs",
    }


@app.on_event("startup")
async def startup():
    """启动检查：skills 路径、语义加载、会话 schema"""
    cfg = get_settings()
    logger.info(
        "Starting DataAgent Backend on %s:%d provider=%s model=%s",
        cfg.host,
        cfg.port,
        cfg.llm_provider,
        cfg.claude_model,
    )

    try:
        ensure_static_skills_bundle()
        skills_root = resolve_skills_root_dir()
        agent_cwd = resolve_agent_project_cwd()
        skills_state = validate_skills_bundle(force_reload=True)
        logger.info(
            "Skills ready root=%s agent_cwd=%s tables=%s rules=%s few_shots=%s",
            skills_root,
            agent_cwd,
            skills_state.get("metadata_tables"),
            skills_state.get("business_rules"),
            skills_state.get("few_shots"),
        )
    except Exception as e:
        logger.exception("Skills bootstrap failed: %s", e)

    try:
        from core.semantic_layer import get_semantic_layer

        get_semantic_layer().load()
        logger.info("Semantic layer loaded")
    except Exception as e:
        logger.warning("Semantic layer preload failed: %s", e)

    try:
        from core.session_store import get_session_store

        get_session_store().init_schema()
        logger.info("Session schema initialized in `%s`", cfg.session_mysql_database)
    except Exception as e:
        logger.exception("Session schema init failed: %s", e)


if __name__ == "__main__":
    import uvicorn
    cfg = get_settings()
    uvicorn.run(
        "main:app",
        host=cfg.host,
        port=cfg.port,
        reload=cfg.debug,
    )
