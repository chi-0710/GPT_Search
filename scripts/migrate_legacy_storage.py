"""Idempotently import reports.json, outputs and my-docs into PostgreSQL."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import mimetypes
import sys
from pathlib import Path
from typing import Any
from urllib.parse import unquote

REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def load_reports(path: Path) -> dict[str, dict[str, Any]]:
    if not path.exists():
        return {}
    value = json.loads(path.read_text(encoding="utf-8"))
    return value if isinstance(value, dict) else {}


def referenced_outputs(
    reports: dict[str, dict[str, Any]],
) -> dict[str, str]:
    result: dict[str, str] = {}
    for public_id, report in reports.items():
        for event in report.get("orderedData") or []:
            if not isinstance(event, dict) or event.get("type") != "path":
                continue
            output = event.get("output") or {}
            if not isinstance(output, dict):
                continue
            for value in output.values():
                if isinstance(value, str) and value:
                    normalized = normalize_path(unquote(value))
                    if normalized:
                        result[normalized] = public_id
    return result


def normalize_path(value: str) -> str:
    normalized = value.replace("\\", "/").strip().lstrip("/")
    while "//" in normalized:
        normalized = normalized.replace("//", "/")
    outputs_marker = "/outputs/"
    marker_position = normalized.lower().rfind(outputs_marker)
    if marker_position >= 0:
        normalized = normalized[marker_position + 1 :]
    return normalized


def orphan_group_key(relative_path: str) -> str:
    """Group format variants of the same legacy report into one archive run."""

    normalized = normalize_path(relative_path)
    return str(Path(normalized).with_suffix("")).replace("\\", "/")


async def import_reports(
    store: PostgresStore,
    path: Path,
    reports: dict[str, dict[str, Any]],
) -> dict[str, str]:
    run_ids: dict[str, str] = {}
    source_hash = digest(path) if path.exists() else ""
    already = (
        await store.get_migration_import(str(path.resolve()), source_hash)
        if path.exists()
        else None
    )
    for public_id, report in reports.items():
        if not already:
            await store.upsert_report(public_id, report)
        run = await store.ensure_imported_run(
            public_id=public_id,
            query=str(report.get("question") or "Legacy report"),
        )
        run_ids[public_id] = run["run_id"]
        if not already:
            for event in report.get("orderedData") or []:
                if isinstance(event, dict):
                    await store.append_event(run["run_id"], event)
    if path.exists() and not already:
        await store.record_migration_import(
            str(path.resolve()), source_hash, "imported", details={"reports": len(reports)}
        )
    return run_ids


async def import_outputs(
    store: PostgresStore,
    output_root: Path,
    references: dict[str, str],
    run_ids: dict[str, str],
) -> list[str]:
    orphans: list[str] = []
    orphan_runs: dict[str, str] = {}
    if not output_root.exists():
        return orphans
    for path in sorted(item for item in output_root.rglob("*") if item.is_file()):
        relative = normalize_path(str(path.relative_to(output_root.parent))) or path.name
        source_path = str(path.resolve())
        source_hash = digest(path)
        if await store.get_migration_import(source_path, source_hash):
            continue
        public_id = references.get(relative)
        run_id = run_ids.get(public_id or "")
        if not run_id:
            group_key = orphan_group_key(relative)
            run_id = orphan_runs.get(group_key)
            if not run_id:
                orphan_run = await store.ensure_imported_run(
                    public_id=None, query=f"Orphaned legacy output: {group_key}"
                )
                run_id = orphan_run["run_id"]
                orphan_runs[group_key] = run_id
            orphans.append(relative)

        if path.suffix.lower() == ".json":
            try:
                log = json.loads(path.read_text(encoding="utf-8"))
                for item in log.get("events") or []:
                    payload = item.get("data") if isinstance(item, dict) else None
                    if isinstance(payload, dict):
                        await store.append_event(run_id, payload)
            except (UnicodeDecodeError, json.JSONDecodeError):
                pass

        kind = {
            ".md": "md",
            ".pdf": "pdf",
            ".docx": "docx",
            ".json": "log",
            ".png": "image",
            ".jpg": "image",
            ".jpeg": "image",
            ".webp": "image",
        }.get(path.suffix.lower(), "legacy")
        await store.put_artifact(
            content=path.read_bytes(),
            filename=path.name,
            kind=kind,
            mime_type=mimetypes.guess_type(path.name)[0],
            report_public_id=public_id,
            run_id=run_id,
            legacy_path=relative,
            metadata={"imported_from": source_path},
        )
        await store.record_migration_import(
            source_path, source_hash, "imported", details={"legacy_path": relative}
        )
    return orphans


async def import_documents(store: PostgresStore, document_root: Path) -> int:
    from backend.server.document_service import index_document

    imported = 0
    if not document_root.exists():
        return imported
    for path in sorted(item for item in document_root.rglob("*") if item.is_file()):
        source_path = str(path.resolve())
        source_hash = digest(path)
        if await store.get_migration_import(source_path, source_hash):
            continue
        try:
            document = await store.put_document_blob(
                filename=path.name,
                mime_type=mimetypes.guess_type(path.name)[0] or "application/octet-stream",
                content=path.read_bytes(),
            )
            await index_document(store, document["id"])
            await store.record_migration_import(
                source_path, source_hash, "imported", details={"document_id": document["id"]}
            )
            imported += 1
        except Exception as exc:
            await store.record_migration_import(
                source_path, source_hash, "failed", error=str(exc)
            )
    return imported


async def run(root: Path, dry_run: bool) -> None:
    reports_path = root / "data" / "reports.json"
    output_root = root / "outputs"
    document_root = root / "my-docs"
    reports = load_reports(reports_path)
    references = referenced_outputs(reports)
    output_files = list(output_root.rglob("*")) if output_root.exists() else []
    document_files = list(document_root.rglob("*")) if document_root.exists() else []
    summary = {
        "reports": len(reports),
        "referenced_outputs": len(references),
        "output_files": sum(item.is_file() for item in output_files),
        "documents": sum(item.is_file() for item in document_files),
        "dry_run": dry_run,
    }
    if dry_run:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return

    from backend.server.database import dispose_engine
    from backend.server.postgres_store import PostgresStore

    store = PostgresStore()
    run_ids = await import_reports(store, reports_path, reports)
    orphans = await import_outputs(store, output_root, references, run_ids)
    imported_documents = await import_documents(store, document_root)
    summary.update({"orphan_outputs": orphans, "imported_documents": imported_documents})
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    await dispose_engine()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    asyncio.run(run(args.root.resolve(), args.dry_run))


if __name__ == "__main__":
    main()
