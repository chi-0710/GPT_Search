from unittest.mock import AsyncMock

import pytest

from backend.server.server_utils import CustomLogsHandler


class FakeEventStore:
    def __init__(self, timeline):
        self.timeline = timeline

    async def append_event(self, run_id, payload):
        self.timeline.append(("database", run_id, payload))
        return len(self.timeline)


@pytest.mark.asyncio
async def test_custom_logs_handler_persists_before_websocket_delivery():
    timeline = []
    websocket = AsyncMock()

    async def send(payload):
        timeline.append(("websocket", payload))

    websocket.send_json.side_effect = send
    handler = CustomLogsHandler(
        websocket,
        "test query",
        store=FakeEventStore(timeline),
        run_id="run-id",
        research_id="research-id",
    )
    payload = {"type": "logs", "content": "test"}

    await handler.send_json(payload)

    assert timeline == [
        ("database", "run-id", payload),
        ("websocket", payload),
    ]


@pytest.mark.asyncio
async def test_custom_logs_handler_keeps_event_when_delivery_fails():
    timeline = []
    websocket = AsyncMock()
    websocket.send_json.side_effect = RuntimeError("disconnected")
    handler = CustomLogsHandler(
        websocket,
        "test query",
        store=FakeEventStore(timeline),
        run_id="run-id",
    )

    await handler.send_json({"type": "report", "output": "saved"})

    assert timeline[0][0] == "database"
