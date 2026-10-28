<!-- prompt-migration-v1
stage: fact_checker
kind: user
source: multi_agents/agents/fact_checker.py
symbol: FactCheckerAgent.check_facts
lines: 10-31
variables: draft
defaults: {}
sha256: e2308ccae507f625f1f273441652c3d3c62df73a4f5e87d16a9adfe4673258fe
-->
请核查以下草稿的事实准确性：

${draft}

如果发现关键错误，请使用简体中文列出问题，以便撰写者修订。如果草稿在事实层面可靠，请仅返回字符串 'None'。接受草稿时不得返回任何其他文字。