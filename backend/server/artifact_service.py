"""Create and persist report artifacts without runtime output directories."""

from __future__ import annotations

import re
import tempfile
import logging
import os
import uuid
from io import BytesIO
from pathlib import Path
from typing import Any
from urllib.parse import quote

import mistune
from docx import Document
from htmldocx import HtmlToDocx

from .postgres_store import PostgresStore


logger = logging.getLogger(__name__)

ARTIFACT_REFERENCE = re.compile(r"(/api/artifacts/([0-9a-fA-F-]{36}))")


async def _materialize_artifact_images(
    store: PostgresStore, markdown: str, directory: Path
) -> str:
    replacements: dict[str, str] = {}
    for full_url, artifact_id in ARTIFACT_REFERENCE.findall(markdown):
        artifact = await store.get_artifact(artifact_id)
        if artifact is None or not artifact.mime_type.startswith("image/"):
            continue
        suffix = Path(artifact.filename).suffix or ".img"
        local_path = directory / f"image-{artifact_id}{suffix}"
        local_path.write_bytes(artifact.content)
        replacements[full_url] = local_path.as_uri()
    for source, target in replacements.items():
        markdown = markdown.replace(source, target)
    return markdown


def _docx_bytes(markdown: str) -> bytes:
    document = Document()
    HtmlToDocx().add_html_to_document(mistune.html(markdown), document)
    output = BytesIO()
    document.save(output)
    return output.getvalue()


async def persist_report_artifacts(
    store: PostgresStore,
    *,
    report_public_id: str,
    run_id: str,
    markdown: str,
    legacy_stem: str,
    include_pdf: bool = True,
    include_docx: bool = True,
) -> dict[str, str]:
    """Save Markdown, DOCX and PDF in PostgreSQL."""

    stem = legacy_stem.strip() or f"report-{uuid.uuid4().hex[:12]}"
    normalized_stem = stem.replace("\\", "-").replace("/", "-")[:120]
    api_origin = os.getenv("PUBLIC_API_URL", "http://localhost:8000").rstrip("/")
    markdown = re.sub(
        r"\]\((/api/research-runs/[0-9a-fA-F-]{36}/evidence/\d+/view)\)",
        lambda match: f"]({api_origin}{match.group(1)})", markdown,
    )
    created: dict[str, str] = {}
    specifications = [
        ("md", f"{normalized_stem}.md", "text/markdown; charset=utf-8", markdown.encode("utf-8")),
    ]

    with tempfile.TemporaryDirectory(prefix="gptr-report-") as temporary_directory:
        temporary_path = Path(temporary_directory)
        materialized_markdown = await _materialize_artifact_images(
            store, markdown, temporary_path
        )
        if include_docx:
            try:
                specifications.append(
                    (
                        "docx",
                        f"{normalized_stem}.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        _docx_bytes(materialized_markdown),
                    )
                )
            except Exception as exc:
                logger.warning("DOCX image rendering failed; retrying without images: %s", exc)
                text_only_markdown = re.sub(r"!\[[^\]]*\]\([^)]+\)", "", markdown)
                try:
                    specifications.append(
                        (
                            "docx",
                            f"{normalized_stem}.docx",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            _docx_bytes(text_only_markdown),
                        )
                    )
                except Exception as retry_exc:
                    logger.exception("DOCX rendering failed: %s", retry_exc)
        if include_pdf:
            pdf_path = temporary_path / f"{normalized_stem}.pdf"
            try:
                from md2pdf.core import md2pdf

                css_path = Path(__file__).resolve().parents[1] / "styles" / "pdf_styles.css"
                md2pdf(
                    str(pdf_path),
                    raw=materialized_markdown,
                    css=str(css_path),
                    base_url=str(temporary_path),
                )
                specifications.append(
                    ("pdf", pdf_path.name, "application/pdf", pdf_path.read_bytes())
                )
            except Exception as exc:
                # A missing optional renderer must not discard the canonical report.
                logger.exception("PDF rendering failed: %s", exc)

    for kind, filename, mime_type, content in specifications:
        legacy_path = f"outputs/{filename}"
        artifact = await store.put_artifact(
            content=content,
            filename=filename,
            kind=kind,
            mime_type=mime_type,
            report_public_id=report_public_id,
            run_id=run_id,
            legacy_path=legacy_path,
        )
        created[kind] = artifact["url"]

    created["json"] = f"/api/research-runs/{run_id}/log"
    return created


def artifact_headers(artifact: Any, *, inline: bool = False) -> dict[str, str]:
    disposition = "inline" if inline else "attachment"
    safe_filename = "".join(
        character if ord(character) < 128 and character not in {'"', "\\"} else "_"
        for character in artifact.filename
    )
    encoded_filename = quote(artifact.filename, safe="")
    return {
        "Content-Disposition": (
            f'{disposition}; filename="{safe_filename}"; '
            f"filename*=UTF-8''{encoded_filename}"
        ),
        "Content-Length": str(artifact.byte_size),
        "ETag": f'"{artifact.sha256}"',
    }
