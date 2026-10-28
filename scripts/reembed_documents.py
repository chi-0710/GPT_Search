"""Re-embed every stored document and activate the configured model profile.

Run this command with application writes stopped. The new profile is activated
only after every document has been reindexed successfully.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from backend.server.database import dispose_engine
from backend.server.document_service import embedding_settings, prepare_document_index
from backend.server.postgres_store import PostgresStore


async def run(dry_run: bool) -> None:
    store = PostgresStore()
    provider, model, dimensions, _kwargs = embedding_settings()
    documents = await store.list_documents()
    print(
        f"target={provider}:{model} dimensions={dimensions} documents={len(documents)} "
        f"dry_run={dry_run}"
    )
    if dry_run:
        await dispose_engine()
        return
    profile = await store.prepare_embedding_profile(provider, model, dimensions)
    prepared_documents = []
    for position, document in enumerate(documents, start=1):
        print(f"[{position}/{len(documents)}] {document['filename']}")
        prepared_documents.append(
            await prepare_document_index(
                store, document["id"], profile_override=profile
            )
        )
    await store.replace_all_document_chunks_and_activate(
        profile["id"], prepared_documents
    )
    index_name = await store.configure_vector_index()
    print(f"activated={profile['id']} index={index_name}")
    await dispose_engine()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    asyncio.run(run(args.dry_run))


if __name__ == "__main__":
    main()
