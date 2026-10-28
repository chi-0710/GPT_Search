import asyncio
from search.utils.report_modes import normalize_report_mode
import json
import os
from typing import Dict, List, Any, Literal
import time
import logging
import hashlib
import sys
import uuid
import warnings
from datetime import datetime, timezone
from io import BytesIO

# Suppress Pydantic V2 migration warnings
warnings.filterwarnings("ignore", message="Valid config keys have changed in V2")
warnings.filterwarnings("ignore", category=UserWarning, module="pydantic")

from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect, File, UploadFile, BackgroundTasks, HTTPException, Query
from contextlib import asynccontextmanager
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse, HTMLResponse, RedirectResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator
from html import escape
from urllib.parse import quote, urlsplit

# Add the parent directory to sys.path to make sure we can import from server
sys.path.insert(0, os.path.abspath(os.path.dirname(os.path.dirname(__file__))))

from server.websocket_manager import WebSocketManager
from server.server_utils import (
    handle_websocket_communication
)
from server.agent_discovery import build_agent_discovery_document

from server.websocket_manager import run_agent
from search.utils.enum import Tone
from chat.chat import ChatAgentWithMemory
from server.artifact_service import artifact_headers, persist_report_artifacts
from server.database import (
    StorageConflict,
    StorageUnavailable,
    dispose_engine,
    readiness_status,
)
from server.document_service import PostgresDocumentRetriever, index_document, resolve_document_scope
from server.postgres_store import PostgresStore
from server.server_utils import CustomLogsHandler

# Setup logging
logger = logging.getLogger(__name__)

# Don't override parent logger settings
logger.propagate = True

# Silence uvicorn reload logs
logging.getLogger("uvicorn.supervisors.ChangeReload").setLevel(logging.WARNING)

# Models


class ResearchRequest(BaseModel):
    task: str
    report_type: str = "multi_agents"
    report_source: Literal["web", "local", "hybrid"] = "web"
    tone: str
    headers: dict | None = None
    repo_name: str
    branch_name: str
    generate_in_background: bool = True
    document_scope: Literal["all", "selected"] = "all"
    document_ids: List[str] = Field(default_factory=list)
    query_domains: List[str] = Field(default_factory=list)

    source_urls: List[str] = Field(default_factory=list)
    document_urls: List[str] = Field(default_factory=list)
    mcp_enabled: bool = False
    mcp_configs: List[dict] = Field(default_factory=list)
    mcp_strategy: str = "fast"
    max_search_results: int | None = Field(default=None, ge=1)

    @field_validator("report_type", mode="before")
    @classmethod
    def normalize_mode(cls, value):
        return normalize_report_mode(value)


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="allow")  # Allow extra fields in the request
    
    report: str
    messages: List[Dict[str, Any]]


@asynccontextmanager
async def lifespan(app: FastAPI):
    status = await readiness_status()
    if status["ready"]:
        logger.info("ResearchRunner API ready - PostgreSQL persistence enabled")
    else:
        logger.error("PostgreSQL readiness check failed: %s", status)
    yield
    await dispose_engine()
    logger.info("Research API shutting down")

# App initialization
app = FastAPI(title="ResearchRunner API", lifespan=lifespan)

# Configure allowed origins for CORS
allowed_origins_env = os.getenv("CORS_ALLOW_ORIGINS")
ALLOWED_ORIGINS = (
    [o.strip() for o in allowed_origins_env.split(",") if o.strip()]
    if allowed_origins_env
    else [
        "http://localhost:3000",
        "http://127.0.0.1:3000",
        "https://app.gptr.dev",
    ]
)

# Standard JSON response - no custom MongoDB encoding needed

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Use default JSON response class

# WebSocket manager
report_store = PostgresStore()
manager = WebSocketManager(report_store)


@app.exception_handler(StorageUnavailable)
async def storage_unavailable_handler(_request: Request, _exc: StorageUnavailable):
    return JSONResponse(status_code=503, content={"detail": 'PostgreSQL 存储服务不可用'})


@app.exception_handler(StorageConflict)
async def storage_conflict_handler(_request: Request, exc: StorageConflict):
    return JSONResponse(status_code=409, content={"detail": str(exc)})

# Startup event


# Lifespan events now handled in the lifespan context manager above


# Routes
@app.get("/")
async def read_root():
    """Return a stable status response for the API service."""
    return {"service": "researchrunner-api", "status": "ok", "docs": "/docs"}


