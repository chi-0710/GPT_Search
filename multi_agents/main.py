from dotenv import load_dotenv
import sys
import os
import uuid

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from multi_agents.agents import ChiefEditorAgent
import asyncio
import json
from search.utils.enum import Tone
from search.config.config import Config

# Tracing follows explicit environment settings; a key alone does not enable it.
load_dotenv()


def open_task():
    # Get the directory of the current script
    current_dir = os.path.dirname(os.path.abspath(__file__))
    # Construct the absolute path to task.json
    task_json_path = os.path.join(current_dir, "task.json")

    with open(task_json_path, "r") as f:
        task = json.load(f)

    if not task:
        raise Exception(
            "No task found. Please ensure a valid task.json file is present in the multi_agents directory and contains the necessary task information."
        )

    # Override model with STRATEGIC_LLM if defined in environment
    strategic_llm = os.environ.get("STRATEGIC_LLM")
    if strategic_llm and ":" in strategic_llm:
        # Extract the model name (part after the first colon)
        model_name = strategic_llm.split(":", 1)[1]
        task["model"] = model_name
    elif strategic_llm:
        task["model"] = strategic_llm
    else:
        cfg = Config()
        task["model"] = cfg.strategic_llm_model or cfg.smart_llm_model

    return task


async def run_research_task(
    query, websocket=None, stream_output=None, tone=Tone.Objective, headers=None,
    output_dir=None,
    runtime=None, report_source="web", task_options=None,
):
    task = open_task()
    task["query"] = query
    task["source"] = report_source
    task.update(task_options or {})
    # The server/CLI owns final export; Publisher only assembles Markdown.
    task.pop("publish_formats", None)

    chief_editor = ChiefEditorAgent(
        task, websocket, stream_output, tone, headers, output_dir=output_dir, runtime=runtime
    )
    research_report = await chief_editor.run_research_task()

    return research_report


async def main():
    """Use the same persisted execution path as the public CLI."""
    from cli import cli, main as run_cli
    task = open_task()
    await run_cli(cli.parse_args([task["query"], "--report_source", task.get("source", "web")]))


if __name__ == "__main__":
    asyncio.run(main())
