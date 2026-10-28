from types import SimpleNamespace
import pytest

from backend.server import app as server_app


@pytest.mark.asyncio
async def test_multi_agent_rest_report_exports_the_complete_report(monkeypatch):
    report = "# Complete multi-agent report\n\nAll sections are present."
    persisted = {}

    async def fake_run_agent(**kwargs):
        assert kwargs["report_type"] == "multi_agents"
        return SimpleNamespace(report=report, sources=[], costs={})

    async def fake_append_event(*args, **kwargs):
        return 1

    async def fake_complete_run(run_id, **kwargs):
        persisted["completed"] = (run_id, kwargs["answer"])

    async def fake_fail_run(*args, **kwargs):
        pytest.fail("the successful run must not be marked failed")

    async def fake_persist(_store, **kwargs):
        persisted["artifact"] = kwargs["markdown"]
        return {
            "docx": "outputs/research-id.docx",
            "pdf": "outputs/research-id.pdf",
            "md": "outputs/research-id.md",
        }

    monkeypatch.setattr(server_app, "run_agent", fake_run_agent)
    monkeypatch.setattr(server_app.report_store, "append_event", fake_append_event)
    monkeypatch.setattr(server_app.report_store, "complete_run", fake_complete_run)
    monkeypatch.setattr(server_app.report_store, "fail_run", fake_fail_run)
    monkeypatch.setattr(server_app, "persist_report_artifacts", fake_persist)

    request = server_app.ResearchRequest(
        task="Test the multi-agent REST response",
        report_type="multi_agents",
        report_source="web",
        tone="Objective",
        repo_name="",
        branch_name="",
        generate_in_background=False,
    )

    response = await server_app.write_report(
        request, {"research_id": "research-id", "run_id": "run-id"}
    )

    assert response["report"] == report
    assert persisted == {
        "completed": ("run-id", report),
        "artifact": report,
    }
    assert response["docx_path"] == "outputs/research-id.docx"