@app.get("/.well-known/agent-discovery.json")
async def agent_discovery(request: Request):
    """Advertise ResearchRunner services via the Agent Discovery Protocol."""
    origin = str(request.base_url).rstrip("/")
    domain = request.url.hostname or request.headers.get("host", "")
    contact = os.getenv("AGENT_DISCOVERY_CONTACT")

    document = build_agent_discovery_document(origin=origin, domain=domain, contact=contact)
    response = JSONResponse(content=document)
    response.headers["Access-Control-Allow-Origin"] = "*"
    return response

@app.get("/report/{research_id}")
async def read_report(research_id: str):
    artifact = await report_store.get_report_artifact(research_id, "docx")
    if artifact is None:
        raise HTTPException(status_code=404, detail='未找到报告')
    return StreamingResponse(
        BytesIO(artifact.content),
        media_type=artifact.mime_type,
        headers=artifact_headers(artifact),
    )


@app.get("/health/ready")
async def health_ready():
    status = await readiness_status()
    return JSONResponse(status_code=200 if status["ready"] else 503, content=status)


def _artifact_response(artifact, *, download: bool):
    return StreamingResponse(
        BytesIO(artifact.content),
        media_type=artifact.mime_type,
        headers=artifact_headers(artifact, inline=not download),
    )


@app.get("/api/artifacts/{artifact_id}")
async def get_artifact(artifact_id: str, download: bool = Query(default=False)):
    try:
        artifact = await report_store.get_artifact(artifact_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail='未找到报告附件') from exc
    if artifact is None:
        raise HTTPException(status_code=404, detail='未找到报告附件')
    return _artifact_response(artifact, download=download)


@app.get("/outputs/{legacy_path:path}")
async def get_legacy_artifact(legacy_path: str):
    artifact = await report_store.get_artifact_by_legacy_path(legacy_path)
    if artifact is None:
        raise HTTPException(status_code=404, detail='未找到报告附件')
    return _artifact_response(artifact, download=False)


@app.get("/api/reports")
async def get_all_reports(
    report_ids: str | None = None,
    limit: int = Query(default=50, ge=1, le=200),
    cursor: datetime | None = None,
    include_incomplete: bool = False,
):
    report_ids_list = report_ids.split(",") if report_ids else None
    reports = await report_store.list_reports(
        report_ids_list,
        limit=limit,
        cursor=cursor,
        include_incomplete=include_incomplete,
    )
    next_cursor = None
    if len(reports) == limit and reports:
        next_cursor = datetime.fromtimestamp(
            reports[-1]["timestamp"] / 1000, tz=timezone.utc
        ).isoformat()
    return {"reports": reports, "next_cursor": next_cursor}


@app.get("/api/reports/{research_id}")
async def get_report_by_id(research_id: str):
    report = await report_store.get_report(research_id)
    if report is None:
        raise HTTPException(status_code=404, detail='未找到报告')
    return {"report": report}


@app.post("/api/reports")
async def create_or_update_report(request: Request):
    data = await request.json()
    research_id = str(data.get("id") or uuid.uuid4())
    await report_store.upsert_report(research_id, data)
    return {"success": True, "id": research_id}


@app.put("/api/reports/{research_id}")
async def update_report(research_id: str, request: Request):
    data = await request.json()
    updated = await report_store.patch_report(research_id, data)
    if not updated:
        raise HTTPException(status_code=404, detail='未找到报告')
    if isinstance(data.get("chatMessages"), list):
        current = await report_store.get_report(research_id) or {}
        current.update(data)
        await report_store.upsert_report(research_id, current)
    return {"success": True, "id": research_id}


@app.delete("/api/reports")
async def clear_reports():
    deleted = await report_store.clear_reports()
    return {"success": True, "deleted": deleted}


@app.delete("/api/reports/{research_id}")
async def delete_report(research_id: str):
    existed = await report_store.delete_report(research_id)
    if not existed:
        raise HTTPException(status_code=404, detail='未找到报告')
    return {"success": True}


@app.get("/api/reports/{research_id}/chat")
async def get_report_chat(research_id: str):
    messages = await report_store.list_chat_messages(research_id)
    if messages is None:
        raise HTTPException(status_code=404, detail='未找到报告')
    return {"chatMessages": messages}


@app.post("/api/reports/{research_id}/chat")
async def add_report_chat_message(research_id: str, request: Request):
    message = await request.json()
    added = await report_store.add_chat_message(research_id, message)
    if not added:
        raise HTTPException(status_code=404, detail='未找到报告')
    return {"success": True, "id": research_id}


@app.get("/api/research-runs/{run_id}")
async def get_research_run(run_id: str):
    run = await report_store.get_run(run_id)
    if run is None:
        raise HTTPException(status_code=404, detail='未找到研究任务')
    return {"run": run}


