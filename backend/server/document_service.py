"""Document ingestion and pgvector retrieval services."""

from __future__ import annotations

import asyncio
import json
import os
import tempfile
from pathlib import Path
from typing import Any

from langchain_text_splitters import RecursiveCharacterTextSplitter

from search.document.document import DocumentLoader
from search.memory.embeddings import Memory

from .postgres_store import PostgresStore


def embedding_settings() -> tuple[str, str, int, dict[str, Any]]:
    configured = os.getenv("EMBEDDING", "openai:text-embedding-3-small")
    provider, separator, model = configured.partition(":")
    if not separator:
        provider, model = "openai", configured
    dimensions = int(os.getenv("EMBEDDING_DIMENSION", "1536"))
    try:
        kwargs = json.loads(os.getenv("EMBEDDING_KWARGS", "{}"))
    except json.JSONDecodeError as exc:
        raise ValueError('EMBEDDING_KWARGS 必须是有效的 JSON') from exc
    return provider, model, dimensions, kwargs


async def _embed_documents(embedding_client: Any, texts: list[str]) -> list[list[float]]:
    if hasattr(embedding_client, "aembed_documents"):
        return await embedding_client.aembed_documents(texts)
    return await asyncio.to_thread(embedding_client.embed_documents, texts)


async def _embed_query(embedding_client: Any, query: str) -> list[float]:
    if hasattr(embedding_client, "aembed_query"):
        return await embedding_client.aembed_query(query)
    return await asyncio.to_thread(embedding_client.embed_query, query)


async def prepare_document_index(
    store: PostgresStore,
    document_id: str,
    profile_override: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Parse and embed without changing database chunks or profile state."""

    document = await store.get_document(document_id)
    if document is None:
        raise ValueError('未找到文档')
    provider, model, configured_dimensions, kwargs = embedding_settings()
    if profile_override:
        provider = str(profile_override["provider"])
        model = str(profile_override["model"])
        configured_dimensions = int(profile_override["dimensions"])

    with tempfile.TemporaryDirectory(prefix="gptr-document-") as temporary_directory:
        path = Path(temporary_directory) / document.filename
        path.write_bytes(document.content)
        loaded = await DocumentLoader([str(path)]).load()

    splitter = RecursiveCharacterTextSplitter(
        chunk_size=int(os.getenv("DOCUMENT_CHUNK_SIZE", "1000")),
        chunk_overlap=int(os.getenv("DOCUMENT_CHUNK_OVERLAP", "100")),
    )
    chunks: list[dict[str, Any]] = []
    for item in loaded:
        if isinstance(item, dict):
            text = str(
                item.get("raw_content")
                or item.get("content")
                or item.get("page_content")
                or ""
            )
            metadata = {
                key: value
                for key, value in item.items()
                if key not in {"raw_content", "content", "page_content"}
            }
            metadata.update(item.get("metadata") or {})
            metadata.pop("metadata", None)
            metadata.pop("source", None)  # Never expose the temporary parse path.
            metadata["filename"] = document.filename
        else:
            text = str(getattr(item, "page_content", item))
            metadata = dict(getattr(item, "metadata", {}) or {})
        for part in splitter.split_text(text):
            if part.strip():
                chunks.append({"content": part, "metadata": metadata})

    if not chunks:
        raise ValueError('文档解析器未返回文本')
    embedding_client = Memory(provider, model, **kwargs).get_embeddings()
    embeddings = await _embed_documents(
        embedding_client, [chunk["content"] for chunk in chunks]
    )
    actual_dimensions = len(embeddings[0]) if embeddings else 0
    if actual_dimensions != configured_dimensions:
        raise ValueError(
            f"向量维度 {actual_dimensions} 与 EMBEDDING_DIMENSION={configured_dimensions} 不一致"
        )
    return {
        "document_id": document_id,
        "provider": provider,
        "model": model,
        "dimensions": actual_dimensions,
        "chunks": chunks,
        "embeddings": embeddings,
    }


async def index_document(
    store: PostgresStore,
    document_id: str,
    profile_override: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Parse, chunk and embed one stored document without persistent local files."""

    try:
        prepared = await prepare_document_index(store, document_id, profile_override)
        profile = profile_override or await store.get_or_create_embedding_profile(
            prepared["provider"], prepared["model"], prepared["dimensions"]
        )
        await store.replace_document_chunks(
            document_id,
            profile_id=profile["id"],
            chunks=prepared["chunks"],
            embeddings=prepared["embeddings"],
            dimensions=prepared["dimensions"],
        )
        if not profile_override or profile_override.get("active"):
            await store.configure_vector_index()
        return {
            "document_id": document_id,
            "status": "ready",
            "chunks": len(prepared["chunks"]),
            "embedding_profile": profile,
        }
    except Exception as exc:
        await store.mark_document_failed(document_id, str(exc))
        raise


class PostgresDocumentRetriever:
    """Callable adapter injected into Local/Hybrid research runs."""

    def __init__(self, store: PostgresStore, document_ids: list[str] | None = None):
        self.store = store
        self.document_ids = document_ids

    async def __call__(self, query: str) -> list[dict[str, Any]]:
        if self.document_ids == []:
            return []
        provider, model, dimensions, kwargs = embedding_settings()
        profile = await self.store.get_or_create_embedding_profile(
            provider, model, dimensions
        )
        embedding_client = Memory(provider, model, **kwargs).get_embeddings()
        embedding = await _embed_query(embedding_client, query)
        if len(embedding) != profile["dimensions"]:
            raise ValueError('查询向量的维度与当前启用的配置不一致')
        matches = await self.store.search_document_chunks(
            embedding,
            document_ids=self.document_ids,
            limit=int(os.getenv("DOCUMENT_RETRIEVAL_LIMIT", "8")),
            similarity_threshold=float(
                os.getenv("DOCUMENT_SIMILARITY_THRESHOLD", "0.35")
            ),
        )
        return matches


def select_document_ids(
    documents: list[dict[str, Any]], profile_id: str, scope: str, ids: list[str], source: str,
) -> list[str]:
    """Freeze the allowed ready documents; invalid selections never broaden scope."""
    if scope not in {"all", "selected"}:
        raise ValueError("document_scope 必须为 all 或 selected")
    ready = {item["id"] for item in documents
             if item["status"] == "ready" and item.get("embedding_profile_id") == profile_id}
    if scope == "selected":
        if not ids:
            raise ValueError("请至少选择一份可检索文件")
        if any(identifier not in ready for identifier in ids):
            raise ValueError("所选文件不存在、尚未完成索引或向量模型不匹配，请刷新资料库")
        result = list(dict.fromkeys(ids))
    else:
        result = sorted(ready)
    if source == "local" and not result:
        raise ValueError("没有可检索的文件，请先上传文件并完成索引")
    return result


async def resolve_document_scope(store: PostgresStore, options: dict[str, Any]) -> list[str]:
    """Resolve once before creating a task, shared by HTTP and WebSocket."""
    source = options.get("report_source") or "web"
    if source not in {"web", "local", "hybrid"}:
        raise ValueError(f"不支持的资料来源：{source}")
    if source not in {"local", "hybrid"}:
        return []
    provider, model, dimensions, _ = embedding_settings()
    profile = await store.get_or_create_embedding_profile(provider, model, dimensions)
    return select_document_ids(
        await store.list_documents(), str(profile["id"]),
        options.get("document_scope", "all"), options.get("document_ids") or [], source,
    )
