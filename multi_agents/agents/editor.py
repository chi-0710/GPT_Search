from datetime import datetime
import asyncio
from typing import Dict, List, Optional

from langgraph.graph import StateGraph, END

from .utils.views import print_agent_output
from .utils.llms import call_model
from ..memory.draft import DraftState
from . import ResearchAgent, ReviewerAgent, ReviserAgent
from .draft_review import route_draft_review, DEFAULT_MAX_DRAFT_REVISIONS
from multi_agents.runtime import ResearchRuntime, gather_sections


class EditorAgent:
    """Agent responsible for editing and managing code."""

    def __init__(self, websocket=None, stream_output=None, tone=None, headers=None, runtime=None):
        self.websocket = websocket
        self.stream_output = stream_output
        self.tone = tone
        self.headers = headers or {}
        self.runtime = runtime or ResearchRuntime()

    async def plan_research(self, research_state: Dict[str, any]) -> Dict[str, any]:
        """
        Plan the research outline based on initial research and task parameters.

        :param research_state: Dictionary containing research state information
        :return: Dictionary with title, date, and planned sections
        """
        initial_research = research_state.get("initial_research")
        task = research_state.get("task")
        include_human_feedback = task.get("include_human_feedback")
        human_feedback = research_state.get("human_feedback")
        max_sections = task.get("max_sections", 3)

        prompt = self._create_planning_prompt(
            initial_research, include_human_feedback, human_feedback, max_sections)

        print_agent_output(
            "Planning an outline layout based on initial research...", agent="EDITOR")
        plan = await call_model(
            prompt=prompt,
            model=task.get("model"),
            response_format="json",
        )
        sections = plan.get("sections")
        if not isinstance(sections, list) or not sections or any(
            not isinstance(section, str) or not section.strip() for section in sections
        ):
            raise ValueError("规划未生成有效的研究章节")
        plan["sections"] = list(dict.fromkeys(section.strip() for section in sections))[:max_sections]

        return {
            "title": plan.get("title"),
            "date": plan.get("date"),
            "sections": plan.get("sections"),
        }

    async def run_parallel_research(self, research_state: Dict[str, any]) -> Dict[str, List[str]]:
        """
        Execute parallel research tasks for each section.

        :param research_state: Dictionary containing research state information
        :return: Dictionary with research results
        """
        workflow = self._create_workflow()
        chain = workflow.compile()

        queries = research_state.get("sections")
        title = research_state.get("title")

        await self._log_parallel_research(queries)

        final_drafts = [
            chain.ainvoke(self._create_task_input(
                research_state, query, title), config={"tags": ["gpt-researcher"]})
            for query in queries
        ]
        results = await gather_sections(final_drafts)
        research_results = [result["draft"] for result in results]
        if not any(any(value for value in draft.values()) for draft in research_results):
            raise ValueError("所有章节均未找到可用证据")
        research_results = [
            {key: value or "资料不足：本章节未检索到可用证据。" for key, value in draft.items()}
            for draft in research_results
        ]

        return {"research_data": research_results}

    def _create_planning_prompt(self, initial_research: str, include_human_feedback: bool,
                                human_feedback: Optional[str], max_sections: int) -> List[Dict[str, str]]:
        """Create the prompt for research planning."""
        return [
            {
                "role": "system",
                "content": "你是一名研究编辑，负责监督研究项目从启动到完成的全过程。"
                           "你的主要任务是根据初步研究摘要规划文章的章节结构，并确保标题和章节名称使用简体中文。\n ",
            },
            {
                "role": "user",
                "content": self._format_planning_instructions(initial_research, include_human_feedback,
                                                              human_feedback, max_sections),
            },
        ]

    def _format_planning_instructions(self, initial_research: str, include_human_feedback: bool,
                                      human_feedback: Optional[str], max_sections: int) -> str:
        """Format the instructions for research planning."""
        today = datetime.now().strftime('%d/%m/%Y')
        feedback_instruction = (
            f"人工反馈：{human_feedback}。你必须依据人工反馈规划章节。"
            if include_human_feedback and human_feedback and human_feedback != 'no'
            else ''
        )

        return f"""今天的日期是 {today}
                   研究摘要报告：'{initial_research}'
                   {feedback_instruction}
                   \n你的任务是根据上述研究摘要，为研究项目生成章节标题大纲。
                   最多生成 {max_sections} 个章节标题。
                   子标题只能聚焦相关研究主题，不得包含引言、结论和参考文献。
                   研究标题和所有章节标题必须使用简体中文；专有名词可保留原语言。
                   除 JSON 外不要返回任何内容。JSON 必须保留字段 'title'（字符串）、'date' 和
                   'sections'（最多 {max_sections} 个章节标题），结构如下：
                   '{{title: 字符串形式的研究标题, date: 今天的日期,
                   sections: ['章节标题 1', '章节标题 2', '章节标题 3' ...]}}'."""

    def _initialize_agents(self) -> Dict[str, any]:
        """Initialize the research, reviewer, and reviser skills."""
        return {
            "research": ResearchAgent(self.websocket, self.stream_output, self.tone, self.headers, self.runtime),
            "reviewer": ReviewerAgent(self.websocket, self.stream_output, self.headers),
            "reviser": ReviserAgent(self.websocket, self.stream_output, self.headers),
        }

    def _create_workflow(self) -> StateGraph:
        """Create the workflow for the research process."""
        agents = self._initialize_agents()
        workflow = StateGraph(DraftState)

        workflow.add_node("researcher", agents["research"].run_depth_research)
        workflow.add_node("reviewer", agents["reviewer"].run)
        workflow.add_node("reviser", agents["reviser"].run)

        workflow.set_entry_point("researcher")
        workflow.add_edge("researcher", "reviewer")
        workflow.add_edge("reviser", "reviewer")
        workflow.add_conditional_edges(
            "reviewer",
            self._route_draft_review,
            {"accept": END, "revise": "reviser"},
        )

        return workflow

    def _route_draft_review(self, state):
        if not any(state.get("draft", {}).values()):
            return "accept"
        return route_draft_review(
            state, state["task"].get("max_draft_revisions", DEFAULT_MAX_DRAFT_REVISIONS)
        )

    async def _log_parallel_research(self, queries: List[str]) -> None:
        """Log the start of parallel research tasks."""
        if self.websocket and self.stream_output:
            await self.stream_output(
                "logs",
                "parallel_research",
                f"正在针对以下查询并行开展研究：{queries}",
                self.websocket,
            )
        else:
            print_agent_output(
                f"Running the following research tasks in parallel: {queries}...",
                agent="EDITOR",
            )

    def _create_task_input(self, research_state: Dict[str, any], query: str, title: str) -> Dict[str, any]:
        """Create the input for a single research task."""
        return {
            "task": research_state.get("task"),
            "topic": query,
            "title": title,
            "headers": self.headers,
        }
