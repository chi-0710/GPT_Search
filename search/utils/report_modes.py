"""Public execution mode compatibility, separate from internal draft formats."""

LEGACY_REPORT_MODES = frozenset({
    "research_report", "detailed_report", "deep", "resource_report",
    "outline_report", "custom_report", "subtopic_report",
})


def normalize_report_mode(value: str | None = None) -> str:
    """Convert known legacy requests to the sole supported execution mode."""
    if value is None or (isinstance(value, str) and (
        value == "multi_agents" or value in LEGACY_REPORT_MODES
    )):
        return "multi_agents"
    raise ValueError(f"不支持的报告模式：{value}")
