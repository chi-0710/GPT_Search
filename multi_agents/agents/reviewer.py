from .utils.views import print_agent_output
from .utils.llms import call_model

TEMPLATE = """你是一名专业的研究文章审阅者。\
你的目标是仅依据指定要求审阅研究草稿，并使用简体中文向修订者提供反馈。\
"""


class ReviewerAgent:
    def __init__(self, websocket=None, stream_output=None, headers=None):
        self.websocket = websocket
        self.stream_output = stream_output
        self.headers = headers or {}

    async def review_draft(self, draft_state: dict):
        """
        Review a draft article
        :param draft_state:
        :return:
        """
        task = draft_state.get("task")
        guidelines = "- ".join(task.get("guidelines") or [])
        revision_notes = draft_state.get("revision_notes")

        revise_prompt = f"""修订者已根据你之前的审阅意见修改了草稿，并提供了以下反馈：
{revision_notes}\n
由于修订者已经根据之前的反馈做出修改，只有在存在关键问题时才提供额外反馈。
如果你认为文章已经足够完善，或只需要非关键修改，请返回 None。
"""

        review_prompt = f"""你的任务是依据指定要求审阅一份由非专业人员撰写的草稿。
如果草稿已达到发布标准，请接受；否则请将其退回修订，并使用简体中文给出指导修订的意见。
如果草稿未满足全部要求，应提供适当的修订意见。
如果草稿满足全部要求，请只返回 None。
{revise_prompt if revision_notes else ""}

要求：{guidelines}\n草稿：{draft_state.get("draft")}\n
"""
        prompt = [
            {"role": "system", "content": TEMPLATE},
            {"role": "user", "content": review_prompt},
        ]

        response = await call_model(prompt, model=task.get("model"))

        if task.get("verbose"):
            if self.websocket and self.stream_output:
                await self.stream_output(
                    "logs",
                    "review_feedback",
                    f"审阅反馈：{response}...",
                    self.websocket,
                )
            else:
                print_agent_output(
                    f"Review feedback is: {response}...", agent="REVIEWER"
                )

        from .utils.none_sentinels import is_none_accept_response

        if is_none_accept_response(response):
            return None
        return response

    async def run(self, draft_state: dict):
        task = draft_state.get("task")
        guidelines = task.get("guidelines")
        to_follow_guidelines = task.get("follow_guidelines")
        review = None
        if to_follow_guidelines and any(draft_state.get("draft", {}).values()):
            print_agent_output(f"Reviewing draft...", agent="REVIEWER")

            if task.get("verbose"):
                print_agent_output(
                    f"Following guidelines {guidelines}...", agent="REVIEWER"
                )

            review = await self.review_draft(draft_state)
        else:
            print_agent_output(f"Ignoring guidelines...", agent="REVIEWER")
        out = {"review": review}
        if review is not None:
            out["draft_revision_count"] = draft_state.get("draft_revision_count", 0) + 1
        return out
