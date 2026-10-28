"""Exercise the compiled workflow with provider calls replaced at boundaries."""

import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from backend.server import multi_agent_runner as runner
from backend.server.app import ResearchRequest
from multi_agents import main
from multi_agents.agents import editor, researcher, writer, fact_checker, reviser, visualizer
from multi_agents.runtime import gather_sections
from search.utils.evidence import InsufficientEvidenceError
from search.utils.report_modes import normalize_report_mode, LEGACY_REPORT_MODES


@pytest.mark.parametrize("mode", [None, "multi_agents", *sorted(LEGACY_REPORT_MODES)])
def test_public_mode_compatibility(mode):
    assert normalize_report_mode(mode) == "multi_agents"
    request = ResearchRequest(task="topic", report_type=mode, tone="Objective", repo_name="", branch_name="")
    assert request.report_type == "multi_agents"


def test_unknown_mode_is_rejected():
    with pytest.raises(ValueError, match="不支持"):
        normalize_report_mode("typo")
    with pytest.raises(ValueError):
        ResearchRequest(task="topic", report_type="typo", tone="Objective", repo_name="", branch_name="")
    for invalid in [{}, [], 1, ""]:
        with pytest.raises(ValueError):
            normalize_report_mode(invalid)


@pytest.fixture
def workflow(monkeypatch):
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "false")
    monkeypatch.setenv("LANGSMITH_TRACING", "false")
    instances = []
    class FakeResearch:
        def __init__(self, **kwargs):
            self.options = kwargs
            self.cfg = SimpleNamespace()
            self.registry = kwargs["evidence_registry"]
            self.context = ""
            instances.append(self)

        async def conduct_research(self):
            source = self.options["report_source"]
            docs, web = [], []
            if source in {"local", "hybrid"}:
                docs = await self.options["document_retriever"](self.options["query"])
            if source in {"web", "hybrid"}:
                numbers = self.registry.register([{
                    "url": "https://example.org/" + self.options["query"],
                    "content": "verified " + self.options["query"],
                }], "web")
                web = [f"[E{number}]" for number in numbers]
            self.context = self.registry.context(docs, web)
            await asyncio.sleep(0)  # Exercise concurrent section interleaving.
            return self.context

        async def write_report(self):
            citations = " ".join(f"[E{item['number']}]" for item in self.registry.snapshots())
            return f"## {self.options['query']}\nEvidence {citations}"

        def get_costs(self):
            return 0.25

        def get_research_sources(self):
            return []

    monkeypatch.setattr(researcher, "Research", FakeResearch)
    monkeypatch.setattr(main, "open_task", lambda: {
        "max_sections": 2, "model": "mock", "verbose": False,
        "include_human_feedback": False, "follow_guidelines": True,
        "max_draft_revisions": 1, "max_fact_check_revisions": 1,
    })
    monkeypatch.setattr(editor, "call_model", AsyncMock(return_value={
        "title": "研究", "date": "2026-10-18", "sections": ["first", "second"],
    }))
    async def write_layout(prompt, *args, **kwargs):
        if "标题数据" in str(prompt):
            return {"title": "研究", "introduction": "引言", "conclusion": "结论",
                    "date": "日期", "references": "参考文献", "table_of_contents": "目录"}
        return {"introduction": "Introduction [E1]", "conclusion": "Conclusion [E1]",
                "table_of_contents": "- first\n- second", "sources": ["[E1]"]}
    monkeypatch.setattr(writer, "call_model", write_layout)
    from multi_agents.agents import reviewer
    monkeypatch.setattr(reviewer, "call_model", AsyncMock(return_value="None"))
    monkeypatch.setattr(fact_checker, "call_model", AsyncMock(return_value="None"))
    monkeypatch.setattr(visualizer, "call_model", AsyncMock(return_value="None"))
    return instances


