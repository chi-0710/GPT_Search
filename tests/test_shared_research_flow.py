"""Exercise orchestration and HTTP handler functions without starting the API."""

import ast
import asyncio
from html import escape
import json
import logging
from pathlib import Path
import sys
from types import SimpleNamespace
from typing import Dict, Any
from unittest.mock import AsyncMock, MagicMock, patch
from urllib.parse import quote, urlsplit
from io import BytesIO

import pytest


class HTTPException(Exception):
    """Framework boundary double; this suite exercises handler bodies only."""
    def __init__(self, status_code, detail):
        self.status_code = status_code
        super().__init__(detail)


class HTMLResponse:
    def __init__(self, content, **kwargs):
        self.body = content.encode()


class RedirectResponse:
    """HTTP response boundary double, matching the handler-only suite."""

    def __init__(self, url, status_code):
        self.status_code = status_code
        self.headers = {"location": url}

from tests.test_shared_library import A, B, PROFILE, RUN, ROOT, EVIDENCE, SERVICE, module, sample_document
from tests.test_research_domain_filter import researcher_module


def units(path, names, namespace):
    """Compile unchanged function/class bodies while bypassing unrelated app imports."""
    tree = ast.parse((ROOT / path).read_text(encoding="utf-8"))
    selected = [node for node in tree.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) and node.name in names]
    assert {node.name for node in selected} == set(names)
    for node in selected:
        node.decorator_list = []
    future = ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)
    code = ast.fix_missing_locations(ast.Module(body=[future, *selected], type_ignores=[]))
    exec(compile(code, str(ROOT / path), "exec"), namespace)
    return namespace


@pytest.mark.parametrize("local,web,expect_error", [
    (True, False, False), (False, True, False), (True, True, False),
    (False, False, True), ("failure", True, False), (True, "failure", False),
])
def test_joint_research_scenarios(local, web, expect_error):
    registry = EVIDENCE.EvidenceRegistry(RUN)
    def fake_web():
        ids = registry.register([{"url": "https://example.org", "raw_content": "online 21 days"}], "web")
        return [f"[E{ids[0]}]"]
    retriever = AsyncMock(return_value=[sample_document()] if local is True else [])
    if local == "failure":
        retriever.side_effect = RuntimeError("documents unavailable")
    researcher = SimpleNamespace(report_source="hybrid", source_urls=[], query="alpha", query_domains=[],
        kwargs={"document_retriever": retriever}, verbose=True, websocket=None)
    conductor = researcher_module.ResearchConductor(researcher)
    conductor._get_context_by_web_search = AsyncMock(return_value=fake_web() if web is True else [])
    if web == "failure":
        conductor._get_context_by_web_search.side_effect = RuntimeError("web unavailable")
    with patch.object(researcher_module, "stream_output", new_callable=AsyncMock) as output:
        if expect_error:
            with pytest.raises(ValueError, match="资料不足"):
                asyncio.run(conductor._conduct_evidence_research(registry))
        else:
            context = asyncio.run(conductor._conduct_evidence_research(registry))
            assert ("17 days" in context) is (local is True)
            assert ("online 21" in context) is (web is True)
        assert output.await_count == 2


def test_local_research_never_calls_web():
    researcher = SimpleNamespace(report_source="local", source_urls=["https://ignored.org"], query="alpha",
        kwargs={"document_retriever": AsyncMock(return_value=[sample_document()])}, verbose=False)
    conductor = researcher_module.ResearchConductor(researcher)
    conductor._get_context_by_web_search = AsyncMock(side_effect=AssertionError("web must not run"))
    conductor._get_context_by_urls = AsyncMock(side_effect=AssertionError("URL scraping must not run"))
    asyncio.run(conductor._conduct_evidence_research(EVIDENCE.EvidenceRegistry(RUN)))
    conductor._get_context_by_web_search.assert_not_called()
    conductor._get_context_by_urls.assert_not_called()


