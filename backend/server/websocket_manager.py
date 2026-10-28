import os
import asyncio
import logging
import traceback
from typing import Dict, List

from fastapi import WebSocket


from search.utils.enum import Tone
from search.actions import stream_output  # Import stream_output
from .multi_agent_runner import run_multi_agent_task
from .server_utils import CustomLogsHandler
from .document_service import PostgresDocumentRetriever
from .postgres_store import PostgresStore
from search.utils.report_modes import normalize_report_mode

logger = logging.getLogger(__name__)

class WebSocketManager:
    """Manage websockets"""

    def __init__(self, store: PostgresStore | None = None):
        """Initialize the WebSocketManager class."""
        self.active_connections: List[WebSocket] = []
        self.sender_tasks: Dict[WebSocket, asyncio.Task] = {}
        self.message_queues: Dict[WebSocket, asyncio.Queue] = {}
        self.store = store or PostgresStore()

    async def start_sender(self, websocket: WebSocket):
        """Start the sender task."""
        queue = self.message_queues.get(websocket)
        if not queue:
            return

        while True:
            try:
                message = await queue.get()
                if message is None:  # Shutdown signal
                    break
                    
                if websocket in self.active_connections:
                    if message == "ping":
                        await websocket.send_text("pong")
                    else:
                        await websocket.send_text(message)
                else:
                    break
            except Exception as e:
                print(f"Error in sender task: {e}")
                break

    async def connect(self, websocket: WebSocket):
        """Connect a websocket."""
        try:
            await websocket.accept()
            self.active_connections.append(websocket)
            self.message_queues[websocket] = asyncio.Queue()
            websocket.state.human_feedback_queue = asyncio.Queue()
            self.sender_tasks[websocket] = asyncio.create_task(
                self.start_sender(websocket))
        except Exception as e:
            print(f"Error connecting websocket: {e}")
            if websocket in self.active_connections:
                await self.disconnect(websocket)

    async def disconnect(self, websocket: WebSocket):
        """Disconnect a websocket."""
        try:
            if websocket in self.active_connections:
                self.active_connections.remove(websocket)
                
                # Cancel sender task if it exists
                if websocket in self.sender_tasks:
                    try:
                        self.sender_tasks[websocket].cancel()
                        await self.message_queues[websocket].put(None)
                    except Exception as e:
                        logger.error(f"Error canceling sender task: {e}")
                    finally:
                        # Always try to clean up regardless of errors
                        if websocket in self.sender_tasks:
                            del self.sender_tasks[websocket]
                
                # Clean up message queue
                if websocket in self.message_queues:
                    del self.message_queues[websocket]
                
                # Finally close the WebSocket
                try:
                    await websocket.close()
                except Exception as e:
                    logger.info(f"WebSocket already closed: {e}")
        except Exception as e:
            logger.error(f"Error during WebSocket disconnection: {e}")
            # Still try to close the connection if possible
            try:
                await websocket.close()
            except Exception:
                pass  # If this fails too, there's nothing more we can do

    async def start_streaming(self, task, report_type, report_source, source_urls, document_urls, tone, websocket, headers=None, query_domains=None, mcp_enabled=False, mcp_strategy="fast", mcp_configs=None, max_search_results=None, *, logs_handler=None, run_id=None, research_id=None, document_ids=None):
        """Start streaming the output."""
        tone = Tone[tone]
        # add customized JSON config file path here
        config_path = os.environ.get("CONFIG_PATH", "default")

        async def artifact_sink(*, content, filename, mime_type, research_id=None):
            metadata = await self.store.put_artifact(
                content=content,
                filename=filename,
                kind="image",
                mime_type=mime_type,
                report_public_id=research_id_value,
                run_id=run_id,
                legacy_path=f"outputs/images/{research_id_value}/{filename}",
            )
            return metadata["url"]

        research_id_value = research_id

        # Pass MCP parameters to run_agent
        report = await run_agent(
            task, report_type, report_source, source_urls, document_urls, tone, websocket, 
            headers=headers, query_domains=query_domains or [], config_path=config_path,
            mcp_enabled=mcp_enabled, mcp_strategy=mcp_strategy, mcp_configs=mcp_configs,
            max_search_results=max_search_results,
            logs_handler=logs_handler,
            document_retriever=PostgresDocumentRetriever(self.store, document_ids=document_ids),
            artifact_sink=artifact_sink,
        )
        return report

async def run_agent(task, report_type, report_source, source_urls, document_urls, tone: Tone, websocket, stream_output=stream_output, headers=None, query_domains=None, config_path="", mcp_enabled=False, mcp_strategy="fast", mcp_configs=None, max_search_results=None, logs_handler=None, document_retriever=None, artifact_sink=None):
    """Run the agent."""    
    # Create logs handler for this research task
    logs_handler = logs_handler or CustomLogsHandler(websocket, task)
    query_domains = query_domains or []
    mcp_configs = mcp_configs or []

    # Log MCP initialization. Retriever and strategy are configured per-request
    # inside Research via mcp_configs/mcp_strategy params — no os.environ
    # mutation needed here (mutating os.environ would persist across requests and
    # affect unrelated sessions, see issue #1676).
    if mcp_enabled and mcp_configs:
        print(f"🔧 MCP enabled with strategy '{mcp_strategy}' and {len(mcp_configs)} server(s)")
        strategy_label = {"fast": "快速", "deep": "深度", "disabled": "禁用"}.get(mcp_strategy, mcp_strategy)
        await logs_handler.send_json({
            "type": "logs",
            "content": "mcp_init",
            "output": f"🔧 已启用 MCP，策略为“{strategy_label}”，共 {len(mcp_configs)} 个服务器"
        })

    report_type = normalize_report_mode(report_type)
    result = await run_multi_agent_task(
        query=task, report_type=report_type, report_source=report_source or "web",
        websocket=logs_handler, stream_output=stream_output, tone=tone, headers=headers,
        run_id=getattr(logs_handler, "run_id", None), config_path=config_path,
        query_domains=query_domains, source_urls=source_urls, document_urls=document_urls,
        document_retriever=document_retriever, artifact_sink=artifact_sink,
        mcp_configs=mcp_configs if mcp_enabled else None, mcp_strategy=mcp_strategy,
        max_search_results=max_search_results,
    )
    if getattr(logs_handler, "run_id", None):
        await logs_handler.store.save_evidence(logs_handler.run_id, result.evidence)
    for warning in result.warnings:
        await logs_handler.send_json({"type": "logs", "content": "citation_warning", "output": warning})
    await logs_handler.send_json({"type": "report_complete", "output": result.report})
    return result