@pytest.mark.asyncio
@pytest.mark.parametrize("source", ["web", "local", "hybrid"])
async def test_compiled_workflow_sources_options_and_citations(workflow, source):
    retriever = AsyncMock(return_value=[{
        "document_id": "allowed-file", "chunk_id": "chunk", "page": 2,
        "content": "file evidence", "title": "paper.pdf",
    }])
    result = await runner.run_multi_agent_task(
        "topic", report_type="detailed_report", report_source=source,
        document_retriever=retriever, run_id="test-run", query_domains=["example.org"],
        source_urls=["https://example.org"], max_search_results=7,
        mcp_configs=[{"name": "mock"}], mcp_strategy="fast",
    )
    assert len(workflow) == 3
    assert len({id(item.registry) for item in workflow}) == 1
    for item in workflow:
        assert item.options["report_source"] == source
        assert item.options["document_retriever"] is retriever
        assert item.options["query_domains"] == ["example.org"]
        assert item.options["source_urls"] == ["https://example.org"]
        assert item.options["mcp_configs"] == [{"name": "mock"}]
        assert item.cfg.max_search_results_per_query == 7
    assert result.costs["total"] == 0.75
    assert result.evidence
    assert len({item["number"] for item in result.evidence}) == len(result.evidence)
    assert "[E1]" not in result.report
    assert "first" in result.report and "second" in result.report
    if source == "web":
        retriever.assert_not_called()
        assert all(item["source_type"] == "web" for item in result.evidence)
    else:
        assert retriever.await_count == 3
        assert "/evidence/" in result.report
        assert {item["document_id"] for item in result.evidence if item["source_type"] == "document"} == {"allowed-file"}


@pytest.mark.asyncio
async def test_fact_check_repairs_chapters(workflow, monkeypatch):
    monkeypatch.setattr(fact_checker, "call_model", AsyncMock(side_effect=["correct chapter", "None"]))
    repair = AsyncMock(return_value={"draft": {"section": "corrected [E1]"}, "revision_notes": "fixed"})
    monkeypatch.setattr(reviser, "call_model", repair)
    result = await runner.run_multi_agent_task("topic")
    assert repair.await_count == 2
    assert "corrected" in result.report


@pytest.mark.asyncio
async def test_fact_check_limit_fails_instead_of_accepting(workflow, monkeypatch):
    from multi_agents.agents.fact_review import MaxFactCheckRevisionsExceededError
    monkeypatch.setattr(fact_checker, "call_model", AsyncMock(return_value="still wrong"))
    monkeypatch.setattr(reviser, "call_model", AsyncMock(return_value={"draft": {"section": "draft [E1]"}}))
    with pytest.raises(MaxFactCheckRevisionsExceededError):
        await runner.run_multi_agent_task("topic")


@pytest.mark.asyncio
async def test_empty_initial_research_aborts(workflow):
    with pytest.raises(InsufficientEvidenceError):
        await runner.run_multi_agent_task("topic", report_source="local", document_retriever=AsyncMock(return_value=[]))
    assert len(workflow) == 1


@pytest.mark.asyncio
async def test_empty_chapters_fail_or_are_marked(workflow):
    async def retrieve(query):
        return [{"document_id": "file", "content": "evidence", "title": "file"}] if query in {"topic", "first"} else []
    result = await runner.run_multi_agent_task("topic", report_source="local", document_retriever=retrieve)
    assert "资料不足" in result.report
    async def initial_only(query):
        return await retrieve("topic") if query == "topic" else []
    with pytest.raises(ValueError, match="所有章节"):
        await runner.run_multi_agent_task("topic", report_source="local", document_retriever=initial_only)


@pytest.mark.asyncio
@pytest.mark.parametrize("cancel", [False, True])
async def test_failed_or_cancelled_section_drains_siblings(cancel):
    started, finished = asyncio.Event(), asyncio.Event()
    async def pending():
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            finished.set()
    async def failing():
        await started.wait()
        raise RuntimeError("model failed")
    task = asyncio.create_task(gather_sections([pending()] if cancel else [pending(), failing()]))
    await started.wait()
    if cancel:
        task.cancel()
    with pytest.raises(asyncio.CancelledError if cancel else RuntimeError):
        await task
    assert finished.is_set()


@pytest.mark.asyncio
async def test_primary_import_error_has_no_fallback(monkeypatch):
    def broken():
        raise ImportError("primary broken")
    monkeypatch.setattr(runner, "_resolve_run_research_task", broken)
    with pytest.raises(ImportError, match="primary broken"):
        await runner.run_multi_agent_task("topic")


