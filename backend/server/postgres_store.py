"""Transactional application repository backed by PostgreSQL."""

from __future__ import annotations

import hashlib
import json
import mimetypes
import os
import re
import uuid
from datetime import datetime, timezone
from pathlib import PurePosixPath
from typing import Any, Iterable, Sequence

from sqlalchemy import delete, func, select, text, update
from .database import StorageConflict, read_session, transaction
from .db_models import (
    Artifact,
    ChatMessage,
    DocumentChunk,
    EmbeddingProfile,
    MigrationImport,
    Report,
    ResearchEvent,
    ResearchEvidence,
    ResearchRun,
    ResearchSource,
    StoredDocument,
)


SENSITIVE_OPTION_KEYS = {
    "authorization",
    "api_key",
    "apikey",
    "token",
    "secret",
    "password",
    "connection_token",
}


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def timestamp_ms(value: datetime | None) -> int:
    return int((value or utcnow()).timestamp() * 1000)


def redact_options(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            str(key): (
                "[REDACTED]"
                if _sensitive_option_key(str(key))
                else redact_options(item)
            )
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [redact_options(item) for item in value]
    return value


def _sensitive_option_key(key: str) -> bool:
    normalized = key.lower().replace("-", "_")
    return normalized in SENSITIVE_OPTION_KEYS or normalized.endswith(
        ("_api_key", "_token", "_secret", "_password")
    )


def normalize_legacy_path(path: str | None) -> str | None:
    if not path:
        return None
    normalized = path.replace("\\", "/").strip().lstrip("/")
    while "//" in normalized:
        normalized = normalized.replace("//", "/")
    return str(PurePosixPath(normalized))


def _uuid(value: str | uuid.UUID) -> uuid.UUID:
    return value if isinstance(value, uuid.UUID) else uuid.UUID(str(value))


class PostgresStore:
    """Small repository façade used by HTTP, WebSocket and migration flows."""

    @property
    def max_blob_bytes(self) -> int:
        return int(os.getenv("MAX_BLOB_SIZE_MB", "50")) * 1024 * 1024

    async def list_reports(
        self,
        report_ids: Sequence[str] | None = None,
        *,
        limit: int | None = None,
        cursor: datetime | None = None,
        include_incomplete: bool = False,
    ) -> list[dict[str, Any]]:
        async with read_session() as session:
            stmt = select(Report)
            if report_ids is not None:
                stmt = stmt.where(Report.public_id.in_(list(report_ids)))
            elif not include_incomplete:
                stmt = stmt.where(Report.status == "completed")
            if cursor:
                stmt = stmt.where(Report.updated_at < cursor)
            stmt = stmt.order_by(Report.updated_at.desc())
            if limit:
                stmt = stmt.limit(max(1, min(limit, 200)))
            reports = list((await session.scalars(stmt)).all())
            chats = await self._load_chat_map(session, [report.id for report in reports])
            return [self._report_dict(report, chats.get(report.id, [])) for report in reports]

    async def get_report(self, public_id: str) -> dict[str, Any] | None:
        async with read_session() as session:
            report = await session.scalar(select(Report).where(Report.public_id == public_id))
            if report is None:
                return None
            chats = await self._load_chat_map(session, [report.id])
            return self._report_dict(report, chats.get(report.id, []))

    async def upsert_report(self, public_id: str, data: dict[str, Any]) -> dict[str, Any]:
        now = utcnow()
        incoming_ms = data.get("timestamp")
        incoming_time = (
            datetime.fromtimestamp(incoming_ms / 1000, tz=timezone.utc)
            if isinstance(incoming_ms, int)
            else now
        )
        async with transaction() as session:
            report = await session.scalar(
                select(Report).where(Report.public_id == public_id).with_for_update()
            )
            if report is None:
                report = Report(
                    public_id=public_id,
                    created_at=incoming_time,
                    updated_at=incoming_time,
                )
                session.add(report)
            report.question = str(data.get("question") or report.question or "")
            if data.get("answer") is not None:
                report.answer = str(data.get("answer") or "")
            if data.get("orderedData") is not None:
                ordered = data.get("orderedData")
                report.ordered_data = ordered if isinstance(ordered, list) else []
            report.status = str(
                data.get("status")
                or ("completed" if report.answer else report.status or "queued")
            )
            report.updated_at = max(report.updated_at or incoming_time, incoming_time)
            if report.status == "completed":
                report.completed_at = report.completed_at or report.updated_at
            await session.flush()
            if "chatMessages" in data and isinstance(data.get("chatMessages"), list):
                await session.execute(delete(ChatMessage).where(ChatMessage.report_id == report.id))
                for index, message in enumerate(data["chatMessages"], start=1):
                    session.add(self._new_chat_message(report.id, message, index))
        return (await self.get_report(public_id)) or {}

    async def patch_report(self, public_id: str, data: dict[str, Any]) -> bool:
        async with transaction() as session:
            report = await session.scalar(
                select(Report).where(Report.public_id == public_id).with_for_update()
            )
            if report is None:
                return False
            if data.get("question") is not None:
                report.question = str(data["question"])
            if data.get("answer") is not None:
                report.answer = str(data["answer"])
            if data.get("orderedData") is not None and isinstance(data["orderedData"], list):
                report.ordered_data = data["orderedData"]
            if data.get("status") in {"queued", "running", "completed", "failed"}:
                report.status = data["status"]
            elif report.answer:
                report.status = "completed"
            report.updated_at = utcnow()
            if report.status == "completed":
                report.completed_at = report.completed_at or report.updated_at
            return True

    async def delete_report(self, public_id: str) -> bool:
        async with transaction() as session:
            result = await session.execute(delete(Report).where(Report.public_id == public_id))
            return bool(result.rowcount)

    async def clear_reports(self) -> int:
        async with transaction() as session:
            result = await session.execute(delete(Report))
            await session.execute(delete(ResearchRun))
            await session.execute(delete(Artifact))
            return int(result.rowcount or 0)

    async def list_chat_messages(self, public_id: str) -> list[dict[str, Any]] | None:
        async with read_session() as session:
            report_id = await session.scalar(select(Report.id).where(Report.public_id == public_id))
            if report_id is None:
                return None
            rows = list(
                (
                    await session.scalars(
                        select(ChatMessage)
                        .where(ChatMessage.report_id == report_id)
                        .order_by(ChatMessage.sequence, ChatMessage.created_at)
                    )
                ).all()
            )
            return [self._chat_dict(row) for row in rows]

    async def add_chat_message(self, public_id: str, message: dict[str, Any]) -> bool:
        async with transaction() as session:
            report = await session.scalar(
                select(Report).where(Report.public_id == public_id).with_for_update()
            )
            if report is None:
                return False
            message_id = message.get("id") or message.get("messageId")
            try:
                parsed_id = _uuid(message_id) if message_id else uuid.uuid4()
            except ValueError as exc:
                raise StorageConflict("Message id must be a valid UUID") from exc
            existing_report_id = await session.scalar(
                select(ChatMessage.report_id).where(ChatMessage.id == parsed_id)
            )
            if existing_report_id:
                if existing_report_id != report.id:
                    raise StorageConflict("Message id already belongs to another report")
                return True
            next_sequence = (
                await session.scalar(
                    select(func.coalesce(func.max(ChatMessage.sequence), 0) + 1).where(
                        ChatMessage.report_id == report.id
                    )
                )
            ) or 1
            row = self._new_chat_message(report.id, message, int(next_sequence))
            row.id = parsed_id
            session.add(row)
            report.updated_at = utcnow()
            return True

    async def create_run(
        self,
        *,
        query: str,
        report_type: str,
        report_source: str,
        tone: str,
        request_options: dict[str, Any] | None = None,
        public_id: str | None = None,
    ) -> dict[str, str]:
        from search.utils.report_modes import normalize_report_mode
        report_type = normalize_report_mode(report_type)
        public_id = public_id or str(uuid.uuid4())
        now = utcnow()
        async with transaction() as session:
            report = Report(
                public_id=public_id,
                question=query,
                answer="",
                ordered_data=[],
                status="running",
                created_at=now,
                updated_at=now,
            )
            session.add(report)
            await session.flush()
            run = ResearchRun(
                report_id=report.id,
                query=query,
                report_type=report_type,
                report_source=report_source,
                tone=tone,
                request_options=redact_options(request_options or {}),
                status="running",
                started_at=now,
            )
            session.add(run)
            await session.flush()
            return {"run_id": str(run.id), "research_id": public_id}

    async def ensure_imported_run(
        self,
        *,
        public_id: str | None,
        query: str,
        report_type: str = "research_report",
        status: str = "completed",
    ) -> dict[str, str]:
        async with transaction() as session:
            report: Report | None = None
            if public_id:
                report = await session.scalar(select(Report).where(Report.public_id == public_id))
            run = None
            if report:
                run = await session.scalar(select(ResearchRun).where(ResearchRun.report_id == report.id))
            if run is None:
                run = ResearchRun(
                    report_id=report.id if report else None,
                    query=query,
                    report_type=report_type,
                    report_source="legacy",
                    tone="Objective",
                    request_options={"origin": "legacy_import"},
                    status=status,
                    started_at=utcnow(),
                    completed_at=utcnow() if status == "completed" else None,
                )
                session.add(run)
                await session.flush()
            return {
                "run_id": str(run.id),
                "research_id": report.public_id if report else "",
            }

    async def append_event(self, run_id: str | uuid.UUID, payload: dict[str, Any]) -> int:
        async with transaction() as session:
            event = ResearchEvent(
                run_id=_uuid(run_id),
                event_type=str(payload.get("type") or "event")[:80],
                content_key=(str(payload.get("content"))[:160] if payload.get("content") is not None else None),
                payload=payload,
            )
            session.add(event)
            await session.flush()
            return event.id

    async def list_events(
        self, identifier: str, *, after_id: int = 0, limit: int = 500
    ) -> list[dict[str, Any]] | None:
        async with read_session() as session:
            run, _public_id = await self._run_and_public_id(session, identifier)
            if run is None:
                return None
            rows = list(
                (
                    await session.scalars(
                        select(ResearchEvent)
                        .where(ResearchEvent.run_id == run.id, ResearchEvent.id > after_id)
                        .order_by(ResearchEvent.id)
                        .limit(max(1, min(limit, 2000)))
                    )
                ).all()
            )
            return [
                {**row.payload, "id": row.id, "createdAt": row.created_at.isoformat()}
                for row in rows
            ]

    async def get_run(self, identifier: str) -> dict[str, Any] | None:
        async with read_session() as session:
            run, public_id = await self._run_and_public_id(session, identifier)
            if run is None:
                return None
            return self._run_dict(run, public_id or "")

    async def list_sources(self, identifier: str) -> list[dict[str, Any]] | None:
        async with read_session() as session:
            run, _public_id = await self._run_and_public_id(session, identifier)
            if run is None:
                return None
            rows = list(
                (
                    await session.scalars(
                        select(ResearchSource)
                        .where(ResearchSource.run_id == run.id)
                        .order_by(ResearchSource.created_at, ResearchSource.id)
                    )
                ).all()
            )
            return [
                {
                    "url": row.url,
                    "title": row.title,
                    "raw_content": row.raw_content,
                    "source_type": row.source_type,
                    "metadata": row.metadata_json or {},
                }
                for row in rows
            ]

    async def complete_run(
        self,
        run_id: str | uuid.UUID,
        *,
        answer: str,
        costs: dict[str, Any] | None = None,
        sources: Iterable[dict[str, Any]] | None = None,
    ) -> None:
        now = utcnow()
        async with transaction() as session:
            run = await session.get(ResearchRun, _uuid(run_id), with_for_update=True)
            if run is None:
                raise StorageConflict("Research run does not exist")
            run.status = "completed"
            run.completed_at = now
            run.cost_summary = costs or {}
            report = await session.get(Report, run.report_id, with_for_update=True) if run.report_id else None
            if report:
                payloads = list(
                    (
                        await session.scalars(
                            select(ResearchEvent.payload)
                            .where(ResearchEvent.run_id == run.id)
                            .order_by(ResearchEvent.id)
                        )
                    ).all()
                )
                report.answer = answer
                report.ordered_data = payloads
                report.status = "completed"
                report.updated_at = now
                report.completed_at = now
            if sources:
                for source in sources:
                    url = str(source.get("url") or source.get("source") or "")
                    if not url:
                        continue
                    digest = hashlib.sha256(url.encode("utf-8", errors="replace")).hexdigest()
                    existing = await session.scalar(
                        select(ResearchSource.id).where(
                            ResearchSource.run_id == run.id,
                            ResearchSource.url_hash == digest,
                        )
                    )
                    if existing:
                        continue
                    session.add(
                        ResearchSource(
                            run_id=run.id,
                            url=url,
                            url_hash=digest,
                            title=str(source.get("title") or ""),
                            raw_content=str(
                                source.get("raw_content")
                                or source.get("content")
                                or source.get("body")
                                or ""
                            ),
                            source_type=str(source.get("source_type") or "web"),
                            metadata_json={
                                key: value
                                for key, value in source.items()
                                if key not in {"url", "source", "title", "raw_content", "content", "body"}
                            },
                        )
                    )

    async def fail_run(self, run_id: str | uuid.UUID, error: str) -> None:
        now = utcnow()
        async with transaction() as session:
            run = await session.get(ResearchRun, _uuid(run_id), with_for_update=True)
            if run is None:
                return
            run.status = "failed"
            run.error = error[:10000]
            run.completed_at = now
            if run.report_id:
                report = await session.get(Report, run.report_id, with_for_update=True)
                if report:
                    report.ordered_data = list(
                        (
                            await session.scalars(
                                select(ResearchEvent.payload)
                                .where(ResearchEvent.run_id == run.id)
                                .order_by(ResearchEvent.id)
                            )
                        ).all()
                    )
                    report.status = "failed"
                    report.updated_at = now

    async def put_artifact(
        self,
        *,
        content: bytes,
        filename: str,
        kind: str,
        mime_type: str | None = None,
        report_public_id: str | None = None,
        run_id: str | uuid.UUID | None = None,
        legacy_path: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        if len(content) > self.max_blob_bytes:
            raise ValueError(f"Artifact exceeds {self.max_blob_bytes} bytes")
        safe_filename = os.path.basename(filename)[:255] or "artifact.bin"
        normalized_path = normalize_legacy_path(legacy_path)
        async with transaction() as session:
            report_id = None
            if report_public_id:
                report_id = await session.scalar(
                    select(Report.id).where(Report.public_id == report_public_id)
                )
            if normalized_path:
                existing = await session.scalar(
                    select(Artifact).where(Artifact.legacy_path == normalized_path)
                )
                if existing:
                    existing.content = content
                    existing.byte_size = len(content)
                    existing.sha256 = hashlib.sha256(content).hexdigest()
                    existing.mime_type = mime_type or existing.mime_type
                    existing.kind = kind
                    existing.report_id = report_id or existing.report_id
                    existing.run_id = _uuid(run_id) if run_id else existing.run_id
                    await session.flush()
                    return self._artifact_meta(existing)
            artifact = Artifact(
                report_id=report_id,
                run_id=_uuid(run_id) if run_id else None,
                kind=kind,
                filename=safe_filename,
                mime_type=mime_type
                or mimetypes.guess_type(safe_filename)[0]
                or "application/octet-stream",
                byte_size=len(content),
                sha256=hashlib.sha256(content).hexdigest(),
                content=content,
                legacy_path=normalized_path,
                metadata_json=metadata or {},
            )
            session.add(artifact)
            await session.flush()
            return self._artifact_meta(artifact)

    async def get_artifact(self, artifact_id: str | uuid.UUID) -> Artifact | None:
        async with read_session() as session:
            return await session.get(Artifact, _uuid(artifact_id))

    async def get_artifact_by_legacy_path(self, path: str) -> Artifact | None:
        normalized = normalize_legacy_path(path)
        candidates = [normalized]
        if normalized and not normalized.startswith("outputs/"):
            candidates.append(f"outputs/{normalized}")
        async with read_session() as session:
            return await session.scalar(select(Artifact).where(Artifact.legacy_path.in_(candidates)))

    async def get_report_artifact(self, public_id: str, kind: str) -> Artifact | None:
        async with read_session() as session:
            report_id = await session.scalar(select(Report.id).where(Report.public_id == public_id))
            if report_id is None:
                return None
            return await session.scalar(
                select(Artifact)
                .where(Artifact.report_id == report_id, Artifact.kind == kind)
                .order_by(Artifact.created_at.desc())
                .limit(1)
            )

    async def list_documents(self) -> list[dict[str, Any]]:
        async with read_session() as session:
            documents = list(
                (
                    await session.scalars(select(StoredDocument).order_by(StoredDocument.created_at.desc()))
                ).all()
            )
            return [self._document_dict(item) for item in documents]

    async def save_evidence(self, run_id: str, snapshots: list[dict[str, Any]]) -> None:
        """Persist the exact context snapshots used by one research run."""
        from sqlalchemy.dialects.postgresql import insert

        async with transaction() as session:
            for snapshot in snapshots:
                await session.execute(insert(ResearchEvidence).values(
                    run_id=_uuid(run_id), number=snapshot["number"], snapshot=snapshot,
                ).on_conflict_do_nothing(constraint="uq_evidence_run_number"))

    async def list_evidence(self, identifier: str) -> list[dict[str, Any]] | None:
        """Load snapshots and report original-file availability without re-searching."""
        async with read_session() as session:
            run, _ = await self._run_and_public_id(session, identifier)
            if run is None:
                return None
            snapshots = list((await session.scalars(select(ResearchEvidence.snapshot).where(
                ResearchEvidence.run_id == run.id
            ).order_by(ResearchEvidence.number))).all())
            document_ids = [_uuid(item["document_id"]) for item in snapshots if item.get("document_id")]
            existing = set(str(value) for value in (await session.scalars(
                select(StoredDocument.id).where(StoredDocument.id.in_(document_ids))
            )).all()) if document_ids else set()
            return [{**item, "run_id": str(run.id),
                     "original_available": item.get("source_type") == "web" or item.get("document_id") in existing}
                    for item in snapshots]

    async def get_document(self, document_id: str | uuid.UUID) -> StoredDocument | None:
        try:
            parsed_id = _uuid(document_id)
        except ValueError:
            return None
        async with read_session() as session:
            return await session.get(StoredDocument, parsed_id)

    async def put_document_blob(
        self, *, filename: str, mime_type: str, content: bytes, sha256: str | None = None
    ) -> dict[str, Any]:
        if len(content) > self.max_blob_bytes:
            raise ValueError(f"Upload exceeds {self.max_blob_bytes} bytes")
        safe_filename = os.path.basename(filename)[:255]
        if not safe_filename:
            raise ValueError("A filename is required")
        digest = sha256 or hashlib.sha256(content).hexdigest()
        from sqlalchemy.dialects.postgresql import insert

        async with transaction() as session:
            stmt = insert(StoredDocument).values(
                filename=safe_filename, mime_type=mime_type or "application/octet-stream",
                byte_size=len(content), sha256=digest, content=content, status="processing",
            ).on_conflict_do_nothing(constraint="uq_documents_filename_sha256").returning(StoredDocument.id)
            inserted_id = await session.scalar(stmt)
            document = await session.scalar(select(StoredDocument).where(
                StoredDocument.filename == safe_filename, StoredDocument.sha256 == digest
            ))
            result = self._document_dict(document)
            result["reused"] = inserted_id is None
            return result

    async def delete_document(self, identifier: str) -> bool:
        async with transaction() as session:
            try:
                parsed = uuid.UUID(identifier)
            except ValueError:
                return False
            predicate = StoredDocument.id == parsed
            result = await session.execute(delete(StoredDocument).where(predicate))
            return bool(result.rowcount)

    async def mark_document_failed(self, document_id: str | uuid.UUID, error: str) -> None:
        async with transaction() as session:
            document = await session.get(StoredDocument, _uuid(document_id), with_for_update=True)
            if document:
                document.status = "failed"
                document.error = error[:10000]

    async def mark_document_processing(self, document_id: str | uuid.UUID) -> None:
        async with transaction() as session:
            document = await session.get(StoredDocument, _uuid(document_id), with_for_update=True)
            if document:
                document.status = "processing"
                document.error = None

    async def get_or_create_embedding_profile(
        self, provider: str, model: str, dimensions: int
    ) -> dict[str, Any]:
        async with transaction() as session:
            active = await session.scalar(
                select(EmbeddingProfile).where(EmbeddingProfile.active.is_(True)).with_for_update()
            )
            if active:
                if (active.provider, active.model, active.dimensions) != (
                    provider,
                    model,
                    dimensions,
                ):
                    raise StorageConflict(
                        "Configured embedding model differs from the active database profile; run reindex first"
                    )
                return self._profile_dict(active)
            profile = await session.scalar(
                select(EmbeddingProfile).where(
                    EmbeddingProfile.provider == provider,
                    EmbeddingProfile.model == model,
                    EmbeddingProfile.dimensions == dimensions,
                )
            )
            if profile is None:
                profile = EmbeddingProfile(
                    provider=provider,
                    model=model,
                    dimensions=dimensions,
                    active=True,
                )
                session.add(profile)
            else:
                profile.active = True
            await session.flush()
            return self._profile_dict(profile)

    async def prepare_embedding_profile(
        self, provider: str, model: str, dimensions: int
    ) -> dict[str, Any]:
        """Create an inactive target profile for a maintenance reindex."""

        async with transaction() as session:
            profile = await session.scalar(
                select(EmbeddingProfile).where(
                    EmbeddingProfile.provider == provider,
                    EmbeddingProfile.model == model,
                    EmbeddingProfile.dimensions == dimensions,
                )
            )
            if profile is None:
                profile = EmbeddingProfile(
                    provider=provider,
                    model=model,
                    dimensions=dimensions,
                    active=False,
                )
                session.add(profile)
                await session.flush()
            return self._profile_dict(profile)

    async def activate_embedding_profile(self, profile_id: str | uuid.UUID) -> None:
        async with transaction() as session:
            target = await session.get(EmbeddingProfile, _uuid(profile_id), with_for_update=True)
            if target is None:
                raise StorageConflict("Embedding profile does not exist")
            await session.execute(
                update(EmbeddingProfile).values(active=False)
            )
            target.active = True

    async def replace_document_chunks(
        self,
        document_id: str | uuid.UUID,
        *,
        profile_id: str | uuid.UUID,
        chunks: Sequence[dict[str, Any]],
        embeddings: Sequence[Sequence[float]],
        dimensions: int,
    ) -> None:
        if len(chunks) != len(embeddings):
            raise ValueError("Chunk and embedding counts differ")
        if any(len(vector) != dimensions for vector in embeddings):
            raise ValueError(f"Embedding dimension must be {dimensions}")
        async with transaction() as session:
            document = await session.get(StoredDocument, _uuid(document_id), with_for_update=True)
            if document is None:
                raise StorageConflict("Document does not exist")
            await session.execute(delete(DocumentChunk).where(DocumentChunk.document_id == document.id))
            for index, (chunk, embedding) in enumerate(zip(chunks, embeddings)):
                session.add(
                    DocumentChunk(
                        document_id=document.id,
                        embedding_profile_id=_uuid(profile_id),
                        chunk_index=index,
                        content=str(chunk.get("content") or ""),
                        metadata_json=chunk.get("metadata") or {},
                        embedding=list(embedding),
                    )
                )
            document.embedding_profile_id = _uuid(profile_id)
            document.status = "ready"
            document.error = None

    async def replace_all_document_chunks_and_activate(
        self,
        profile_id: str | uuid.UUID,
        prepared_documents: Sequence[dict[str, Any]],
    ) -> None:
        """Atomically replace every prepared document and switch profiles."""

        target_profile_id = _uuid(profile_id)
        for prepared in prepared_documents:
            dimensions = int(prepared["dimensions"])
            chunks = prepared["chunks"]
            embeddings = prepared["embeddings"]
            if len(chunks) != len(embeddings):
                raise ValueError("Chunk and embedding counts differ")
            if any(len(vector) != dimensions for vector in embeddings):
                raise ValueError(f"Embedding dimension must be {dimensions}")

        async with transaction() as session:
            profile = await session.get(
                EmbeddingProfile, target_profile_id, with_for_update=True
            )
            if profile is None:
                raise StorageConflict("Embedding profile does not exist")
            for prepared in prepared_documents:
                document_id = _uuid(prepared["document_id"])
                document = await session.get(
                    StoredDocument, document_id, with_for_update=True
                )
                if document is None:
                    raise StorageConflict("Document does not exist")
                await session.execute(
                    delete(DocumentChunk).where(DocumentChunk.document_id == document_id)
                )
                for index, (chunk, embedding) in enumerate(
                    zip(prepared["chunks"], prepared["embeddings"])
                ):
                    session.add(
                        DocumentChunk(
                            document_id=document_id,
                            embedding_profile_id=target_profile_id,
                            chunk_index=index,
                            content=str(chunk.get("content") or ""),
                            metadata_json=chunk.get("metadata") or {},
                            embedding=list(embedding),
                        )
                    )
                document.embedding_profile_id = target_profile_id
                document.status = "ready"
                document.error = None
            await session.execute(update(EmbeddingProfile).values(active=False))
            profile.active = True

    async def search_document_chunks(
        self,
        embedding: Sequence[float],
        *,
        limit: int = 8,
        similarity_threshold: float = 0.35,
        document_ids: Sequence[str] | None = None,
    ) -> list[dict[str, Any]]:
        async with read_session() as session:
            profile = await session.scalar(
                select(EmbeddingProfile).where(EmbeddingProfile.active.is_(True))
            )
            if profile is None:
                return []
            if len(embedding) != profile.dimensions:
                raise ValueError(
                    f"Query embedding dimension {len(embedding)} does not match {profile.dimensions}"
                )
            vector_literal = "[" + ",".join(format(float(value), ".12g") for value in embedding) + "]"
            dimensions = int(profile.dimensions)
            stmt = text(
                f"""
                SELECT dc.id AS chunk_id, dc.document_id, dc.content, dc.metadata, d.filename,
                       1 - (dc.embedding::vector({dimensions}) <=> CAST(:embedding AS vector({dimensions}))) AS similarity
                FROM document_chunks dc
                JOIN documents d ON d.id = dc.document_id
                WHERE dc.embedding_profile_id = :profile_id
                  AND d.status = 'ready'
                  AND d.embedding_profile_id = :profile_id
                  AND (:all_documents OR dc.document_id = ANY(CAST(:document_ids AS uuid[])))
                  AND 1 - (dc.embedding::vector({dimensions}) <=> CAST(:embedding AS vector({dimensions}))) >= :threshold
                ORDER BY dc.embedding::vector({dimensions}) <=> CAST(:embedding AS vector({dimensions}))
                LIMIT :limit
                """
            )
            rows = (
                await session.execute(
                    stmt,
                    {
                        "embedding": vector_literal,
                        "profile_id": profile.id,
                        "threshold": similarity_threshold,
                        "all_documents": document_ids is None,
                        "document_ids": [str(_uuid(value)) for value in (document_ids or [])],
                        "limit": max(1, min(limit, 100)),
                    },
                )
            ).mappings()
            return [
                {
                    "document_id": str(row["document_id"]),
                    "chunk_id": str(row["chunk_id"]),
                    "page": (row["metadata"] or {}).get("page_number"),
                    "raw_content": row["content"],
                    "url": row["filename"],
                    "title": row["filename"],
                    "metadata": row["metadata"] or {},
                    "similarity": float(row["similarity"]),
                }
                for row in rows
            ]

    async def configure_vector_index(self) -> str | None:
        async with transaction() as session:
            profile = await session.scalar(
                select(EmbeddingProfile).where(EmbeddingProfile.active.is_(True))
            )
            if profile is None:
                return None
            dimensions = int(profile.dimensions)
            index_name = f"ix_document_chunks_hnsw_{str(profile.id).replace('-', '')[:12]}"
            profile_literal = str(profile.id)
            await session.execute(
                text(
                    f"CREATE INDEX IF NOT EXISTS {index_name} ON document_chunks "
                    f"USING hnsw ((embedding::vector({dimensions})) vector_cosine_ops) "
                    f"WHERE embedding_profile_id = '{profile_literal}'::uuid"
                )
            )
            return index_name

    async def get_migration_import(self, source_path: str, sha256: str) -> dict[str, Any] | None:
        async with read_session() as session:
            row = await session.scalar(
                select(MigrationImport).where(
                    MigrationImport.source_path == source_path,
                    MigrationImport.sha256 == sha256,
                )
            )
            if row is None:
                return None
            return {"status": row.status, "error": row.error, "details": row.details}

    async def record_migration_import(
        self,
        source_path: str,
        sha256: str,
        status: str,
        *,
        error: str | None = None,
        details: dict[str, Any] | None = None,
    ) -> None:
        async with transaction() as session:
            row = await session.scalar(
                select(MigrationImport).where(
                    MigrationImport.source_path == source_path,
                    MigrationImport.sha256 == sha256,
                )
            )
            if row is None:
                row = MigrationImport(source_path=source_path, sha256=sha256, status=status)
                session.add(row)
            row.status = status
            row.error = error
            row.details = details or {}

    async def _load_chat_map(self, session, report_ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, list[dict[str, Any]]]:
        if not report_ids:
            return {}
        rows = list(
            (
                await session.scalars(
                    select(ChatMessage)
                    .where(ChatMessage.report_id.in_(list(report_ids)))
                    .order_by(ChatMessage.report_id, ChatMessage.sequence)
                )
            ).all()
        )
        result: dict[uuid.UUID, list[dict[str, Any]]] = {}
        for row in rows:
            result.setdefault(row.report_id, []).append(self._chat_dict(row))
        return result

    async def _run_by_public_id(self, session, public_id: str) -> ResearchRun | None:
        return await session.scalar(
            select(ResearchRun)
            .join(Report, ResearchRun.report_id == Report.id)
            .where(Report.public_id == public_id)
            .order_by(ResearchRun.started_at.desc(), ResearchRun.created_at.desc())
            .limit(1)
        )

    async def _run_and_public_id(
        self, session, identifier: str
    ) -> tuple[ResearchRun | None, str | None]:
        try:
            run_uuid = uuid.UUID(identifier)
        except ValueError:
            run_uuid = None
        if run_uuid:
            row = (
                await session.execute(
                    select(ResearchRun, Report.public_id)
                    .outerjoin(Report, ResearchRun.report_id == Report.id)
                    .where(ResearchRun.id == run_uuid)
                )
            ).first()
            if row:
                return row[0], row[1]
        run = await self._run_by_public_id(session, identifier)
        return run, identifier if run else None

    def _report_dict(self, report: Report, chats: list[dict[str, Any]]) -> dict[str, Any]:
        return {
            "id": report.public_id,
            "question": report.question,
            "answer": report.answer,
            "orderedData": report.ordered_data or [],
            "chatMessages": chats,
            "timestamp": timestamp_ms(report.updated_at),
            "status": report.status,
            "evidenceUrl": f"/api/reports/{report.public_id}/evidence",
        }

    def _new_chat_message(
        self, report_id: uuid.UUID, message: dict[str, Any], sequence: int
    ) -> ChatMessage:
        return ChatMessage(
            report_id=report_id,
            sequence=sequence,
            role=str(message.get("role") or "user"),
            content=str(message.get("content") or ""),
            metadata_json=message.get("metadata"),
            client_timestamp=message.get("timestamp") if isinstance(message.get("timestamp"), int) else None,
        )

    def _chat_dict(self, row: ChatMessage) -> dict[str, Any]:
        result: dict[str, Any] = {
            "id": str(row.id),
            "role": row.role,
            "content": row.content,
            "timestamp": row.client_timestamp or timestamp_ms(row.created_at),
        }
        if row.metadata_json is not None:
            result["metadata"] = row.metadata_json
        return result

    def _run_dict(self, run: ResearchRun, public_id: str) -> dict[str, Any]:
        return {
            "id": str(run.id),
            "research_id": public_id,
            "query": run.query,
            "report_type": run.report_type,
            "report_source": run.report_source,
            "tone": run.tone,
            "status": run.status,
            "error": run.error,
            "costs": run.cost_summary or {},
            "request_options": run.request_options or {},
            "started_at": run.started_at.isoformat() if run.started_at else None,
            "completed_at": run.completed_at.isoformat() if run.completed_at else None,
        }

    def _artifact_meta(self, artifact: Artifact) -> dict[str, Any]:
        return {
            "id": str(artifact.id),
            "kind": artifact.kind,
            "filename": artifact.filename,
            "mimeType": artifact.mime_type,
            "size": artifact.byte_size,
            "sha256": artifact.sha256,
            "legacyPath": artifact.legacy_path,
            "url": f"/api/artifacts/{artifact.id}",
        }

    def _profile_dict(self, profile: EmbeddingProfile) -> dict[str, Any]:
        return {
            "id": str(profile.id),
            "provider": profile.provider,
            "model": profile.model,
            "dimensions": profile.dimensions,
            "active": profile.active,
        }

    def _document_dict(self, document: StoredDocument) -> dict[str, Any]:
        return {
            "id": str(document.id),
            "filename": document.filename,
            "mimeType": document.mime_type,
            "size": document.byte_size,
            "sha256": document.sha256,
            "status": document.status,
            "error": document.error,
            "createdAt": document.created_at.isoformat() if document.created_at else None,
            "updatedAt": document.updated_at.isoformat() if document.updated_at else None,
            "embedding_profile_id": str(document.embedding_profile_id) if document.embedding_profile_id else None,
        }
