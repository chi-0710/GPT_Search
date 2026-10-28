from .utils.views import print_agent_output
from .utils.llms import call_model

class VisualizerAgent:
    def __init__(self, websocket=None, stream_output=None, headers=None):
        self.websocket = websocket
        self.stream_output = stream_output
        self.headers = headers or {}

    async def generate_visualizations(self, research_state: dict):
        task = research_state.get("task")
        data = research_state.get("research_data", [])
        
        prompt = [
            {
                "role": "system",
                "content": "你是一名数据可视化智能体。你的任务是从所提供的研究资料中提取关键数据点、流程或时间线，并生成一个相关的 Mermaid.js 图表（例如饼图、流程图或时间线），使报告更便于阅读。图表中的可见文字应使用简体中文，专有名词可保留原语言。",
            },
            {
                "role": "user",
                "content": f"研究资料：\n{data}\n\n请生成一个 Mermaid 图表代码块，呈现这些资料中最有价值或最具结构性的内容。必须使用标准 Markdown ```mermaid ... ``` 语法，并让图表中的可见文字使用简体中文。如果完全没有适合绘图的数据，请仅返回字符串 'None'。"
            }
        ]

        response = await call_model(prompt, model=task.get("model"))
        return response

    async def run(self, research_state: dict):
        if self.websocket and self.stream_output:
            await self.stream_output(
                "logs",
                "visualizing",
                f"正在为研究报告生成可视化图表...",
                self.websocket,
            )
        else:
            print_agent_output(f"Generating visualizations for the research report...", agent="VISUALIZER")

        diagram_output = await self.generate_visualizations(research_state)

        diagrams = []
        if "None" not in diagram_output:
            diagrams.append(diagram_output)
            if self.websocket and self.stream_output:
                await self.stream_output("logs", "visualizing", f"图表已生成。", self.websocket)
            else:
                print_agent_output(f"Generated diagrams.", agent="VISUALIZER")
        else:
            if self.websocket and self.stream_output:
                await self.stream_output("logs", "visualizing", '没有适合可视化的数据。', self.websocket)
            else:
                print_agent_output("No suitable data for visualizations.", agent="VISUALIZER")

        return {"diagrams": diagrams}