@pytest.mark.asyncio
async def test_draft_review_limit_is_connected(workflow, monkeypatch):
    from multi_agents.agents import reviewer
    from multi_agents.agents.draft_review import MaxDraftRevisionsExceededError
    monkeypatch.setattr(reviewer, "call_model", AsyncMock(return_value="revise"))
    monkeypatch.setattr(reviser, "call_model", AsyncMock(return_value={"draft": {"section": "still wrong [E1]"}}))
    with pytest.raises(MaxDraftRevisionsExceededError):
        await runner.run_multi_agent_task("topic")


@pytest.mark.asyncio
async def test_human_replanning_uses_actual_counter(workflow, monkeypatch):
    from multi_agents.agents.human import HumanAgent
    from multi_agents.agents.plan_review import MaxPlanRevisionsExceededError
    calls = 0
    async def reject(self, state):
        nonlocal calls
        calls += 1
        return {"human_feedback": "revise", "plan_revision_count": state.get("plan_revision_count", 0) + 1}
    monkeypatch.setattr(HumanAgent, "review_plan", reject)
    with pytest.raises(MaxPlanRevisionsExceededError):
        await runner.run_multi_agent_task("topic", task_options={"max_plan_revisions": 1})
    assert calls == 2
    assert len(workflow) == 1


@pytest.mark.asyncio
async def test_draft_stream_does_not_replace_final_report():
    from multi_agents.runtime import DraftLogSink
    target = SimpleNamespace(send_json=AsyncMock())
    sink = DraftLogSink(target)
    await sink.send_json({"type": "report", "output": "draft"})
    await sink.send_json({"type": "report_complete", "output": "draft"})
    event = {"type": "logs", "output": "working"}
    await sink.send_json(event)
    target.send_json.assert_awaited_once_with(event)


@pytest.mark.asyncio
async def test_export_flags_do_not_require_native_pdf_renderer(monkeypatch):
    from backend.server import artifact_service
    store = SimpleNamespace(put_artifact=AsyncMock(return_value={"url": "/saved"}))
    docx = __import__("unittest.mock", fromlist=["Mock"]).Mock(side_effect=AssertionError("disabled"))
    monkeypatch.setattr(artifact_service, "_docx_bytes", docx)
    paths = await artifact_service.persist_report_artifacts(
        store, report_public_id="report", run_id="run", markdown="# report", legacy_stem="report",
        include_pdf=False, include_docx=False,
    )
    assert paths == {"md": "/saved", "json": "/api/research-runs/run/log"}
    assert store.put_artifact.call_args.kwargs["content"] == b"# report"


@pytest.mark.asyncio
async def test_model_helper_uses_run_config_evidence_and_costs(monkeypatch):
    from multi_agents.agents.utils import llms
    from multi_agents.runtime import ResearchRuntime, active_runtime
    from search.utils.evidence import EvidenceRegistry
    registry = EvidenceRegistry("run")
    registry.context([{"document_id": "file", "content": "verified fact", "title": "source"}], [])
    runtime = ResearchRuntime(options={"config_path": "custom"}, evidence_registry=registry)
    config = __import__("unittest.mock", fromlist=["Mock"]).Mock(return_value=SimpleNamespace(
        strategic_llm_provider="configured", strategic_llm_model="strategic",
        smart_llm_provider="other", smart_llm_model="smart", llm_kwargs={},
    ))
    async def complete(**kwargs):
        assert kwargs["model"] == "strategic"
        assert kwargs["llm_provider"] == "configured"
        assert "verified fact" in str(kwargs["messages"])
        kwargs["cost_callback"](0.5)
        return '{"answer": "result"}'
    monkeypatch.setattr(llms, "Config", config)
    monkeypatch.setattr(llms, "create_chat_completion", complete)
    token = active_runtime.set(runtime)
    try:
        assert await llms.call_model([], None, "json") == {"answer": "result"}
        config.assert_called_once_with("custom")
        assert runtime.model_cost == 0.5
        monkeypatch.setattr(llms, "create_chat_completion", AsyncMock(side_effect=RuntimeError("model failed")))
        with pytest.raises(RuntimeError, match="model failed"):
            await llms.call_model([], None)
    finally:
        active_runtime.reset(token)
