import os
import time
import datetime
from langgraph.graph import StateGraph, END
# from langgraph.checkpoint.memory import MemorySaver
from .utils.views import print_agent_output
from ..memory.research import ResearchState
from .utils.utils import sanitize_filename
from .plan_review import (
    DEFAULT_MAX_PLAN_REVISIONS,
    route_human_feedback,
)
from .fact_review import route_fact_check, DEFAULT_MAX_FACT_CHECK_REVISIONS
from multi_agents.runtime import ResearchRuntime, active_runtime, gather_sections

# Import agent classes
from . import \
    WriterAgent, \
    EditorAgent, \
    PublisherAgent, \
    ResearchAgent, \
    HumanAgent, \
    FactCheckerAgent, \
    VisualizerAgent


class ChiefEditorAgent:
    """Agent responsible for managing and coordinating editing tasks."""

    def __init__(self, task: dict, websocket=None, stream_output=None, tone=None, headers=None, output_dir=None, runtime=None):
        self.task = task
        self.websocket = websocket
        self.stream_output = stream_output
        self.headers = headers or {}
        self.tone = tone
        self.runtime = runtime or ResearchRuntime()
        self.task_id = self._generate_task_id()
        self.output_dir = self._create_output_directory(output_dir)

    def _generate_task_id(self):
        # Currently time based, but can be any unique identifier
        return int(time.time())

    def _create_output_directory(self, requested_output_dir=None):
        output_dir = requested_output_dir or (
            "./outputs/"
            + sanitize_filename(f"run_{self.task_id}_{self.task.get('query')[0:40]}")
        )

        os.makedirs(output_dir, exist_ok=True)
        return output_dir

    def _initialize_agents(self):
        return {
            "writer": WriterAgent(self.websocket, self.stream_output, self.headers),
            "editor": EditorAgent(self.websocket, self.stream_output, self.tone, self.headers, self.runtime),
            "research": ResearchAgent(self.websocket, self.stream_output, self.tone, self.headers, self.runtime),
            "publisher": PublisherAgent(self.output_dir, self.websocket, self.stream_output, self.headers),
            "human": HumanAgent(self.websocket, self.stream_output, self.headers),
            "fact_checker": FactCheckerAgent(self.websocket, self.stream_output, self.headers),
            "visualizer": VisualizerAgent(self.websocket, self.stream_output, self.headers)
        }

    def _create_workflow(self, agents):
        workflow = StateGraph(ResearchState)

        # Add nodes for each agent
        workflow.add_node("browser", agents["research"].run_initial_research)
        workflow.add_node("planner", agents["editor"].plan_research)
        workflow.add_node("researcher", agents["editor"].run_parallel_research)
        workflow.add_node("writer", agents["writer"].run)
        workflow.add_node("fact_checker", agents["fact_checker"].run)
        workflow.add_node("visualizer", agents["visualizer"].run)
        workflow.add_node("publisher", agents["publisher"].run)
        workflow.add_node("human", agents["human"].review_plan)
        workflow.add_node("repair_sections", self._repair_sections)

        # Add edges
        self._add_workflow_edges(workflow)

        return workflow

    def _add_workflow_edges(self, workflow):
        workflow.add_edge('browser', 'planner')
        workflow.add_edge('planner', 'human')
        workflow.add_edge('researcher', 'writer')
        workflow.add_edge('writer', 'fact_checker')
        workflow.add_edge('visualizer', 'publisher')
        workflow.set_entry_point("browser")
        workflow.add_edge('publisher', END)

        # Add human in the loop
        workflow.add_conditional_edges(
            'human',
            self._route_human_feedback,
            {"accept": "researcher", "revise": "planner"}
        )

        # Fact-checker loop — bounded via task.max_fact_check_revisions
        workflow.add_conditional_edges(
            'fact_checker',
            self._route_fact_check,
            {"accept": "visualizer", "revise": "repair_sections"}
        )
        workflow.add_edge("repair_sections", "writer")

    def _route_fact_check(self, state):
        return route_fact_check(
            state, self.task.get("max_fact_check_revisions", DEFAULT_MAX_FACT_CHECK_REVISIONS)
        )

    async def _repair_sections(self, state):
        """Apply fact-check notes to chapter bodies before rewriting the layout."""
        from .reviser import ReviserAgent
        reviser = ReviserAgent(self.websocket, self.stream_output, self.headers)
        revisions = await gather_sections([
            reviser.run({"task": state["task"], "draft": draft,
                         "review": state["fact_check_notes"]})
            for draft in state.get("research_data", [])
        ])
        return {"research_data": [item["draft"] for item in revisions]}

    def _route_human_feedback(self, review):
        max_plan_revisions = self.task.get(
            "max_plan_revisions", DEFAULT_MAX_PLAN_REVISIONS)
        return route_human_feedback(review, max_plan_revisions)

    def init_research_team(self):
        """Initialize and create a workflow for the research team."""
        agents = self._initialize_agents()
        return self._create_workflow(agents)

    async def _log_research_start(self):
        message = f"正在启动查询“{self.task.get('query')}”的研究流程..."
        if self.websocket and self.stream_output:
            await self.stream_output("logs", "starting_research", message, self.websocket)
        else:
            print_agent_output(message, "MASTER")

    async def run_research_task(self, task_id=None):
        """
        Run a research task with the initialized research team.

        Args:
            task_id (optional): The ID of the task to run.

        Returns:
            The result of the research task.
        """
        research_team = self.init_research_team()
        chain = research_team.compile()

        await self._log_research_start()

        config = {
            "configurable": {
                "thread_id": task_id,
                "thread_ts": datetime.datetime.utcnow()
            }
        }

        config["recursion_limit"] = 100
        token = active_runtime.set(self.runtime)
        try:
            return await chain.ainvoke({"task": self.task}, config=config)
        finally:
            active_runtime.reset(token)