@app.get("/api/research-runs/{run_id}/events")
async def get_research_events(
    run_id: str, after_id: int = Query(default=0, ge=0), limit: int = Query(default=500, ge=1, le=2000)
):
    events = await report_store.list_events(run_id, after_id=after_id, limit=limit)
    if events is None:
        raise HTTPException(status_code=404, detail='未找到研究任务')
    return {"events": events}


@app.get("/api/research-runs/{run_id}/log")
async def download_research_log(run_id: str):
    run = await report_store.get_run(run_id)
    sources = await report_store.list_sources(run_id)
    if run is None or sources is None:
        raise HTTPException(status_code=404, detail='未找到研究任务')
    events: list[dict[str, Any]] = []
    after_id = 0
    while True:
        batch = await report_store.list_events(run_id, after_id=after_id, limit=2000)
        if batch is None:
            raise HTTPException(status_code=404, detail='未找到研究任务')
        events.extend(batch)
        if len(batch) < 2000:
            break
        after_id = int(batch[-1]["id"])
    report = await report_store.get_report(run["research_id"])
    payload = {
        "timestamp": run.get("started_at"),
        "events": [
            {"timestamp": event.get("createdAt"), "type": "event", "data": event}
            for event in events
        ],
        "content": {
            "query": run.get("query", ""),
            "sources": [source["url"] for source in sources],
            "context": sources,
            "report": (report or {}).get("answer", ""),
            "costs": run.get("costs", {}),
        },
    }
    content = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
    return StreamingResponse(
        BytesIO(content),
        media_type="application/json",
        headers={"Content-Disposition": f'attachment; filename="{run_id}.json"'},
    )


async def write_report(research_request: ResearchRequest, run: dict[str, str]):
    logs_handler = CustomLogsHandler(
        None,
        research_request.task,
        store=report_store,
        run_id=run["run_id"],
        research_id=run["research_id"],
    )
    await logs_handler.send_json({
        "type": "research_started",
        "content": "research_started",
        "research_id": run["research_id"],
        "run_id": run["run_id"],
        "output": run["research_id"],
    })

    async def artifact_sink(*, content, filename, mime_type, research_id=None):
        metadata = await report_store.put_artifact(
            content=content,
            filename=filename,
            kind="image",
            mime_type=mime_type,
            report_public_id=run["research_id"],
            run_id=run["run_id"],
            legacy_path=f"outputs/images/{run['research_id']}/{filename}",
        )
        return metadata["url"]

    try:
        result = await run_agent(
            task=research_request.task,
            report_type=research_request.report_type,
            report_source=research_request.report_source,
            source_urls=research_request.source_urls,
            document_urls=research_request.document_urls,
            mcp_enabled=research_request.mcp_enabled,
            mcp_configs=research_request.mcp_configs,
            mcp_strategy=research_request.mcp_strategy,
            max_search_results=research_request.max_search_results,
            tone=Tone[research_request.tone],
            websocket=None,
            stream_output=None,
            headers=research_request.headers,
            query_domains=research_request.query_domains,
            config_path="",
            logs_handler=logs_handler,
            document_retriever=PostgresDocumentRetriever(report_store, document_ids=run.get("resolved_document_ids", [])),
            artifact_sink=artifact_sink,
        )
        report = result.report
        sources = result.sources
        costs = result.costs
        paths = await persist_report_artifacts(
            report_store,
            report_public_id=run["research_id"],
            run_id=run["run_id"],
            markdown=str(report),
            legacy_stem=run["research_id"],
        )
        await logs_handler.send_json({"type": "path", "output": paths})
        await report_store.complete_run(
            run["run_id"], answer=str(report), costs=costs, sources=sources
        )
        response = {
            "research_id": run["research_id"],
            "run_id": run["run_id"],
            "evidence_url": f"/api/research-runs/{run['run_id']}/evidence",
            "report": report,
            "docx_path": paths.get("docx"),
            "pdf_path": paths.get("pdf"),
            "md_path": paths.get("md"),
        }
        response["research_information"] = {
            "source_urls": [item["url"] for item in sources if item.get("url")],
            "research_costs": costs,
            "visited_urls": [item["url"] for item in sources if item.get("url")],
            "research_images": [],
        }
        return response
    except asyncio.CancelledError:
        await report_store.fail_run(run["run_id"], "研究任务已取消")
        raise
    except Exception as exc:
        await logs_handler.send_json({
            "type": "logs", "content": "error", "output": f"错误：{exc}"
        })
        await report_store.fail_run(run["run_id"], str(exc))
        raise

