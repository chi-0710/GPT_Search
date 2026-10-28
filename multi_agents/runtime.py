"""Per-run dependencies kept outside serializable LangGraph state."""

import asyncio
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any

active_runtime = ContextVar("research_runtime", default=None)


class DraftLogSink:
    """Forward research progress while keeping chapter drafts out of final output."""

    def __init__(self, target):
        self.target = target

    def __getattr__(self, name):
        return getattr(self.target, name)

    async def send_json(self, payload, **kwargs):
        if payload.get("type") not in {"report", "report_complete"}:
            await self.target.send_json(payload, **kwargs)


async def gather_sections(coroutines):
    """Cancel and drain siblings when a section fails or the run is cancelled."""
    tasks = [asyncio.create_task(coro) for coro in coroutines]
    try:
        return await asyncio.gather(*tasks)
    except BaseException:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        raise


@dataclass
class ResearchRuntime:
    """Shared evidence and dependencies for initial and section research."""

    options: dict[str, Any] = field(default_factory=dict)
    document_retriever: Any = None
    evidence_registry: Any = None
    artifact_sink: Any = None
    sources: list[dict] = field(default_factory=list)
    research_cost: float = 0.0
    model_cost: float = 0.0

    def add_model_cost(self, value: float) -> None:
        self.model_cost += value

    def collect(self, researcher) -> None:
        """Accumulate costs and source metadata after each research attempt."""
        self.research_cost += researcher.get_costs()
        for source in researcher.get_research_sources():
            if isinstance(source, dict) and source not in self.sources:
                self.sources.append(source)
