"""The single execution adapter for HTTP, WebSocket and CLI research."""

from dataclasses import dataclass, field
import tempfile
import uuid

from multi_agents.runtime import ResearchRuntime
from search.utils.evidence import EvidenceRegistry
from search.utils.report_modes import normalize_report_mode


@dataclass
class ResearchResult:
    """Stable result independent of internal researcher instances."""
    report: str
    sources: list[dict] = field(default_factory=list)
    costs: dict = field(default_factory=dict)
    evidence: list[dict] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def _resolve_run_research_task():
    from multi_agents.main import run_research_task
    return run_research_task


async def run_multi_agent_task(
    query, *, report_type=None, report_source="web", websocket=None,
    stream_output=None, tone=None, headers=None, document_retriever=None,
    artifact_sink=None, run_id=None, config_path="", query_domains=None,
    source_urls=None, document_urls=None, mcp_configs=None, mcp_strategy=None,
    max_search_results=None, task_options=None,
) -> ResearchResult:
    """Run the primary workflow using shared, run-scoped evidence."""
    normalize_report_mode(report_type)
    if report_source not in {"web", "local", "hybrid"}:
        raise ValueError(f"不支持的资料来源：{report_source}")
    if report_source in {"local", "hybrid"} and document_retriever is None:
        raise ValueError("文件研究必须配置资料库检索器")
    registry = EvidenceRegistry(run_id or str(uuid.uuid4()))
    options = {
        "config_path": config_path, "query_domains": query_domains or [],
        "source_urls": source_urls or [], "document_urls": document_urls or [],
        "max_search_results": max_search_results,
    }
    if mcp_configs:
        options.update(mcp_configs=mcp_configs, mcp_strategy=mcp_strategy or "fast")
    runtime = ResearchRuntime(options, document_retriever, registry, artifact_sink)
    with tempfile.TemporaryDirectory(prefix="researchrunner-") as output_dir:
        state = await _resolve_run_research_task()(
            query=query, report_source=report_source, websocket=websocket,
            stream_output=stream_output, tone=tone, headers=headers,
            runtime=runtime, output_dir=output_dir, task_options=task_options,
        )
    if not state.get("report"):
        raise ValueError("多智能体未生成报告")
    report = registry.render_report(state["report"])
    return ResearchResult(
        report=report, sources=registry.snapshots(),
        costs={"total": runtime.research_cost + runtime.model_cost,
               "research": runtime.research_cost, "agents": runtime.model_cost},
        evidence=registry.snapshots(), warnings=list(dict.fromkeys(registry.warnings)),
    )
