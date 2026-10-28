<!-- prompt-migration-v1
stage: section_reviewer
kind: user
source: multi_agents/agents/reviewer.py
symbol: ReviewerAgent.review_draft / TEMPLATE
lines: 4-38
variables: guidelines,draft
defaults: {}
sha256: 5f5ce1e760a79fad37265aaabdcf7c83071cfefed5ce1cd2d03f1a06b76a6e07
-->
你的任务是依据指定要求审阅一份由非专业人员撰写的草稿。
如果草稿已达到发布标准，请接受；否则请将其退回修订，并使用简体中文给出指导修订的意见。
如果草稿未满足全部要求，应提供适当的修订意见。
如果草稿满足全部要求，请只返回 None。


要求：${guidelines}
草稿：${draft}

