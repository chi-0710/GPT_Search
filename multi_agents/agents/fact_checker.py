from .utils.views import print_agent_output
from .utils.llms import call_model

class FactCheckerAgent:
    def __init__(self, websocket=None, stream_output=None, headers=None):
        self.websocket = websocket
        self.stream_output = stream_output
        self.headers = headers or {}

    async def check_facts(self, research_state: dict):
        task = research_state.get("task")
        
        # Assemble a draft representation for the fact checker
        intro = research_state.get("introduction", "")
        conclusion = research_state.get("conclusion", "")
        data = research_state.get("research_data", [])
        
        draft = f"引言：\n{intro}\n\n章节资料：\n{data}\n\n结论：\n{conclusion}"

        prompt = [
            {
                "role": "system",
                "content": "你是一名严谨的事实核查智能体。你的目标是依据所提供的研究上下文审查研究报告草稿，识别潜在的事实错误、幻觉或不一致，并决定是否需要退回撰写者修订。",
            },
            {
                "role": "user",
                "content": f"请核查以下草稿的事实准确性：\n\n{draft}\n\n如果发现关键错误，请使用简体中文列出问题，以便撰写者修订。如果草稿在事实层面可靠，请仅返回字符串 'None'。接受草稿时不得返回任何其他文字。"
            }
        ]

        response = await call_model(prompt, model=task.get("model"))
        return response

    async def run(self, research_state: dict):
        if self.websocket and self.stream_output:
            await self.stream_output(
                "logs",
                "fact_checking",
                f"正在核查合并草稿中的事实...",
                self.websocket,
            )
        else:
            print_agent_output(f"Checking facts in the assembled draft...", agent="FACT_CHECKER")

        review = await self.check_facts(research_state)

        from .utils.none_sentinels import is_none_accept_response

        if is_none_accept_response(review):
            review = None
            if self.websocket and self.stream_output:
                await self.stream_output("logs", "fact_checking", '草稿已通过事实核查。', self.websocket)
            else:
                print_agent_output("Draft accepted by Fact Checker.", agent="FACT_CHECKER")
        else:
            if self.websocket and self.stream_output:
                await self.stream_output("logs", "fact_checking", f"事实核查发现以下问题：{review}...", self.websocket)
            else:
                print_agent_output(f"Fact Checker found issues: {review}...", agent="FACT_CHECKER")

        out = {"fact_check_notes": review}
        if review is not None:
            out["fact_check_revision_count"] = research_state.get(
                "fact_check_revision_count", 0
            ) + 1
        return out
