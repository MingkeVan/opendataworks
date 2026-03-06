from __future__ import annotations

from logging.config import fileConfig
from urllib.parse import quote_plus

from alembic import context
from sqlalchemy import create_engine, pool

from config import get_settings

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = None


def _database_url() -> str:
    cfg = get_settings()
    user = quote_plus(str(cfg.mysql_user or "").strip())
    password = quote_plus(str(cfg.mysql_password or ""))
    host = str(cfg.mysql_host or "localhost").strip()
    port = int(cfg.mysql_port or 3306)
    database = str(cfg.session_mysql_database or "").strip()
    auth = user
    if password:
        auth = f"{auth}:{password}"
    return f"mysql+pymysql://{auth}@{host}:{port}/{database}?charset=utf8mb4"


def run_migrations_offline() -> None:
    context.configure(
        url=_database_url(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_type=True,
    )

    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = create_engine(_database_url(), poolclass=pool.NullPool)

    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            compare_type=True,
        )

        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