def test_rest_freezes_document_scope_before_scheduling():
    request_options = {"task": "alpha", "report_type": "research_report", "report_source": "hybrid", "tone": "Objective",
                       "document_scope": "selected", "document_ids": [A]}
    request = SimpleNamespace(**request_options, generate_in_background=True, model_dump=lambda: request_options.copy())
    store = SimpleNamespace(create_run=AsyncMock(return_value={"run_id": RUN, "research_id": "report"}))
    background = SimpleNamespace(add_task=MagicMock())
    namespace = units("backend/server/app.py", ["generate_report"], {
        "report_store": store, "resolve_document_scope": AsyncMock(return_value=[A]),
        "HTTPException": HTTPException, "write_report": AsyncMock(),
    })
    result = asyncio.run(namespace["generate_report"](request, background))
    assert result["resolved_document_ids"] == [A]
    assert store.create_run.call_args.kwargs["request_options"]["resolved_document_ids"] == [A]
    assert background.add_task.call_args.kwargs["run"]["resolved_document_ids"] == [A]


def test_rest_invalid_scope_does_not_create_run():
    store = SimpleNamespace(create_run=AsyncMock())
    namespace = units("backend/server/app.py", ["generate_report"], {
        "report_store": store, "resolve_document_scope": AsyncMock(side_effect=ValueError("not ready")),
        "HTTPException": HTTPException,
    })
    with pytest.raises(HTTPException) as error:
        asyncio.run(namespace["generate_report"](SimpleNamespace(model_dump=lambda: {}), None))
    assert error.value.status_code == 422
    store.create_run.assert_not_called()


def test_websocket_freezes_same_scope_and_passes_to_runner():
    store = SimpleNamespace(create_run=AsyncMock(return_value={"run_id": RUN, "research_id": "report"}),
        append_event=AsyncMock(), complete_run=AsyncMock(), fail_run=AsyncMock())
    manager = SimpleNamespace(store=store, start_streaming=AsyncMock(return_value=SimpleNamespace(report="report", sources=[], costs={})))
    namespace = units("backend/server/server_utils.py", ["handle_start_command", "extract_command_data", "CustomLogsHandler"], {
        "__name__": "backend.server.server_utils", "__package__": "backend.server",
        "json": json, "asyncio": asyncio, "logger": logging.getLogger("test"),
        "normalize_report_mode": lambda value: "multi_agents",
        "generate_report_files": AsyncMock(return_value={}),
    })
    resolver = AsyncMock(return_value=[A])
    with patch.dict(sys.modules, {"backend.server.document_service": module("backend.server.document_service", resolve_document_scope=resolver)}):
        asyncio.run(namespace["handle_start_command"](SimpleNamespace(send_json=AsyncMock()),
            'start ' + json.dumps({"task": "alpha", "report_type": "detailed_report", "report_source": "hybrid",
                                   "document_scope": "selected", "document_ids": [A]}), manager))
    assert store.create_run.call_args.kwargs["request_options"]["resolved_document_ids"] == [A]
    assert manager.start_streaming.call_args.kwargs["document_ids"] == [A]
    store.complete_run.assert_awaited_once()




def test_evidence_view_escapes_content_and_handles_deleted_original():
    item = {"number": 1, "source_type": "document", "document_id": A, "title": "paper_a.pdf",
            "page": 2, "content": "<script>bad()</script>", "original_available": True}
    namespace = units("backend/server/app.py", ["evidence_view"], {
        "evidence_item": AsyncMock(return_value=item), "HTMLResponse": HTMLResponse, "escape": escape, "quote": quote,
    })
    rendered = asyncio.run(namespace["evidence_view"](RUN, 1)).body.decode()
    assert "#page=2" in rendered
    assert "&lt;script&gt;" in rendered and "<script>bad()" not in rendered
    item["original_available"] = False
    rendered = asyncio.run(namespace["evidence_view"](RUN, 1)).body.decode()
    assert "原文件已删除" in rendered and "<iframe" not in rendered
    assert "&lt;script&gt;" in rendered


@pytest.mark.parametrize("url", ["https://example.org/article?q=1&x=%20#part", "http://example.org/a_(b)"])
def test_legacy_web_evidence_redirects_to_original(url):
    namespace = units("backend/server/app.py", ["evidence_view"], {
        "evidence_item": AsyncMock(return_value={"source_type": "web", "url": url}),
        "urlsplit": urlsplit, "RedirectResponse": RedirectResponse, "HTTPException": HTTPException,
    })
    response = asyncio.run(namespace["evidence_view"](RUN, 1))
    assert response.status_code == 302
    assert response.headers["location"] == url


