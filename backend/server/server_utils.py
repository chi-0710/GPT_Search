from search.utils.report_modes import normalize_report_mode
import asyncio
import json
import os
import traceback
from typing import Awaitable, Dict, Any
import logging


# Import chat agent
try:
    import sys
    backend_path = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if backend_path not in sys.path:
        sys.path.insert(0, backend_path)
    from chat.chat import ChatAgentWithMemory
except ImportError:
    ChatAgentWithMemory = None

logger = logging.getLogger(__name__)

class CustomLogsHandler:
    """Custom handler to capture streaming logs from the research process"""
    def __init__(self, websocket, task: str, *, store=None, run_id=None, research_id=None):
        self.websocket = websocket
        self.store = store
        self.run_id = run_id
        self.research_id = research_id

    async def send_json(self, data: Dict[str, Any], *, persist: bool = True) -> None:
        """Store log data and send to websocket"""
        if persist and self.store and self.run_id:
            await self.store.append_event(self.run_id, data)
        if self.websocket:
            try:
                await self.websocket.send_json(data)
            except Exception:
                logger.info("WebSocket delivery failed after event persistence")

async def handle_start_command(websocket, data: str, manager):
    json_data = json.loads(data[6:])
    json_data["report_type"] = normalize_report_mode(json_data.get("report_type"))
    (
        task,
        report_type,
        source_urls,
        document_urls,
        tone,
        headers,
        report_source,
        query_domains,
        mcp_enabled,
        mcp_strategy,
        mcp_configs,
        max_search_results,
    ) = extract_command_data(json_data)

    if not task or not report_type:
        print("Error: Missing task or report_type")
        return

    from .document_service import resolve_document_scope
    resolved_document_ids = await resolve_document_scope(manager.store, json_data)
    json_data["resolved_document_ids"] = resolved_document_ids
    run = await manager.store.create_run(
        query=task,
        report_type=report_type,
        report_source=report_source or "web",
        tone=tone or "Objective",
        request_options=json_data,
    )
    logs_handler = CustomLogsHandler(
        websocket,
        task,
        store=manager.store,
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
    try:
        result = await manager.start_streaming(
            task,
            report_type,
            report_source,
            source_urls,
            document_urls,
            tone,
            websocket,
            headers,
            query_domains,
            mcp_enabled,
            mcp_strategy,
            mcp_configs,
            max_search_results,
            logs_handler=logs_handler,
            run_id=run["run_id"],
            research_id=run["research_id"],
            document_ids=resolved_document_ids,
        )
        report = result.report
        sources = result.sources
        costs = result.costs
        file_paths = await generate_report_files(
            report,
            run["research_id"],
            store=manager.store,
            report_public_id=run["research_id"],
            run_id=run["run_id"],
        )
        path_event = {"type": "path", "output": file_paths}
        await manager.store.append_event(run["run_id"], path_event)
        await manager.store.complete_run(
            run["run_id"], answer=report, costs=costs, sources=sources
        )
        await logs_handler.send_json(path_event, persist=False)
    except asyncio.CancelledError:
        await manager.store.fail_run(run["run_id"], "Research task cancelled after client disconnect")
        try:
            await logs_handler.send_json({
                "type": "logs",
                "content": "error",
                "output": '客户端断开连接后，研究任务已取消',
            })
        except Exception:
            logger.warning("Could not publish cancellation event", exc_info=True)
        raise
    except Exception as exc:
        await logs_handler.send_json({
            "type": "logs", "content": "error", "output": f"错误：{exc}"
        })
        await manager.store.fail_run(run["run_id"], str(exc))


def parse_human_feedback(data: str) -> str:
    """Parse the structured feedback message sent by the web client."""
    payload = data.strip()
    legacy_prefix = "human_feedback"
    if payload.startswith(legacy_prefix):
        payload = payload[len(legacy_prefix):].lstrip()

    feedback_data = json.loads(payload)
    if not isinstance(feedback_data, dict) or feedback_data.get("type") != "human_feedback":
        raise ValueError('人工反馈消息无效')

    feedback = feedback_data.get("content")
    if not isinstance(feedback, str):
        raise ValueError('人工反馈内容必须是字符串')
    return feedback


def is_human_feedback_message(data: str) -> bool:
    try:
        parse_human_feedback(data)
        return True
    except (json.JSONDecodeError, TypeError, ValueError):
        return False


async def handle_human_feedback(data: str, websocket):
    feedback = parse_human_feedback(data)
    feedback_queue = getattr(websocket.state, "human_feedback_queue", None)
    if feedback_queue is None:
        raise RuntimeError('人工反馈队列尚未初始化')

    await feedback_queue.put(feedback)
    logger.info("Received human feedback")


async def handle_chat_command(websocket, data: str):
    """Handle chat command from WebSocket."""
    try:
        # Parse chat data - format is "chat {json_data}"
        json_str = data[5:].strip()  # Remove "chat " prefix
        chat_data = json.loads(json_str)
        
        message = chat_data.get("message", "")
        report = chat_data.get("report", "")
        messages = chat_data.get("messages", [])
        
        # If only message is provided, convert to messages format
        if message and not messages:
            messages = [{"role": "user", "content": message}]
        
        if not messages:
            await websocket.send_json({
                "type": "chat",
                "content": '未提供消息。',
                "role": "assistant"
            })
            return
        
        # Check if ChatAgentWithMemory is available
        if ChatAgentWithMemory is None:
            await websocket.send_json({
                "type": "chat",
                "content": '聊天功能不可用。请检查服务器配置。',
                "role": "assistant"
            })
            return
        
        # Create chat agent with the report context
        chat_agent = ChatAgentWithMemory(
            report=report,
            config_path="default",
            headers=None
        )
        
        # Process the chat
        response_content, tool_calls_metadata = await chat_agent.chat(messages, websocket)
        
        # Send response back via WebSocket
        await websocket.send_json({
            "type": "chat",
            "content": response_content,
            "role": "assistant",
            "metadata": {
                "tool_calls": tool_calls_metadata
            } if tool_calls_metadata else None
        })
        
        logger.info(f"Chat response sent successfully")
        
    except json.JSONDecodeError as e:
        logger.error(f"Failed to parse chat data: {e}")
        await websocket.send_json({
            "type": "chat",
            "content": f"错误：消息格式无效——{str(e)}",
            "role": "assistant"
        })
    except Exception as e:
        logger.error(f"Error handling chat command: {e}\n{traceback.format_exc()}")
        await websocket.send_json({
            "type": "chat",
            "content": f"处理你的消息时出错：{str(e)}",
            "role": "assistant"
        })

async def generate_report_files(
    report: str,
    filename: str,
    *,
    store,
    report_public_id: str,
    run_id: str,
) -> Dict[str, str]:
    from .artifact_service import persist_report_artifacts

    return await persist_report_artifacts(
        store,
        report_public_id=report_public_id,
        run_id=run_id,
        markdown=report,
        legacy_stem=filename,
    )


async def handle_websocket_communication(websocket, manager):
    running_task: asyncio.Task | None = None

    def run_long_running_task(awaitable: Awaitable) -> asyncio.Task:
        async def safe_run():
            try:
                await awaitable
            except asyncio.CancelledError:
                logger.info("Task cancelled.")
                raise
            except Exception as e:
                logger.error(f"Error running task: {e}\n{traceback.format_exc()}")
                await websocket.send_json(
                    {
                        "type": "logs",
                        "content": "error",
                        "output": f"错误：{e}",
                    }
                )

        return asyncio.create_task(safe_run())

    try:
        while True:
            try:
                data = await websocket.receive_text()
                logger.info(f"Received WebSocket message: {data[:50]}..." if len(data) > 50 else data)
                
                if data == "ping":
                    await websocket.send_text("pong")
                elif is_human_feedback_message(data):
                    logger.info("Processing human_feedback command")
                    await handle_human_feedback(data, websocket)
                elif running_task and not running_task.done():
                    # discard any new request if a task is already running
                    logger.warning(
                        f"Received request while task is already running. Request data preview: {data[: min(20, len(data))]}..."
                    )
                    await websocket.send_json(
                        {
                            "type": "logs",
                            "content": "warning",
                            "output": '任务正在运行，请稍候。',
                        }
                    )
                # Normalize command detection by checking startswith after stripping whitespace
                elif data.strip().startswith("start"):
                    logger.info(f"Processing start command")
                    running_task = run_long_running_task(
                        handle_start_command(websocket, data, manager)
                    )
                elif data.strip().startswith("chat"):
                    logger.info(f"Processing chat command")
                    running_task = run_long_running_task(handle_chat_command(websocket, data))
                else:
                    error_msg = f"Error: Unknown command or not enough parameters provided. Received: '{data[:100]}...'" if len(data) > 100 else f"Error: Unknown command or not enough parameters provided. Received: '{data}'"
                    logger.error(error_msg)
                    print(error_msg)
                    await websocket.send_json({
                        "type": "error",
                        "content": "error",
                        "output": '服务器收到未知命令'
                    })
            except Exception as e:
                logger.error(f"WebSocket error: {str(e)}\n{traceback.format_exc()}")
                print(f"WebSocket error: {e}")
                break
    finally:
        if running_task and not running_task.done():
            running_task.cancel()
            try:
                await running_task
            except asyncio.CancelledError:
                pass

def extract_command_data(json_data: Dict) -> tuple:
    return (
        json_data.get("task"),
        json_data.get("report_type"),
        json_data.get("source_urls"),
        json_data.get("document_urls"),
        json_data.get("tone") or "Objective",
        json_data.get("headers", {}),
        json_data.get("report_source") or "web",
        json_data.get("query_domains", []),
        json_data.get("mcp_enabled", False),
        json_data.get("mcp_strategy", "fast"),
        json_data.get("mcp_configs", []),
        json_data.get("max_search_results"),
    )
