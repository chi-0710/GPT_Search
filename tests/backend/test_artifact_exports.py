"""Exercise the active PostgreSQL export path, not removed local-file helpers."""

from pathlib import Path
import sys
from types import ModuleType, SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from backend.server import artifact_service
from multi_agents.agents.publisher import PublisherAgent


@pytest.fixture
def export_store():
    return SimpleNamespace(
        put_artifact=AsyncMock(side_effect=lambda **kwargs: {"url": f"/api/artifacts/{kwargs['kind']}"}),
        get_artifact=AsyncMock(return_value=None),
    )


@pytest.fixture
def renderer(monkeypatch):
    calls = []

    def render(file_path, raw=None, css=None, base_url=None):
        calls.append((Path(file_path), raw, Path(css), Path(base_url)))
        Path(file_path).write_bytes(b"pdf-bytes")

    core = ModuleType("md2pdf.core")
    core.md2pdf = render
    package = ModuleType("md2pdf")
    package.core = core
    monkeypatch.setitem(sys.modules, "md2pdf", package)
    monkeypatch.setitem(sys.modules, "md2pdf.core", core)
    return calls


@pytest.mark.asyncio
@pytest.mark.parametrize("stem", ["", "   ", "my-report", "nested/report\\name"])
async def test_export_filename_and_temporary_cleanup(stem, tmp_path, monkeypatch, export_store, renderer):
    monkeypatch.chdir(tmp_path)
    paths = await artifact_service.persist_report_artifacts(
        export_store, report_public_id="report-id", run_id="run-id", markdown="# hi",
        legacy_stem=stem, include_docx=False,
    )
    assert paths["pdf"] == "/api/artifacts/pdf"
    assert paths["json"] == "/api/research-runs/run-id/log"
    assert len(renderer) == 1
    pdf_path, markdown, css, base_url = renderer[0]
    assert pdf_path.name != ".pdf"
    assert pdf_path.parent == base_url
    assert css.is_absolute() and css.is_file()
    assert markdown == "# hi"
    assert not pdf_path.exists()  # Only persisted bytes survive temporary rendering.
    assert not (tmp_path / "outputs").exists()
    artifacts = {call.kwargs["kind"]: call.kwargs for call in export_store.put_artifact.call_args_list}
    assert set(artifacts) == {"md", "pdf"}
    assert artifacts["pdf"]["content"] == b"pdf-bytes"
    assert artifacts["pdf"]["run_id"] == "run-id"
    if stem == "my-report":
        assert artifacts["pdf"]["filename"] == "my-report.pdf"
    elif stem.startswith("nested"):
        assert artifacts["pdf"]["filename"] == "nested-report-name.pdf"
    else:
        assert artifacts["pdf"]["filename"].startswith("report-")


@pytest.mark.asyncio
async def test_pdf_failure_preserves_canonical_markdown(export_store, renderer, monkeypatch):
    def fail(*args, **kwargs):
        raise RuntimeError("renderer unavailable")

    monkeypatch.setattr(sys.modules["md2pdf.core"], "md2pdf", fail)
    paths = await artifact_service.persist_report_artifacts(
        export_store, report_public_id="report-id", run_id="run-id", markdown="# report",
        legacy_stem="report", include_docx=False,
    )
    assert "pdf" not in paths
    assert paths["md"] == "/api/artifacts/md"
    export_store.put_artifact.assert_awaited_once()
    assert export_store.put_artifact.call_args.kwargs["content"] == b"# report"


@pytest.mark.asyncio
async def test_publisher_ignores_legacy_local_export_flags(tmp_path):
    publisher = PublisherAgent(str(tmp_path))
    state = {
        "task": {"publish_formats": {"pdf": True, "docx": True, "markdown": True}},
        "headers": {"title": "报告", "introduction": "简介", "references": "参考资料"},
        "research_data": ["章节正文 [E1]"], "sources": ["证据来源"],
    }
    result = await publisher.run(state)
    assert "章节正文 [E1]" in result["report"]
    assert "证据来源" in result["report"]
    assert list(tmp_path.iterdir()) == []
