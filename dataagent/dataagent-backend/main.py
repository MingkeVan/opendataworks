"""
DataAgent Backend 入口 — FastAPI 应用
"""
import logging
import sys

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from api.routes import router
from config import get_settings

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
    """启动时尝试预加载语义层"""
    cfg = get_settings()
    logger.info(
        "Starting DataAgent Backend on %s:%d (model=%s)",
        cfg.host, cfg.port, cfg.claude_model,
    )

    # 尝试预加载（失败也不阻塞启动）
    try:
        from core.semantic_layer import get_semantic_layer
        sl = get_semantic_layer()
        sl.load()
        logger.info("Semantic layer pre-loaded successfully")
    except Exception as e:
        logger.warning("Failed to pre-load semantic layer: %s (can be loaded later via /reload)", e)

    # 初始化会话持久化 schema（独立于业务元数据 schema）
    try:
        from core.session_store import get_session_store
        get_session_store().init_schema()
        logger.info("Session store schema initialized")
    except Exception as e:
        logger.warning("Failed to init session store schema: %s", e)


if __name__ == "__main__":
    import uvicorn
    cfg = get_settings()
    uvicorn.run(
        "main:app",
        host=cfg.host,
        port=cfg.port,
        reload=cfg.debug,
    )
