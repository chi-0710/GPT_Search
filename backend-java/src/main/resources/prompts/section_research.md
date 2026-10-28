<!-- prompt-migration-v1
stage: section_research
kind: user
source: search/prompts.py
symbol: PromptFamily.generate_subtopic_report_prompt
lines: 461-535
variables: context,main_topic,current_subtopic,max_subsections,report_format,date,language,total_words,tone
defaults: {"language": "Chinese (Simplified)", "max_subsections": "5", "report_format": "APA", "total_words": "1200"}
sha256: 1c65338cc41499055bf80ad6e5859082dbbbe360c70457242fee6d5c6de2bca4
-->

上下文：
"${context}"

主要主题和子主题：
请使用最新可用信息，围绕主要主题“${main_topic}”下的子主题“${current_subtopic}”撰写详细报告。
子章节数量最多为 ${max_subsections} 个。

内容重点：
- 报告应聚焦回答问题，结构清晰、信息充分、分析深入，并在资料允许时包含事实和数字。
- 使用 Markdown 语法并遵循 ${report_format} 格式。
- 展示数据、比较内容或结构化信息时，使用 Markdown 表格提高可读性。

重要——内容和章节的唯一性：
- 这部分要求对于保证内容独特且不与已有报告重叠至关重要。
- 撰写任何新子章节前，仔细检查下面提供的已有标题和已有内容。
- 不得再次涵盖已有内容。
- 不得将任何已有标题用作新子章节标题。
- 不得重复已有内容或其高度相似的变体。
- 如果包含嵌套子章节，应确保其内容独特且未被已有内容涵盖。
- 确保本次内容全部为新内容，不与此前的子主题报告重叠。

“已有子主题报告”：
- 已有子主题报告及其章节标题：

    []

- 之前子主题报告中已有的正文内容：

    []

“结构和格式”：
- 本子报告将成为更大报告的一部分，因此只需包含按适当子主题划分的正文，不要添加引言或结论章节。

- 在报告中引用相关来源时，必须加入对应来源 URL 的 Markdown 超链接，例如：

    ### 章节标题

    这是一段示例文字（[文内引用](url)）。

- 主要子主题标题使用 H2（##），子章节使用 H3（###）。
- 使用较小的 Markdown 标题（如 H2 或 H3）组织内容，不要使用最大的 H1，因为 H1 将用于完整报告的标题。
- 将内容组织为彼此清晰的章节，使其补充已有报告但不与之重叠。
- 如果添加与已有子章节相似或相同的子章节，应明确说明新内容与之前子主题报告中已有内容的区别。例如：

    ### 新标题（与已有标题相似）

    前一章节讨论了[主题 A]，本章节将探讨[主题 B]。

“日期”：
如有需要，假定当前日期为 ${date}。

“重要”：
- 你必须使用以下语言撰写报告：${language}。当该语言为 Chinese (Simplified) 时，标题和正文均须使用简体中文。
- 必须聚焦主要主题，排除任何无关信息。
- 不得包含引言、结论、摘要或参考文献章节。
- 必须使用 ${report_format} 格式的文内引用，并将 Markdown 超链接放在引用该来源的句子或段落末尾，格式如下：([文内引用](url))。
- 如果添加相似或相同的子章节，必须在必要位置说明已有内容与新内容之间的区别。
- 报告篇幅至少为 ${total_words} 个词。
- 全文采用${tone}的语气。

不要添加结论章节。