@app.post("/report/")
async def generate_report(research_request: ResearchRequest, background_tasks: BackgroundTasks):
    options = research_request.model_dump()
    try:
        document_ids = await resolve_document_scope(report_store, options)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    options["resolved_document_ids"] = document_ids
    run = await report_store.create_run(
        query=research_request.task,
        report_type=research_request.report_type,
        report_source=research_request.report_source,
        tone=research_request.tone,
        request_options=options,
    )
    run["resolved_document_ids"] = document_ids

    if research_request.generate_in_background:
        background_tasks.add_task(write_report, research_request=research_request, run=run)
        return {"message": "Your report is being generated in the background. Please check back later.",
                **run}
    else:
        response = await write_report(research_request, run)
        return response


@app.get("/files/")
async def list_files():
    documents = await report_store.list_documents()
    return {"files": [item["filename"] for item in documents], "documents": documents}


@app.get("/api/documents")
async def list_documents():
    return {"documents": await report_store.list_documents()}


@app.get("/api/documents/{document_id}/content")
async def document_content(document_id: str, download: bool = True):
    document = await report_store.get_document(document_id)
    if document is None:
        raise HTTPException(status_code=404, detail="原文件已删除或不存在")
    # Only PDFs are viewed inline; other formats are downloaded, never executed as HTML.
    is_pdf = document.filename.lower().endswith(".pdf") and document.content.startswith(b"%PDF-")
    disposition = "inline" if is_pdf and not download else "attachment"
    return StreamingResponse(BytesIO(document.content),
        media_type="application/pdf" if is_pdf else "application/octet-stream",
        headers={"Content-Disposition": f"{disposition}; filename*=UTF-8''{quote(document.filename, safe='')}",
                 "X-Content-Type-Options": "nosniff"})


@app.get("/api/reports/{identifier}/evidence")
@app.get("/api/research-runs/{identifier}/evidence")
async def evidence_list(identifier: str):
    evidence = await report_store.list_evidence(identifier)
    if evidence is None:
        raise HTTPException(status_code=404, detail='未找到研究任务')
    return {"evidence": evidence}


@app.get("/api/research-runs/{identifier}/evidence/{number}")
async def evidence_item(identifier: str, number: int):
    evidence = await evidence_list(identifier)
    item = next((item for item in evidence["evidence"] if item["number"] == number), None)
    if item is None:
        raise HTTPException(status_code=404, detail='未找到证据')
    return item


@app.get("/api/research-runs/{identifier}/evidence/{number}/view", response_class=HTMLResponse)
async def evidence_view(identifier: str, number: int):
    item = await evidence_item(identifier, number)
    original = "<p>原文件已删除，以下引用片段仍可查看。</p>"
    if item["source_type"] == "web":
        url = str(item.get("url") or "").strip()
        try:
            parsed = urlsplit(url)
            valid_url = (
                parsed.scheme in {"http", "https"}
                and bool(parsed.hostname)
                and not any(character.isspace() for character in url)
            )
        except ValueError:
            valid_url = False
        if not valid_url:
            raise HTTPException(status_code=422, detail="该引用没有有效的原始网页地址")
        return RedirectResponse(url=url, status_code=302)
    elif item["source_type"] == "mcp":
        original = "<p>工具检索结果：以下为本次研究使用的内容快照。</p>"
    elif item["original_available"]:
        path = f'/api/documents/{quote(item["document_id"], safe="")}/content'
        original = f'<p><a href="{path}">下载原文件</a></p>'
        if item["title"].lower().endswith(".pdf"):
            page = item.get("page")
            fragment = f"#page={int(page)}" if isinstance(page, int) and page > 0 else ""
            original += f'<p><a href="{path}?download=false{fragment}">打开 PDF 对应位置</a></p>'
            original += f'<iframe title="PDF 原文" src="{path}?download=false{fragment}" style="width:100%;height:55vh"></iframe>'
    location = f'第 {item["page"]} 页' if item.get("page") else f'片段 {item.get("chunk_id") or number}'
    return HTMLResponse(
        '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width">'
        f'<title>引用 {number}</title><body style="max-width:1000px;margin:2em auto;padding:1em;font-family:sans-serif">'
        f'<h1>{escape(item["title"])}</h1><p>{escape(location)}</p>{original}'
        f'<h2>研究时使用的证据片段</h2><pre style="white-space:pre-wrap">{escape(item["content"])}</pre></body></html>',
        headers={"Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; frame-src 'self'; base-uri 'none'"},
    )


