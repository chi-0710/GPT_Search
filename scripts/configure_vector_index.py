"""Create the HNSW index for the active embedding profile."""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from backend.server.database import dispose_engine
from backend.server.postgres_store import PostgresStore


async def main() -> None:
    store = PostgresStore()
    index_name = await store.configure_vector_index()
    print(index_name or "No active embedding profile; upload or reindex a document first.")
    await dispose_engine()


if __name__ == "__main__":
    asyncio.run(main())
