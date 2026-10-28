from search import Research
from .utils.views import print_agent_output
from multi_agents.runtime import ResearchRuntime, DraftLogSink
from search.utils.evidence import InsufficientEvidenceError


class ResearchAgent:
    def __init__(self, websocket=None, stream_output=None, tone=None, headers=None, runtime=None):
        self.websocket = websocket
        self.stream_output = stream_output
        self.headers = headers or {}
        self.tone = tone
        self.runtime = runtime or ResearchRuntime()

    async def research(self, query: str, research_report: str = "research_report",
                       parent_query: str = "", verbose=True, source="web", tone=None, headers=None):
        # Initialize the researcher
        options = dict(self.runtime.options)
        max_results = options.pop("max_search_results", None)
        researcher = Research(query=query, report_type=research_report, parent_query=parent_query,
                              verbose=verbose, report_source=source, tone=tone,
                              websocket=DraftLogSink(self.websocket) if self.websocket else None,
                              headers=self.headers,
                              document_retriever=self.runtime.document_retriever,
                              evidence_registry=self.runtime.evidence_registry,
                              artifact_sink=self.runtime.artifact_sink, **options)
        if max_results is not None:
            researcher.cfg.max_search_results_per_query = max_results
        # Conduct research on the given query
        try:
            context = await researcher.conduct_research()
            if not context or not str(context).strip():
                raise InsufficientEvidenceError(f"资料不足：{query}")
            return await researcher.write_report()
        finally:
            self.runtime.collect(researcher)

    async def run_subtopic_research(self, parent_query: str, subtopic: str, verbose: bool = True, source="web", headers=None):
        try:
            report = await self.research(parent_query=parent_query, query=subtopic,
                                         research_report="subtopic_report", verbose=verbose, source=source, tone=self.tone, headers=None)
        except InsufficientEvidenceError:
            report = None
        return {subtopic: report}

    async def run_initial_research(self, research_state: dict):
        task = research_state.get("task")
        query = task.get("query")
        source = task.get("source", "web")

        if self.websocket and self.stream_output:
            await self.stream_output("logs", "initial_research", f"正在针对以下查询开展初步研究：{query}", self.websocket)
        else:
            print_agent_output(f"Running initial research on the following query: {query}", agent="RESEARCHER")
        return {"task": task, "initial_research": await self.research(query=query, verbose=task.get("verbose"),
                                                                      source=source, tone=self.tone, headers=self.headers)}

    async def run_depth_research(self, draft_state: dict):
        task = draft_state.get("task")
        topic = draft_state.get("topic")
        parent_query = task.get("query")
        source = task.get("source", "web")
        verbose = task.get("verbose")
        if self.websocket and self.stream_output:
            await self.stream_output("logs", "depth_research", f"正在对以下报告主题开展深入研究：{topic}", self.websocket)
        else:
            print_agent_output(f"Running in depth research on the following report topic: {topic}", agent="RESEARCHER")
        research_draft = await self.run_subtopic_research(parent_query=parent_query, subtopic=topic,
                                                          verbose=verbose, source=source, headers=self.headers)
        return {"draft": research_draft}
