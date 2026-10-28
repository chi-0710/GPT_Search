"""Async PostgreSQL connection management for the API service."""

from __future__ import annotations

import os
from pathlib import Path
from contextlib import asynccontextmanager
from functools import lru_cache
from typing import AsyncIterator

from asyncpg import PostgresError
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)


class StorageUnavailable(RuntimeError):
    """Raised when PostgreSQL cannot serve an application operation."""


class StorageConflict(RuntimeError):
    """Raised for application-level uniqueness conflicts."""


def _database_url() -> str:
    url = os.getenv(
        "DATABASE_URL",
        "postgresql+asyncpg://gpt_researcher:gpt_researcher@localhost:5432/gpt_researcher",
    )
    if url.startswith("postgres://"):
        return "postgresql+asyncpg://" + url[len("postgres://") :]
    if url.startswith("postgresql://"):
        return "postgresql+asyncpg://" + url[len("postgresql://") :]
    return url


@lru_cache(maxsize=1)
def get_engine() -> AsyncEngine:
    """Create the process-wide engine lazily.

    Laziness keeps non-database commands importable while PostgreSQL is down;
    all business operations still fail closed when they actually need storage.
    """

    return create_async_engine(
        _database_url(),
        pool_pre_ping=True,
        pool_size=int(os.getenv("DB_POOL_SIZE", "5")),
        max_overflow=int(os.getenv("DB_MAX_OVERFLOW", "10")),
        pool_timeout=float(os.getenv("DB_POOL_TIMEOUT_SECONDS", "30")),
    )


@lru_cache(maxsize=1)
def get_session_factory() -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(
        get_engine(),
        autoflush=False,
        expire_on_commit=False,
    )


@asynccontextmanager
async def transaction() -> AsyncIterator[AsyncSession]:
    """Provide an isolated transaction and normalize connectivity failures."""

    try:
        async with get_session_factory()() as session:
            async with session.begin():
                yield session
    except StorageConflict:
        raise
    except IntegrityError as exc:
        raise StorageConflict("The write conflicts with existing stored data") from exc
    except (SQLAlchemyError, PostgresError, ModuleNotFoundError, OSError) as exc:
        raise StorageUnavailable("PostgreSQL storage is unavailable") from exc


@asynccontextmanager
async def read_session() -> AsyncIterator[AsyncSession]:
    try:
        async with get_session_factory()() as session:
            yield session
    except (SQLAlchemyError, PostgresError, ModuleNotFoundError, OSError) as exc:
        raise StorageUnavailable("PostgreSQL storage is unavailable") from exc


async def readiness_status() -> dict[str, object]:
    """Check connectivity, migrations and pgvector without mutating schema."""

    from alembic.config import Config
    from alembic.script import ScriptDirectory

    migration_config = Config()
    migration_config.set_main_option("script_location", str(Path(__file__).resolve().parents[2] / "alembic"))
    expected_migration = ScriptDirectory.from_config(migration_config).get_current_head()
    try:
        async with get_engine().connect() as connection:
            await connection.execute(text("SELECT 1"))
            vector_enabled = bool(
                await connection.scalar(
                    text("SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')")
                )
            )
            migration_table = bool(
                await connection.scalar(
                    text(
                        "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                        "WHERE table_schema = 'public' AND table_name = 'alembic_version')"
                    )
                )
            )
            migration_version = (
                await connection.scalar(
                    text(
                        "SELECT version_num FROM alembic_version "
                        "ORDER BY version_num DESC LIMIT 1"
                    )
                )
                if migration_table
                else None
            )
        ready = vector_enabled and migration_version == expected_migration
        return {
            "ready": ready,
            "database": "ok",
            "pgvector": vector_enabled,
            "migration": migration_version,
            "expected_migration": expected_migration,
        }
    except Exception as exc:  # readiness must return a diagnostic response
        return {
            "ready": False,
            "database": "unavailable",
            "pgvector": False,
            "migration": None,
            "expected_migration": expected_migration,
            "error": str(exc),
        }


async def dispose_engine() -> None:
    if get_engine.cache_info().currsize:
        await get_engine().dispose()
