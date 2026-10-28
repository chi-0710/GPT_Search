"""WebSocket imports and runtime configuration remain usable with one engine."""
import importlib
from unittest.mock import AsyncMock

import pytest

from backend.server import websocket_manager


def test_websocket_manager_module_imports():
    assert hasattr(importlib.import_module("backend.server.websocket_manager"), "WebSocketManager")


def test_server_app_module_imports():
    assert hasattr(importlib.import_module("backend.server.app"), "app")


@pytest.mark.asyncio
async def test_start_streaming_reads_config_path_from_environment(monkeypatch):
    result = object()
    run = AsyncMock(return_value=result)
    monkeypatch.setattr(websocket_manager, "run_agent", run)
    monkeypatch.setenv("CONFIG_PATH", "custom-config")
    manager = websocket_manager.WebSocketManager()
    actual = await manager.start_streaming(
        task="test", report_type="multi_agents", report_source="web",
        source_urls=[], document_urls=[], tone="Objective", websocket=object(),
        document_ids=["allowed"],
    )
    assert actual is result
    assert run.call_args.kwargs["config_path"] == "custom-config"
    assert run.call_args.kwargs["document_retriever"].document_ids == ["allowed"]
