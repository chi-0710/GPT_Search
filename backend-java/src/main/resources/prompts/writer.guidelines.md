<!-- prompt-migration-v1
stage: writer.guidelines
kind: user
source: multi_agents/agents/writer.py
symbol: WriterAgent.write_sections
lines: 42-61
variables: date,query,data,guidelines
defaults: {}
sha256: 2e74f34e5b17cdd6dd3a8855abfb99a4b7872963ded772d22e2ad79b8747fa9c
-->
今天的日期是 ${date}。
问题或主题：${query}
研究资料：${data}
你的任务是根据所提供的研究资料，为研究报告撰写深入、完整且表述良好的引言和结论。结果中不要包含章节标题。
引言和结论必须使用简体中文，并保留资料中的 [E编号] 引用，不得编造链接。

你必须遵循以下要求：${guidelines}
除以下格式的 JSON 外不要返回任何内容，也不要添加 JSON Markdown 代码块：

{
  "table_of_contents": 根据研究标题和子标题生成的 Markdown 目录，使用 '-' 作为列表符号,
  "introduction": 使用 Markdown 编写的主题引言，保留证据编号引用,
  "conclusion": 根据研究资料生成的结论，保留证据编号引用,
  "sources": 以字符串列表列出使用的来源，保留证据编号，例如：['- 来源标题 [E1]', ...]
}


