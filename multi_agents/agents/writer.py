from datetime import datetime
import json5 as json
from .utils.views import print_agent_output
from .utils.llms import call_model

sample_json = """
{
  "table_of_contents": 根据研究标题和子标题生成的 Markdown 目录，使用 '-' 作为列表符号,
  "introduction": 使用 Markdown 编写的主题引言，保留证据编号引用,
  "conclusion": 根据研究资料生成的结论，保留证据编号引用,
  "sources": 以字符串列表列出使用的来源，保留证据编号，例如：['- 来源标题 [E1]', ...]
}
"""


class WriterAgent:
    def __init__(self, websocket=None, stream_output=None, headers=None):
        self.websocket = websocket
        self.stream_output = stream_output
        self.headers = headers

    def get_headers(self, research_state: dict):
        return {
            "title": research_state.get("title"),
            "date": "日期",
            "introduction": "引言",
            "table_of_contents": "目录",
            "conclusion": "结论",
            "references": "参考文献",
        }

    async def write_sections(self, research_state: dict):
        query = research_state.get("title")
        data = research_state.get("research_data")
        task = research_state.get("task")
        follow_guidelines = task.get("follow_guidelines")
        guidelines = task.get("guidelines")

        fact_notes = research_state.get('fact_check_notes')
        fact_notes_str = f"事实核查意见：{fact_notes}。你必须修订引言和结论以解决这些问题。\n" if fact_notes else ""

        prompt = [
            {
                "role": "system",
                "content": "你是一名研究报告撰写者。你的唯一任务是依据研究发现和资料，"
                "围绕主题撰写高质量的简体中文研究报告。\n ",
            },
            {
                "role": "user",
                "content": f"今天的日期是 {datetime.now().strftime('%d/%m/%Y')}。\n"
                f"问题或主题：{query}\n"
                f"研究资料：{str(data)}\n"
                f"{fact_notes_str}"
                f"你的任务是根据所提供的研究资料，为研究报告撰写深入、完整且表述良好的引言和结论。"
                f"结果中不要包含章节标题。\n"
                f"引言和结论必须使用简体中文，并保留资料中的 [E编号] 引用，不得编造链接。\n\n"
                f"{f'你必须遵循以下要求：{guidelines}' if follow_guidelines else ''}\n"
                f"除以下格式的 JSON 外不要返回任何内容，也不要添加 JSON Markdown 代码块：\n"
                f"{sample_json}\n\n",
            },
        ]

        response = await call_model(
            prompt,
            task.get("model"),
            response_format="json",
        )
        return response

    async def revise_headers(self, task: dict, headers: dict):
        prompt = [
            {
                "role": "system",
                "content": """你是一名研究报告撰写者。
你的唯一任务是根据给定要求修订标题数据，并确保标题使用简体中文。""",
            },
            {
                "role": "user",
                "content": f"""你的任务是根据给定要求修订标题 JSON。
你必须遵循这些要求，但所有值都应是简单字符串，不使用任何 Markdown 语法，并使用简体中文。
除与所给标题数据格式完全相同的 JSON 外，不要返回任何内容；必须保留原有 JSON 字段名。
要求：{task.get("guidelines")}\n
标题数据：{headers}\n
""",
            },
        ]

        response = await call_model(
            prompt,
            task.get("model"),
            response_format="json",
        )
        return {"headers": response}

    async def run(self, research_state: dict):
        if self.websocket and self.stream_output:
            await self.stream_output(
                "logs",
                "writing_report",
                f"正在根据研究资料撰写最终研究报告...",
                self.websocket,
            )
        else:
            print_agent_output(
                f"Writing final research report based on research data...",
                agent="WRITER",
            )

        research_layout_content = await self.write_sections(research_state)

        if research_state.get("task").get("verbose"):
            if self.websocket and self.stream_output:
                research_layout_content_str = json.dumps(
                    research_layout_content, indent=2
                )
                await self.stream_output(
                    "logs",
                    "research_layout_content",
                    research_layout_content_str,
                    self.websocket,
                )
            else:
                print_agent_output(research_layout_content, agent="WRITER")

        headers = self.get_headers(research_state)
        if research_state.get("task").get("follow_guidelines"):
            if self.websocket and self.stream_output:
                await self.stream_output(
                    "logs",
                    "rewriting_layout",
                    '正在根据要求重新编排报告结构...',
                    self.websocket,
                )
            else:
                print_agent_output(
                    "Rewriting layout based on guidelines...", agent="WRITER"
                )
            headers = await self.revise_headers(
                task=research_state.get("task"), headers=headers
            )
            headers = headers.get("headers")

        return {**research_layout_content, "headers": headers}
