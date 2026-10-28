#!/usr/bin/env python3
"""Deterministic, local-only models/search/scrape stub for ResearchRunner E2E runs.

This server implements just enough of the OpenAI HTTP protocol for the Java backend
(Spring AI OpenAI client) plus the custom retriever and Firecrawl scrape contracts, so a
complete research run can be exercised without contacting any real (paid) provider.

Endpoints
---------
POST /v1/chat/completions   OpenAI-compatible chat completion.  The answer is derived only
                            from markers in the system instruction sent by the Java nodes:
                            planner/reviewer/fact_checker/reviser JSON, writer/section and
                            initial-research Markdown, visualizer Mermaid, follow-up chat.
                            Every answer carries deterministic content and a usage block.
POST /v1/embeddings         OpenAI-compatible embeddings; returns one deterministic
                            1024-dimension vector per input element.
GET  /search?query=...      Custom retriever (RETRIEVER=custom): JSON array of
                            [{url,title,raw_content}] hits on https://stub.local/... .
POST /firecrawl/scrape      Firecrawl scrape contract: {"success": true,
                            "data": {"markdown": ..., "metadata": {"title": ...}}}.
GET  /health                Liveness probe used by the one-shot E2E script.
GET  /control/stats         Stage counters/journal for debugging (JSON).
POST /control/reset         Clears counters and the reject-once bookkeeping.

Environment
-----------
STUB_REJECT_ONCE=1          Reject every distinct research query once per review stage so
                            section_reviser / fact_reviser (repair_sections) run too.  The
                            section reviser answer follows the current Java JSON contract;
                            a legacy "plain Markdown" protocol is still auto-detected.
STUB_SLOW_MS=1500           Latency added to chat completions whose research query contains
                            the marker "E2E-SLOW"; used by the cancellation test.
STUB_SLOW_ALL=1             Slow every chat completion (diagnostics only).

Usage: python scripts/stub_provider_server.py --port 18090 --log <file>
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import threading
import time
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Markers asserted by frontend/nextjs/e2e/full-flow.spec.ts.  Keep them stable.
REPORT_TITLE = "E2E-STUB-研究标题"
REPORT_CONCLUSION = "E2E-STUB-报告结论"
SECTION_MARKER = "E2E-STUB-章节正文"
CHAT_MARKER = "E2E-STUB-聊天回复"
SEARCH_MARKER = "E2E-STUB-网页正文"

EMBEDDING_DIMENSIONS = int(os.environ.get("STUB_EMBEDDING_DIMENSIONS", "1024"))
EMBEDDING_BASE = float(os.environ.get("STUB_EMBEDDING_BASE", "1.0"))
DEFAULT_SLOW_MS = int(os.environ.get("STUB_SLOW_MS", "1500"))
EMBEDDING_CHARS = int(os.environ.get("STUB_EMBEDDING_CHARS", "0"))

LOG_LOCK = threading.Lock()


def _env_flag(name: str, default: str = "0") -> bool:
    return os.environ.get(name, default).strip().lower() in {"1", "true", "yes", "on"}


REJECT_ONCE = _env_flag("STUB_REJECT_ONCE", "0")
REJECT_FACT = _env_flag("STUB_REJECT_FACT", os.environ.get("STUB_REJECT_ONCE", "0"))
SLOW_ALL = _env_flag("STUB_SLOW_ALL", "0")


class State:
    """Thread-safe request journal and deterministic review bookkeeping."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.stages: dict[str, int] = {}
        self.requests = 0
        self.embeddings = 0
        self.searches = 0
        self.scrapes = 0
        self.journal: list[dict] = []
        self.section_rejected: set[str] = set()
        self.fact_rejected: set[str] = set()

    def count(self, stage: str) -> int:
        with self.lock:
            self.requests += 1
            self.stages[stage] = self.stages.get(stage, 0) + 1
            return self.stages[stage]

    def record(self, **event) -> None:
        event["timestamp"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
        with self.lock:
            self.journal.append(event)
            if len(self.journal) > 500:
                del self.journal[:250]

    def reject_section(self, query: str) -> bool:
        if not REJECT_ONCE:
            return False
        with self.lock:
            key = query.strip() or "<empty>"
            if key in self.section_rejected:
                return False
            self.section_rejected.add(key)
            return True

    def reject_fact(self, query: str) -> bool:
        if not REJECT_FACT:
            return False
        with self.lock:
            key = query.strip() or "<empty>"
            if key in self.fact_rejected:
                return False
            self.fact_rejected.add(key)
            return True

    def reset(self) -> None:
        with self.lock:
            self.stages.clear()
            self.journal.clear()
            self.section_rejected.clear()
            self.fact_rejected.clear()
            self.requests = self.embeddings = self.searches = self.scrapes = 0

    def snapshot(self) -> dict:
        with self.lock:
            return {
                "requests": self.requests,
                "stages": dict(self.stages),
                "embeddings": self.embeddings,
                "searches": self.searches,
                "scrapes": self.scrapes,
                "reject_once": REJECT_ONCE,
                "reject_fact": REJECT_FACT,
                "slow_ms": DEFAULT_SLOW_MS,
                "journal": list(self.journal[-120:]),
            }


STATE = State()


def log_line(payload: dict) -> None:
    text = json.dumps(payload, ensure_ascii=False)
    with LOG_LOCK:
        print(text, flush=True)
        log_path = LOG_PATH
        if log_path:
            try:
                with open(log_path, "a", encoding="utf-8") as handle:
                    handle.write(text + "\n")
            except OSError:
                pass


LOG_PATH: str | None = None
DEBUG_DIR: str | None = os.environ.get("STUB_DEBUG_DIR") or None


def _dump_chat_request(messages: list) -> None:
    """Persist each chat request (roles + content) for protocol debugging."""
    try:
        os.makedirs(DEBUG_DIR, exist_ok=True)
        stamp = time.strftime("%H%M%S") + f"-{int(time.time() * 1000) % 1000:03d}"
        payload = {
            "messages": [
                {
                    "role": (message or {}).get("role") if isinstance(message, dict) else "?",
                    "content": (message or {}).get("content") if isinstance(message, dict) else str(message),
                }
                for message in messages
            ]
        }
        with open(os.path.join(DEBUG_DIR, f"chat-{stamp}.json"), "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
    except OSError:
        pass


def _load_payload(messages: list) -> tuple[dict, str]:
    """Return (parsed JSON payload from the user message, raw payload text)."""
    if not messages:
        return {}, ""
    user_texts = [message.get("content", "") for message in messages if message.get("role") == "user"]
    if not user_texts:
        return {}, ""
    raw = str(user_texts[-1])
    try:
        value = json.loads(raw)
        return (value if isinstance(value, dict) else {}, raw)
    except (TypeError, ValueError):
        return {}, raw


def classify(instruction: str, payload: dict) -> str:
    """Map the Java system instruction onto the research stage that produced it."""
    if "知行研究助手" in instruction and "报告跟进" in instruction:
        return "chat"
    if "数据可视化智能体" in instruction:
        return "visualizer"
    if "研究文章审阅者" in instruction:
        return "section_reviewer"
    if "事实核查智能体" in instruction:
        return "fact_checker"
    if "专业撰稿人" in instruction:
        if "必须删除无法由证据支持的断言" in instruction:
            return "fact_reviser"
        return "section_reviser"
    if "研究编辑" in instruction and "章节标题大纲" in instruction:
        return "planner"
    if "研究报告撰写者" in instruction:
        return "writer"
    if "撰写详细报告" in instruction and "子章节数量最多为" in instruction:
        return "section_research"
    if "详细报告的形式回答以下问题或任务" in instruction or "报告应围绕问题的答案展开" in instruction:
        return "initial_research"
    # Follow-up chat has no research payload; treat unknown single-message prompts as chat.
    if payload.get("messages") is not None:
        return "chat"
    return "unknown"


def _query_of(payload: dict, instruction: str) -> str:
    for key in ("query", "question", "title"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    marker = "以详细报告的形式回答以下问题或任务：\""
    index = instruction.find(marker)
    if index >= 0:
        end = instruction.find("\"", index + len(marker))
        if end > index:
            return instruction[index + len(marker):end].strip()
    return ""


def _section_reviser_contract(instruction: str) -> bool:
    """True when the current protocol asks for the {draft, revision_notes} JSON contract."""
    if "不要 JSON 包装" in instruction:
        return False
    return "revision_notes" in instruction and "JSON" in instruction


def initial_research_markdown(query: str) -> str:
    return (
        "# 初步研究摘要\n\n"
        f"围绕研究问题“{query or 'E2E'}”，本地 stub 汇总了确定性证据要点。"
        f"本段由 {SECTION_MARKER} 生成，仅用于端到端回归。\n\n"
        "## 关键发现\n\n"
        "- 研究报告的备份、验证与回滚需要严格依据可用证据。[E1]\n"
        "- 所有结论都应在报告中保留证据编号，便于复核。\n"
    )


def section_research_markdown(chapter: str) -> str:
    return (
        f"## {chapter or '章节'}\n\n"
        f"{SECTION_MARKER}：本章节围绕给定证据整理要点，并保留可核验的引用编号。[E1]\n\n"
        "- 证据编号在写作与修订阶段保持不变。\n"
        "- 资料不足时会明确说明，而不是编造来源。\n"
    )


def writer_markdown(query: str) -> str:
    return f"""# {REPORT_TITLE}

## 引言

本报告由本地确定性 stub 生成，用于验证“{query or '研究问题'}”的完整研究流程（规划、章节研究、写作、核查、可视化、发布与导出），不产生任何真实供应商调用。[E1]

## 章节一：研究背景与流程

- 全流程包含初始研究、规划、并行章节研究、写作、事实核查、可视化与发布。
- 引用证据编号保持一致，导出 Markdown/PDF/DOCX 时保留可核验链接。[E1]

## 章节二：验证要点

| 检查项 | 期望结果 |
| --- | --- |
| 报告正文 | 可读且包含证据编号 [E1] |
| 证据弹窗 | 能列出证据快照 |
| 导出 | Markdown/PDF/DOCX 均可访问 |

## 结论

{REPORT_CONCLUSION}：全流程真实联调已完成，报告保留证据编号 [E1]，可据此复核来源。
"""


def revised_section_markdown() -> str:
    return (
        f"## 修订后的章节内容\n\n{SECTION_MARKER}（修订版）：补充了证据编号与可核验说明。[E1]\n"
    )


def chat_answer() -> str:
    return (
        f"{CHAT_MARKER}：根据报告正文，本报告由本地 stub 生成，结论保留了证据编号 [E1]，"
        "如需更多细节可以继续追问。"
    )


def chat_completion(stage: str, payload: dict, instruction: str) -> str:
    query = _query_of(payload, instruction)
    if stage == "planner":
        return json.dumps(
            {
                "title": "E2E 研究计划：本地 stub 全流程验证",
                "date": "2026-10-28",
                "sections": ["背景与现状", "关键要点与结论"],
            },
            ensure_ascii=False,
        )
    if stage == "section_reviewer":
        if STATE.reject_section(query):
            return json.dumps(
                {
                    "accepted": False,
                    "feedback": "请补充可核验的证据编号，并删除缺乏证据支持的断言。",
                },
                ensure_ascii=False,
            )
        return json.dumps({"accepted": True, "feedback": ""}, ensure_ascii=False)
    if stage == "fact_checker":
        if STATE.reject_fact(query):
            return json.dumps(
                {
                    "accepted": False,
                    "feedback": "结论段落缺少证据编号，请依据证据修订后再提交核查。",
                },
                ensure_ascii=False,
            )
        return json.dumps({"accepted": True, "feedback": ""}, ensure_ascii=False)
    if stage == "section_reviser":
        body = revised_section_markdown()
        if _section_reviser_contract(instruction):
            return json.dumps(
                {"draft": body, "revision_notes": "已按审阅意见补充证据编号与可核验说明。"},
                ensure_ascii=False,
            )
        return body
    if stage == "fact_reviser":
        return revised_section_markdown()
    if stage == "writer":
        return writer_markdown(query)
    if stage == "section_research":
        chapter = payload.get("title")
        if not isinstance(chapter, str) or not chapter.strip():
            chapter = ""
            for title in payload.get("chapters", []) or []:
                if isinstance(title, dict) and title.get("title"):
                    chapter = str(title["title"])
                    break
        return section_research_markdown(str(chapter))
    if stage == "initial_research":
        return initial_research_markdown(query)
    if stage == "visualizer":
        return (
            "```mermaid\n"
            "flowchart TD\n"
            "    n1[\"研究主题\"] --> n2[\"证据收集\"]\n"
            "    n2 --> n3[\"章节写作\"]\n"
            "    n3 --> n4[\"报告发布\"]\n"
            "```"
        )
    if stage == "chat":
        return chat_answer()
    return f"{CHAT_MARKER}（未识别的阶段，返回确定性占位文本）。"


def embedding_for(text: str) -> list[float]:
    """A deterministic non-zero vector: shared direction plus a tiny per-text jitter.

    The shared positive direction keeps cosine similarity between any query and any
    indexed chunk close to 1, so the pgvector retrieval threshold always matches while
    still being deterministic for a given input.
    """
    digest = hashlib.sha256(str(text).encode("utf-8")).digest()
    vector: list[float] = []
    for index in range(EMBEDDING_DIMENSIONS):
        jitter = ((digest[index % len(digest)] / 255.0) - 0.5) * 1e-3
        vector.append(EMBEDDING_BASE + jitter)
    return vector


class Handler(BaseHTTPRequestHandler):
    server_version = "ResearchRunnerStub/1.0"
    protocol_version = "HTTP/1.1"

    # ------------------------------------------------------------------ plumbing
    def log_message(self, fmt: str, *args) -> None:  # noqa: A003 - stdlib signature
        log_line({"event": "http", "method": self.command, "path": self.path})

    def _send_json(self, status: int, body: object) -> None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)

    def _read_body(self) -> bytes:
        """Read the request body, including HTTP/1.1 chunked transfer encoding.

        Spring AI's JDK HTTP client may stream the JSON request body with
        `Transfer-Encoding: chunked` instead of a Content-Length header; the stdlib
        BaseHTTPRequestHandler only understands Content-Length.
        """
        expect = (self.headers.get("Expect") or "").lower()
        if "100-continue" in expect:
            self.send_response_only(100)
            self.end_headers()
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            chunks: list[bytes] = []
            while True:
                line = self.rfile.readline()
                if not line:
                    break
                line = line.strip()
                if not line:
                    continue
                try:
                    size = int(line.split(b";", 1)[0], 16)
                except ValueError:
                    break
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            return b"".join(chunks)
        length = int(self.headers.get("Content-Length") or "0")
        return self.rfile.read(length) if length else b""

    def _read_json(self) -> object:
        raw = self._read_body()
        try:
            return json.loads(raw.decode("utf-8") or "{}")
        except (UnicodeDecodeError, ValueError) as error:
            raise ValueError(f"invalid JSON body: {error}") from error

    # ------------------------------------------------------------------ GET
    def do_GET(self) -> None:  # noqa: N802 - stdlib signature
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path in {"/health", "/healthz", "/"}:
            self._send_json(200, {"status": "ok", "stub": True, "reject_once": REJECT_ONCE})
            return
        if parsed.path == "/control/stats":
            self._send_json(200, STATE.snapshot())
            return
        if parsed.path == "/search":
            self._handle_search(urllib.parse.parse_qs(parsed.query).get("query", [""])[0])
            return
        self._send_json(404, {"error": "not found", "path": parsed.path})

    def do_POST(self) -> None:  # noqa: N802 - stdlib signature
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/control/reset":
            STATE.reset()
            self._send_json(200, {"reset": True})
            return
        try:
            body = self._read_json()
        except ValueError as error:
            self._send_json(400, {"error": str(error)})
            return
        if parsed.path in {"/v1/chat/completions", "/chat/completions"}:
            self._handle_chat(body)
        elif parsed.path in {"/v1/embeddings", "/embeddings"}:
            self._handle_embeddings(body)
        elif parsed.path.endswith("/firecrawl/scrape") or parsed.path.endswith("/scrape"):
            self._handle_scrape(body)
        else:
            self._send_json(404, {"error": "not found", "path": parsed.path})

    # ------------------------------------------------------------------ chat
    def _handle_chat(self, body: object) -> None:
        started = time.time()
        if not isinstance(body, dict):
            self._send_json(400, {"error": "chat body must be an object"})
            return
        messages = body.get("messages") or []
        if DEBUG_DIR:
            _dump_chat_request(messages)
        system_parts = [
            str(message.get("content", ""))
            for message in messages
            if isinstance(message, dict) and message.get("role") == "system"
        ]
        instruction = "\n".join(system_parts)
        payload, raw_payload = _load_payload(messages)
        stage = classify(instruction, payload)
        query = _query_of(payload, instruction)
        delay_ms = 0
        if DEFAULT_SLOW_MS > 0 and (SLOW_ALL or "E2E-SLOW" in query or "E2E-SLOW" in instruction):
            delay_ms = DEFAULT_SLOW_MS
            time.sleep(delay_ms / 1000.0)
        content = chat_completion(stage, payload, instruction)
        STATE.count(stage)
        model = str(body.get("model") or "stub-model")
        prompt_tokens = max(1, len(instruction) // 4 + len(raw_payload) // 4)
        completion_tokens = max(1, len(content) // 4)
        response = {
            "id": f"chatcmpl-stub-{uuid.uuid4().hex[:16]}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": model,
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": content},
                    "finish_reason": "stop",
                }
            ],
            "usage": {
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
                "total_tokens": prompt_tokens + completion_tokens,
            },
        }
        STATE.record(
            event="chat",
            stage=stage,
            query=query[:160],
            chars=len(content),
            delay_ms=delay_ms,
            elapsed_ms=int((time.time() - started) * 1000),
        )
        log_line(
            {
                "event": "chat",
                "stage": stage,
                "query": query[:160],
                "chars": len(content),
                "delay_ms": delay_ms,
                "instruction_chars": len(instruction),
            }
        )
        self._send_json(200, response)

    # ------------------------------------------------------------------ embeddings
    def _handle_embeddings(self, body: object) -> None:
        if not isinstance(body, dict):
            self._send_json(400, {"error": "embeddings body must be an object"})
            return
        inputs = body.get("input")
        if isinstance(inputs, str):
            inputs = [inputs]
        if not isinstance(inputs, list):
            self._send_json(400, {"error": "input must be a string or array"})
            return
        data = [
            {"object": "embedding", "index": index, "embedding": embedding_for(str(text))}
            for index, text in enumerate(inputs)
        ]
        with STATE.lock:
            STATE.embeddings += len(data)
        STATE.record(event="embeddings", count=len(data))
        self._send_json(
            200,
            {
                "object": "list",
                "data": data,
                "model": str(body.get("model") or "stub-embedding"),
                "usage": {"prompt_tokens": sum(len(str(text)) // 4 for text in inputs), "total_tokens": 0},
            },
        )

    # ------------------------------------------------------------------ search
    def _handle_search(self, query: str) -> None:
        with STATE.lock:
            STATE.searches += 1
        STATE.record(event="search", query=query[:160])
        results = [
            {
                "url": "https://stub.local/e2e/page-1",
                "title": "E2E 网页一：迁移背景",
                "raw_content": (
                    f"{SEARCH_MARKER}一：关于“{query}”的背景资料。"
                    "应用数据库迁移前需要备份，并在隔离数据库中验证；命名数据卷不是备份。"
                ),
            },
            {
                "url": "https://stub.local/e2e/page-2",
                "title": "E2E 网页二：验证要点",
                "raw_content": (
                    f"{SEARCH_MARKER}二：关于“{query}”的验证与回滚要点。"
                    "切换前停止旧后端写入，确认迁移版本一致，再启动新后端并核对报告与证据。"
                ),
            },
        ]
        self._send_json(200, results)

    # ------------------------------------------------------------------ scrape
    def _handle_scrape(self, body: object) -> None:
        if not isinstance(body, dict):
            self._send_json(400, {"error": "scrape body must be an object"})
            return
        url = str(body.get("url") or "")
        with STATE.lock:
            STATE.scrapes += 1
        STATE.record(event="scrape", url=url[:160])
        markdown = (
            f"# {SEARCH_MARKER}\n\n"
            f"来源地址：{url}\n\n"
            "这是本地 stub 返回的网页正文。数据库迁移必须保留完整备份，"
            "在隔离环境恢复并核对记录后再切换写入；回滚时先停止新后端写入并保留新数据。"
        )
        self._send_json(
            200,
            {
                "success": True,
                "data": {
                    "markdown": markdown,
                    "metadata": {"title": f"E2E 网页正文 {url.rsplit('/', 1)[-1] or 'page'}"},
                },
            },
        )


def main() -> int:
    global LOG_PATH
    parser = argparse.ArgumentParser(description="ResearchRunner deterministic provider stub")
    parser.add_argument("--host", default=os.environ.get("STUB_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("STUB_PORT", "18090")))
    parser.add_argument("--log", default=os.environ.get("STUB_LOG", ""))
    args = parser.parse_args()
    LOG_PATH = args.log or None
    if LOG_PATH:
        directory = os.path.dirname(os.path.abspath(LOG_PATH))
        if directory:
            os.makedirs(directory, exist_ok=True)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    log_line(
        {
            "event": "start",
            "host": args.host,
            "port": args.port,
            "reject_once": REJECT_ONCE,
            "reject_fact": REJECT_FACT,
            "slow_ms": DEFAULT_SLOW_MS,
            "embedding_dimensions": EMBEDDING_DIMENSIONS,
        }
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
