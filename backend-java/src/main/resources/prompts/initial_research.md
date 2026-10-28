<!-- prompt-migration-v1
stage: initial_research
kind: user
source: search/prompts.py
symbol: PromptFamily.generate_report_prompt
lines: 259-312
variables: context,question,total_words,report_format,tone,language,date
defaults: {"language": "Chinese (Simplified)", "report_format": "APA", "total_words": "1200"}
sha256: b403273560e58be149dcf6f8cf495d922baa4e90764c9f111b1bb39483282897
-->

资料："${context}"
---
请使用上述资料，以详细报告的形式回答以下问题或任务："${question}"。
报告应围绕问题的答案展开，结构清晰、信息充分、深入且全面；如资料中包含事实和数字，应加以使用；篇幅至少为 ${total_words} 个词。
请尽量充分利用所提供的所有相关且必要的信息，撰写尽可能完整的报告。

报告必须遵循以下全部要求：
- 你必须依据所给资料形成具体且有依据的观点，不要以笼统、空泛的结论敷衍。
- 你必须使用 Markdown 语法，并遵循 ${report_format} 格式撰写报告。
- 使用清晰的 Markdown 标题组织报告：主标题使用 #，主要章节使用 ##，子章节使用 ###。
- 展示结构化数据或比较内容时，使用 Markdown 表格提高可读性。
- 你必须优先考虑来源的相关性、可靠性和重要性，优先选择可信来源。
- 在来源可信的前提下，应优先采用较新的文章，而不是较旧的文章。
- 你不得包含目录，但必须使用正确的 Markdown 标题（#、##、###）清晰组织报告。
- 使用 ${report_format} 格式的文内引用，并将 Markdown 超链接放在引用该来源的句子或段落末尾，格式如下：([文内引用](url))。
- 每一项实质性论断、数据或引文都必须附带其来源的文内引用。不得引用所提供资料中没有出现的来源。
- 不要忘记在报告末尾按照 ${report_format} 格式添加参考文献列表。
- 
你必须在报告末尾以参考文献形式列出所有使用过的来源 URL，确保不添加重复来源，每个来源只列一次。
每个 URL 都应使用超链接格式：[网站链接](url)
此外，在报告正文引用相关来源时，也必须加入对应 URL 的超链接：

例如：作者。（年，月日）。网页标题。网站名称。[网站链接](url)

- 请采用${tone}的语气撰写报告。
你必须使用以下语言撰写整份报告：${language}。当该语言为 Chinese (Simplified) 时，标题和正文均须使用简体中文；专有名词、URL 和原文引用可保留原语言。
假定当前日期为 ${date}。
