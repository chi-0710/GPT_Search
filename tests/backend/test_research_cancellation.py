"""Cancellation must finish persistence even when the client has disconnected."""

import asyncio
import sys
from types import ModuleType, SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from backend.server import server_utils


def test_cancelled_run_is_finalized_before_log_delivery(monkeypatch):
    async def scenario():
        documents = ModuleType("backend.server.document_service")
        documents.resolve_document_scope = AsyncMock(return_value=[])
        monkeypatch.setitem(sys.modules, documents.__name__, documents)
        store = SimpleNamespace(
            create_run=AsyncMock(return_value={"run_id": "run", "research_id": "report"}),
            append_event=AsyncMock(side_effect=[None, RuntimeError("event persistence failed")]),
            fail_run=AsyncMock(),
        )
        websocket = SimpleNamespace(send_json=AsyncMock(side_effect=RuntimeError("closed")))
        manager = SimpleNamespace(
            store=store, start_streaming=AsyncMock(side_effect=asyncio.CancelledError()),
        )
        with pytest.raises(asyncio.CancelledError):
            await server_utils.handle_start_command(
                websocket, 'start {"task":"test","report_type":"research_report"}', manager,
            )
        store.fail_run.assert_awaited_once_with("run", "Research task cancelled after client disconnect")
        assert store.append_event.await_count == 2

    asyncio.run(scenario())


def test_disconnect_waits_for_research_cleanup(monkeypatch):
    async def scenario():
        started = asyncio.Event()
        cleaned = asyncio.Event()

        async def research(*args):
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                await asyncio.sleep(0)
                cleaned.set()

        calls = 0

        async def receive():
            nonlocal calls
            calls += 1
            if calls == 1:
                return 'start {}'
            await started.wait()
            raise RuntimeError("client disconnected")

        monkeypatch.setattr(server_utils, "handle_start_command", research)
        websocket = SimpleNamespace(receive_text=receive, send_json=AsyncMock())
        await server_utils.handle_websocket_communication(websocket, object())
        assert cleaned.is_set()

    asyncio.run(scenario())
