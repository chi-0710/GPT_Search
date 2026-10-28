from __future__ import annotations

import asyncio
import hashlib
import os
import uuid

import pytest
import pytest_asyncio
from sqlalchemy import text

from backend.server.database import get_engine
from backend.server.postgres_store import PostgresStore


pytestmark = [
    pytest.mark.asyncio,
    pytest.mark.skipif(
        os.getenv("RUN_POSTGRES_INTEGRATION") != "1",
        reason="requires the PostgreSQL + pgvector integration container",
    ),
]


@pytest_asyncio.fixture(autouse=True)
async def clean_database():
    tables = (
        "research_events, research_sources, chat_messages, artifacts, research_runs, "
        "document_chunks, documents, embedding_profiles, migration_imports, reports"
    )
    async with get_engine().begin() as connection:
        await connection.execute(text(f"TRUNCATE {tables} RESTART IDENTITY CASCADE"))
    yield
    async with get_engine().begin() as connection:
        await connection.execute(text(f"TRUNCATE {tables} RESTART IDENTITY CASCADE"))


async def test_report_run_event_chat_artifact_and_cascade():
    store = PostgresStore()
    run = await store.create_run(
        query="PostgreSQL persistence",
        report_type="research_report",
        report_source="web",
        tone="Objective",
        request_options={"api_key": "must-not-be-stored"},
    )
    await store.append_event(run["run_id"], {"type": "logs", "content": "one"})
    await store.append_event(run["run_id"], {"type": "report", "output": "answer"})
    await store.complete_run(
        run["run_id"],
        answer="# answer",
        sources=[
            {
                "url": "https://example.com/source",
                "title": "Example",
                "raw_content": "source body",
                "score": 0.9,
            }
        ],
    )

    message = {
        "id": "11111111-1111-4111-8111-111111111111",
        "role": "user",
        "content": "hello",
        "timestamp": 123,
    }
    assert await store.add_chat_message(run["research_id"], message)
    assert await store.add_chat_message(run["research_id"], message)
    assert len(await store.list_chat_messages(run["research_id"]) or []) == 1

    artifact = await store.put_artifact(
        content=b"report bytes",
        filename="report.md",
        kind="md",
        mime_type="text/markdown",
        report_public_id=run["research_id"],
        run_id=run["run_id"],
        legacy_path="outputs/report.md",
    )
    loaded = await store.get_artifact(artifact["id"])
    assert loaded is not None and loaded.content == b"report bytes"
    assert loaded.sha256 == hashlib.sha256(b"report bytes").hexdigest()
    assert await store.get_artifact_by_legacy_path("report.md") is not None
    assert [event["id"] for event in await store.list_events(run["run_id"]) or []] == [1, 2]
    report = await store.get_report(run["research_id"])
    assert report and report["answer"] == "# answer" and len(report["orderedData"]) == 2
    sources = await store.list_sources(run["run_id"])
    assert sources and sources[0]["raw_content"] == "source body"

    assert await store.delete_report(run["research_id"])
    assert await store.get_artifact(artifact["id"]) is None


async def test_concurrent_chat_writes_are_ordered_and_idempotent():
    store = PostgresStore()
    run = await store.create_run(
        query="chat ordering",
        report_type="research_report",
        report_source="web",
        tone="Objective",
    )
    messages = [
        {
            "id": str(uuid.UUID(int=index + 1)),
            "role": "user",
            "content": f"message-{index}",
        }
        for index in range(12)
    ]
    await asyncio.gather(
        *(store.add_chat_message(run["research_id"], message) for message in messages)
    )
    await asyncio.gather(
        *(store.add_chat_message(run["research_id"], message) for message in messages)
    )

    stored = await store.list_chat_messages(run["research_id"])
    assert stored is not None and len(stored) == len(messages)
    assert {message["id"] for message in stored} == {message["id"] for message in messages}


async def test_failed_run_snapshots_events_and_clear_reports_keeps_documents():
    store = PostgresStore()
    document = await store.put_document_blob(
        filename="independent.txt", mime_type="text/plain", content=b"independent"
    )
    run = await store.create_run(
        query="failure",
        report_type="research_report",
        report_source="web",
        tone="Objective",
    )
    await store.append_event(
        run["run_id"], {"type": "logs", "content": "error", "output": "failed"}
    )
    await store.fail_run(run["run_id"], "boom")

    report = await store.get_report(run["research_id"])
    failed_run = await store.get_run(run["run_id"])
    assert report and report["status"] == "failed" and len(report["orderedData"]) == 1
    assert failed_run and failed_run["status"] == "failed" and failed_run["error"] == "boom"

    assert await store.clear_reports() == 1
    assert await store.get_document(document["id"]) is not None


async def test_document_vector_similarity_and_hnsw_index():
    store = PostgresStore()
    document = await store.put_document_blob(
        filename="vectors.txt", mime_type="text/plain", content=b"alpha beta"
    )
    profile = await store.get_or_create_embedding_profile("fake", "fake-3d", 3)
    with pytest.raises(ValueError, match="dimension"):
        await store.replace_document_chunks(
            document["id"],
            profile_id=profile["id"],
            chunks=[{"content": "wrong", "metadata": {}}],
            embeddings=[[1.0, 0.0]],
            dimensions=3,
        )
    await store.replace_document_chunks(
        document["id"],
        profile_id=profile["id"],
        chunks=[
            {"content": "alpha", "metadata": {"index": 0}},
            {"content": "beta", "metadata": {"index": 1}},
        ],
        embeddings=[[1.0, 0.0, 0.0], [0.0, 1.0, 0.0]],
        dimensions=3,
    )
    assert await store.configure_vector_index()
    matches = await store.search_document_chunks(
        [1.0, 0.0, 0.0], limit=2, similarity_threshold=-1.0
    )
    assert [item["raw_content"] for item in matches] == ["alpha", "beta"]
