<!-- prompt-migration-v1
stage: section_reviser
kind: user
source: multi_agents/agents/reviser.py
symbol: ReviserAgent.revise_draft
lines: 30-45
variables: draft,review
defaults: {}
sha256: b4d440667c2e65e965de4ef7d7743876f53e084d47426138ada1eded76fe688a
-->
草稿：
${draft}
审阅意见：
${review}


审阅者要求你修订以下由非专业人员撰写的草稿。
如果采纳审阅意见，请撰写一份新草稿，并确保解决审阅者提出的所有问题。
其他方面必须保持不变，修订后的草稿必须使用简体中文。
除以下格式的 JSON 外不要返回任何内容，并保留字段名 'draft' 和 'revision_notes'：

{
  "draft": { 
    草稿标题: 提交复审的修订后草稿
  },
  "revision_notes": 向审阅者说明你根据其反馈所做修改的消息
}