@pytest.mark.parametrize("url", [None, "", "javascript:alert(1)", "file:///tmp/a", "//example.org", "https://", "https://[broken", "https://example.org/a\nb", "https://bad host/a"])
def test_legacy_web_evidence_rejects_invalid_original(url):
    namespace = units("backend/server/app.py", ["evidence_view"], {
        "evidence_item": AsyncMock(return_value={"source_type": "web", "url": url}),
        "urlsplit": urlsplit, "RedirectResponse": RedirectResponse, "HTTPException": HTTPException,
    })
    with pytest.raises(HTTPException) as error:
        asyncio.run(namespace["evidence_view"](RUN, 1))
    assert error.value.status_code == 422


def test_mcp_evidence_view_keeps_snapshot():
    item = {"source_type": "mcp", "title": "Tool result", "page": None, "content": "Saved evidence"}
    namespace = units("backend/server/app.py", ["evidence_view"], {
        "evidence_item": AsyncMock(return_value=item), "HTMLResponse": HTMLResponse, "escape": escape,
    })
    response = asyncio.run(namespace["evidence_view"](RUN, 1))
    assert "Saved evidence" in response.body.decode()
    assert "工具检索结果" in response.body.decode()


def test_unknown_linked_citation_cannot_override_server_location():
    registry = EVIDENCE.EvidenceRegistry(RUN)
    registry.context([sample_document()], [])
    report = registry.render_report("known [E1](file:///bad) unknown [E99](https://fake.org)")
    assert "file:///" not in report and "fake.org" not in report
    assert f"/api/research-runs/{RUN}/evidence/1/view" in report
    assert "引用不可核验" in report


def test_run_agent_persists_only_used_evidence_and_finalizes_links():
    store = SimpleNamespace(save_evidence=AsyncMock())
    handler = SimpleNamespace(run_id=RUN, store=store, send_json=AsyncMock())
    namespace = units("backend/server/websocket_manager.py", ["run_agent"], {
        "run_multi_agent_task": AsyncMock(return_value=SimpleNamespace(report="result", evidence=[sample_document()], warnings=[], sources=[], costs={})),
        "normalize_report_mode": lambda value: "multi_agents",
        "stream_output": AsyncMock(),
    })
    result = asyncio.run(namespace["run_agent"]("alpha", "research_report", "hybrid", [], [], None, None, logs_handler=handler))
    snapshots = store.save_evidence.call_args.args[1]
    assert len(snapshots) == 1 and snapshots[0]["document_id"] == A
    assert result.report == "result"
    assert handler.send_json.call_args.args[0] == {"type": "report_complete", "output": result.report}


@pytest.mark.parametrize("filename,content,expected", [
    ("paper.pdf", b"%PDF-test", "inline"), ("paper.pdf", b"<html>bad</html>", "attachment"),
    ("notes.html", b"<html>bad</html>", "attachment"),
])
def test_original_download_only_inlines_real_pdf(filename, content, expected):
    namespace = units("backend/server/app.py", ["document_content"], {
        "report_store": SimpleNamespace(get_document=AsyncMock(return_value=SimpleNamespace(filename=filename, content=content))),
        "HTTPException": HTTPException, "BytesIO": BytesIO, "quote": quote,
        "StreamingResponse": lambda stream, **kwargs: SimpleNamespace(content=stream.read(), **kwargs),
    })
    response = asyncio.run(namespace["document_content"](A, False))
    assert response.headers["Content-Disposition"].startswith(expected)
    assert response.headers["X-Content-Type-Options"] == "nosniff"


def test_web_compression_to_context_manager_registers_evidence():
    compressor_namespace = units("search/context/compression.py", ["ContextCompressor"], {
        "Document": lambda **kwargs: SimpleNamespace(**kwargs),
        "os": __import__("os"), "PromptFamily": SimpleNamespace,
    })
    manager_namespace = units("search/skills/context_manager.py", ["ContextManager"], {
        "ContextCompressor": compressor_namespace["ContextCompressor"],
    })
    registry = EVIDENCE.EvidenceRegistry(RUN)
    researcher = SimpleNamespace(verbose=False, memory=SimpleNamespace(get_embeddings=lambda: None),
        cfg=SimpleNamespace(similarity_threshold=0.35), prompt_family=None, add_costs=lambda _: None,
        kwargs={"evidence_registry": registry})
    manager = manager_namespace["ContextManager"](researcher)
    tokens = asyncio.run(manager.get_similar_content_by_query("alpha", [
        {"url": "https://example.org/alpha", "raw_content": "21 day online evidence"}]))
    assert tokens == "[E1]"
    assert "21 day online evidence" in registry.context([], tokens)
    assert registry.snapshots()[0]["source_type"] == "web"