async def _read_upload(file: UploadFile) -> tuple[bytes, str]:
    content = bytearray()
    hasher = hashlib.sha256()
    while chunk := await file.read(1024 * 1024):
        content.extend(chunk)
        hasher.update(chunk)
        if len(content) > report_store.max_blob_bytes:
            raise HTTPException(status_code=413, detail='上传的文件过大')
    return bytes(content), hasher.hexdigest()


async def _store_uploaded_document(file: UploadFile, background_tasks: BackgroundTasks):
    content, sha256 = await _read_upload(file)
    document = await report_store.put_document_blob(
        filename=file.filename or "document.bin",
        mime_type=file.content_type or "application/octet-stream",
        content=content,
        sha256=sha256,
    )
    if not document.get("reused"):
        background_tasks.add_task(index_document, report_store, document["id"])
    return document


@app.post("/api/documents")
async def upload_document(background_tasks: BackgroundTasks, file: UploadFile = File(...)):
    return {"document": await _store_uploaded_document(file, background_tasks)}


@app.delete("/api/documents/{document_id}")
async def delete_document(document_id: str):
    if not await report_store.delete_document(document_id):
        raise HTTPException(status_code=404, detail='未找到文档')
    return {"success": True}


@app.post("/api/documents/{document_id}/reindex")
async def reindex_document(document_id: str, background_tasks: BackgroundTasks):
    document = await report_store.get_document(document_id)
    if document is None:
        raise HTTPException(status_code=404, detail='未找到文档')
    await report_store.mark_document_processing(document_id)
    background_tasks.add_task(index_document, report_store, document_id)
    return {"success": True, "status": "processing"}


@app.post("/api/multi_agents")
async def run_multi_agents():
    request = ResearchRequest(
        task="Is AI in a hype cycle?",
        report_type="multi_agents",
        report_source="web",
        tone="Objective",
        repo_name="",
        branch_name="",
        generate_in_background=False,
    )
    run = await report_store.create_run(
        query=request.task,
        report_type=request.report_type,
        report_source=request.report_source,
        tone=request.tone,
        request_options=request.model_dump(),
    )
    return await write_report(request, run)


@app.post("/upload/")
async def upload_file(background_tasks: BackgroundTasks, file: UploadFile = File(...)):
    document = await _store_uploaded_document(file, background_tasks)
    return {"filename": document["filename"], "id": document["id"], "status": document["status"]}


@app.delete("/files/{filename}")
async def delete_file(filename: str):
    raise HTTPException(status_code=410, detail="请使用 /api/documents/{document_id} 按文档 ID 删除；同名文件可能并存")


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await manager.connect(websocket)
    try:
        await handle_websocket_communication(websocket, manager)
    except WebSocketDisconnect as e:
        # Disconnect with more detailed logging about the WebSocket disconnect reason
        logger.info(f"WebSocket disconnected with code {e.code} and reason: '{e.reason}'")
        await manager.disconnect(websocket)
    except Exception as e:
        # More general exception handling
        logger.error(f"Unexpected WebSocket error: {str(e)}")
        await manager.disconnect(websocket)

@app.post("/api/chat")
async def chat(chat_request: ChatRequest):
    """Process a chat request with a report and message history.

    Args:
        chat_request: ChatRequest object containing report text and message history

    Returns:
        JSON response with the assistant's message and any tool usage metadata
    """
    try:
        logger.info(f"Received chat request with {len(chat_request.messages)} messages")

        # Create chat agent with the report
        chat_agent = ChatAgentWithMemory(
            report=chat_request.report,
            config_path="default",
            headers=None
        )

        # Process the chat and get response with metadata
        response_content, tool_calls_metadata = await chat_agent.chat(chat_request.messages, None)
        logger.info(f"response_content: {response_content}")
        logger.info(f"Got chat response of length: {len(response_content) if response_content else 0}")
        
        if tool_calls_metadata:
            logger.info(f"Tool calls used: {json.dumps(tool_calls_metadata)}")

        # Format response as a ChatMessage object with role, content, timestamp and metadata
        response_message = {
            "role": "assistant",
            "content": response_content,
            "timestamp": int(time.time() * 1000),  # Current time in milliseconds
            "metadata": {
                "tool_calls": tool_calls_metadata
            } if tool_calls_metadata else None
        }

        logger.info(f"Returning formatted response: {json.dumps(response_message)[:100]}...")
        return {"response": response_message}
    except Exception as e:
        logger.error(f"Error processing chat request: {str(e)}", exc_info=True)
        return {"error": str(e)}
