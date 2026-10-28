from search.config.config import Config
from multi_agents.agents import ChiefEditorAgent

chief_editor = ChiefEditorAgent({
  "query": "Is AI in a hype cycle?",
  "max_sections": 3,
  "max_plan_revisions": 3,
  "follow_guidelines": False,
  "model": Config().strategic_llm_model,
  "guidelines": [
    "报告必须使用 APA 格式",
    "每个子章节都必须通过超链接提供支持性来源；如果没有可用来源，请删除该子章节，或将其改写为上一章节的一部分",
    "报告必须使用简体中文撰写"
  ],
  "verbose": False
}, websocket=None, stream_output=None)
graph = chief_editor.init_research_team()
graph = graph.compile()
