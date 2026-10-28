import asyncio
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from backend.server import server_utils


HUMAN_AGENT_PATH = (
    Path(__file__).resolve().parents[2] / "multi_agents" / "agents" / "human.py"
)
spec = importlib.util.spec_from_file_location("human_agent", HUMAN_AGENT_PATH)
human_agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(human_agent)


def test_parse_human_feedback_uses_frontend_json_protocol():
    message = json.dumps({"type": "human_feedback", "content": "Add risks"})

    assert server_utils.parse_human_feedback(message) == "Add risks"


@pytest.mark.asyncio
async def test_feedback_reaches_agent_while_research_task_is_running(monkeypatch):
    feedback_queue = asyncio.Queue()
    websocket = _FakeWebSocket(
        [
            "start {}",
            json.dumps({"type": "human_feedback", "content": "Add risks"}),
        ],
        feedback_queue,
    )

    async def fake_start_command(*args, **kwargs):
        await asyncio.Event().wait()

    monkeypatch.setattr(server_utils, "handle_start_command", fake_start_command)

    await server_utils.handle_websocket_communication(websocket, object())

    wrapped_websocket = SimpleNamespace(websocket=websocket)

    async def stream_output(*args, **kwargs):
        return None

    agent = human_agent.HumanAgent(wrapped_websocket, stream_output)
    result = await agent.review_plan({
        "task": {"include_human_feedback": True},
        "sections": [],
        "plan_revision_count": 0,
    })

    assert result == {
        "human_feedback": "Add risks",
        "plan_revision_count": 1,
    }
    assert websocket.sent_json == []


class _FakeWebSocket:
    def __init__(self, messages, feedback_queue):
        self._messages = iter(messages)
        self.state = SimpleNamespace(human_feedback_queue=feedback_queue)
        self.sent_json = []

    async def receive_text(self):
        try:
            return next(self._messages)
        except StopIteration:
            raise RuntimeError("test complete")

    async def send_json(self, data):
        self.sent_json.append(data)

    async def send_text(self, data):
        return None
