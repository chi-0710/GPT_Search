class HumanAgent:
    def __init__(self, websocket=None, stream_output=None, headers=None):
        self.websocket = websocket
        self.stream_output = stream_output
        self.headers = headers or {}

    async def review_plan(self, research_state: dict):
        print(f"HumanAgent websocket: {self.websocket}")
        print(f"HumanAgent stream_output: {self.stream_output}")
        task = research_state.get("task")
        layout = research_state.get("sections")

        user_feedback = None

        if task.get("include_human_feedback"):
            # Stream response to the user if a websocket is provided (such as from web app)
            if self.websocket and self.stream_output:
                try:
                    await self.stream_output(
                        "human_feedback",
                        "request",
                        f"你对以下研究主题规划有什么修改意见吗？{layout}。如果没有，请回复“无”。",
                        self.websocket,
                    )
                    # Feedback is read by the server's single WebSocket receive loop
                    # and forwarded here to avoid concurrent receive_text() calls.
                    raw_websocket = self.websocket.websocket
                    feedback_queue = getattr(raw_websocket.state, "human_feedback_queue", None)
                    if feedback_queue is None:
                        raise RuntimeError("Human feedback queue is not initialized")
                    user_feedback = await feedback_queue.get()
                except Exception as e:
                    raise RuntimeError("无法读取人工审核意见，请通过有效的 WebSocket 会话重试") from e
            # Otherwise, prompt the user for feedback in the console
            else:
                user_feedback = input(
                    f"你对以下规划有什么修改意见吗？{layout}。如果没有，请回复“无”。\n>> "
                )

        no_feedback_responses = {
            "no", "无", "没有", "没有反馈", "不需要", "无需修改"
        }
        if user_feedback and user_feedback.strip().lower() in no_feedback_responses:
            user_feedback = None

        plan_revision_count = research_state.get("plan_revision_count", 0)
        if user_feedback:
            plan_revision_count += 1

        print(f"User feedback before return: {user_feedback}")

        return {
            "human_feedback": user_feedback,
            "plan_revision_count": plan_revision_count,
        }
