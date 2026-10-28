<!-- prompt-migration-v1
stage: section_reviewer.revision
kind: user
source: multi_agents/agents/reviewer.py
symbol: ReviewerAgent.review_draft / TEMPLATE
lines: 4-38
variables: revision_notes,guidelines,draft
defaults: {}
sha256: 2e88673559627951e034c569b5a6a117f932a0fbd618b7b976283d2bb9044241
-->
你的任务是依据指定要求审阅一份由非专业人员撰写的草稿。
如果草稿已达到发布标准，请接受；否则请将其退回修订，并使用简体中文给出指导修订的意见。
如果草稿未满足全部要求，应提供适当的修订意见。
如果草稿满足全部要求，请只返回 None。
修订者已根据你之前的审阅意见修改了草稿，并提供了以下反馈：
${revision_notes}

由于修订者已经根据之前的反馈做出修改，只有在存在关键问题时才提供额外反馈。
如果你认为文章已经足够完善，或只需要非关键修改，请返回 None。


要求：${guidelines}
草稿：${draft}

