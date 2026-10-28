from .utils.views import print_agent_output
from .utils.llms import call_model
import json

sample_revision_notes = """
{
  "draft": { 
    草稿标题: 提交复审的修订后草稿
  },
  "revision_notes": 向审阅者说明你根据其反馈所做修改的消息
}
"""


class ReviserAgent:
    def __init__(self, websocket=None, stream_output=None, headers=None):
        self.websocket = websocket
        self.stream_output = stream_output
        self.headers = headers or {}

    async def revise_draft(self, draft_state: dict):
        """
        Review a draft article
        :param draft_state:
        :return:
        """
        review = draft_state.get("review")
        task = draft_state.get("task")
        draft_report = draft_state.get("draft")
        prompt = [
            {
                "role": "system",
                "content": "你是一名专业撰稿人，负责根据审阅意见修订草稿，并确保草稿和修订说明使用简体中文。",
            },
            {
                "role": "user",
                "content": f"""草稿：\n{draft_report}\n审阅意见：\n{review}\n\n
审阅者要求你修订以下由非专业人员撰写的草稿。
如果采纳审阅意见，请撰写一份新草稿，并确保解决审阅者提出的所有问题。
其他方面必须保持不变，修订后的草稿必须使用简体中文。
除以下格式的 JSON 外不要返回任何内容，并保留字段名 'draft' 和 'revision_notes'：
{sample_revision_notes}
""",
            },
        ]

        response = await call_model(
            prompt,
            model=task.get("model"),
            response_format="json",
        )
        return response

    async def run(self, draft_state: dict):
        print_agent_output(f"Rewriting draft based on feedback...", agent="REVISOR")
        revision = await self.revise_draft(draft_state)

        if draft_state.get("task").get("verbose"):
            if self.websocket and self.stream_output:
                await self.stream_output(
                    "logs",
                    "revision_notes",
                    f"修订说明：{revision.get('revision_notes')}",
                    self.websocket,
                )
            else:
                print_agent_output(
                    f"Revision notes: {revision.get('revision_notes')}", agent="REVISOR"
                )

        return {
            "draft": revision.get("draft"),
            "revision_notes": revision.get("revision_notes"),
        }
