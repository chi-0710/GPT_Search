# 知行研究助手（ResearchRunner）Web 研究流程英文文案：中文翻译对照（已实施）

整理日期：2026-09-19。用户已确认实施：A–F 的 176 处文案及 G 的中文显示适配已完成，协议和原始数据键保留。下方保留审批时的英文原文及译文供追溯；行号指向审批时的位置。Docker 后端尚未重新构建。

## 范围与结论

已整理 **176 处源码文本位置**，涉及 **26 个文件**，合并为 **155 组中英文对照**；另列 **22 个前端字段的中文展示名称**。

检查范围包括：研究 WebSocket 输出、进度事件、MCP 流式日志、多智能体日志、前端日志渲染，以及相邻的聊天、文档研究和报告接口错误显示路径。同一原文在多个位置出现时合并列出，全部位置保留链接；同一行内的不同条件分支文本分别计数。

这不是全仓库所有英文字符串的清单。代码注释、开发者控制台日志、测试数据、依赖库代码、历史报告和外部网页原文不属于本次文案翻译。静态扫描不能枚举第三方服务临时返回的英文错误或模型临时生成的英文内容。

## 拟议修改规则

- 只翻译显示文本；保留事件标识、JSON 键、参数、工具标识、URL、模型名及数据库值。
- 保留动态变量、条件、图片序号、结果数量、费用币种、数字精度、百分比和截断长度。费用的小数位过长属于另一项显示优化，本次不改变费用格式。
- 保留图标和换行。表中的 `\n` 表示真实换行，不是建议在网页上显示反斜杠。
- `MCP`、`Tavily`、`API`、`PostgreSQL` 等名称保留；快速和深度模式的自然语言说明翻译为中文。
- `{str(e)}`、用户查询、模型输出和外部来源标题等动态值不做机械翻译；只翻译外围固定句子，因此不能承诺页面绝无英文。
- 前端字段名建议通过中文展示映射处理，不直接重命名 `task` 等数据键。此项需要展示层适配，单列在 G 节。
- “相关接口”“可能进入上下文”中的文案不是每次研究都会显示；AG2 条目仅在对应独立实现启用时使用。

## 分组统计

| 范围 | 源码文本位置数 | 合并后的对照组数 |
| --- | ---: | ---: |
| A. 研究流程进度与多智能体状态 | 99 | 97 |
| B. MCP 状态与工具调用 | 24 | 24 |
| C. 浏览器连接、聊天和任务错误 | 14 | 13 |
| D. 相关接口错误（可能由页面展示） | 19 | 7 |
| E. 结果标题和引用包装（可能进入正文或上下文） | 10 | 5 |
| F. 文档研究错误、图片替代文字及聊天检索回退 | 10 | 9 |

部分前端视图会隐藏 `scraping_images`、`selected_images` 事件，但仍列入以覆盖其他使用这些事件的界面。MCP 通用辅助方法是否出现取决于实际调用路径。


## A. 研究流程进度与多智能体状态

| 编号 | 英文原文 | 中文译文（拟） | 源码位置 |
| --- | --- | --- | --- |
| 001 | `🌐 Scraping content from {len(urls)} URLs...` | `🌐 正在从 {len(urls)} 个网址抓取内容...` | [search/skills/browser.py:51](<F:/Desk/面试简历/gpt-researcher-main/search/skills/browser.py:51>) |
| 002 | `📄 Scraped {len(scraped_content)} pages of content` | `📄 已抓取 {len(scraped_content)} 个页面的内容` | [search/skills/browser.py:66](<F:/Desk/面试简历/gpt-researcher-main/search/skills/browser.py:66>) |
| 003 | `🖼️ Selected {len(new_images)} new images from {len(images)} total images` | `🖼️ 已从共 {len(images)} 张图片中选出 {len(new_images)} 张新图片` | [search/skills/browser.py:72](<F:/Desk/面试简历/gpt-researcher-main/search/skills/browser.py:72>) |
| 004 | `🌐 Scraping complete` | `🌐 内容抓取完成` | [search/skills/browser.py:80](<F:/Desk/面试简历/gpt-researcher-main/search/skills/browser.py:80>) |
| 005 | `📚 Getting relevant content based on query: {query}...` | `📚 正在根据查询检索相关内容：{query}...` | [search/skills/context_manager.py:51](<F:/Desk/面试简历/gpt-researcher-main/search/skills/context_manager.py:51>) |
| 006 | ` Getting relevant content based on query: {query}...` | ` 正在根据查询检索相关内容：{query}...` | [search/skills/context_manager.py:81](<F:/Desk/面试简历/gpt-researcher-main/search/skills/context_manager.py:81>) |
| 007 | `🔎 Getting relevant written content based on query: {query}...` | `🔎 正在根据查询检索已撰写的相关内容：{query}...` | [search/skills/context_manager.py:144](<F:/Desk/面试简历/gpt-researcher-main/search/skills/context_manager.py:144>) |
| 008 | `⚖️ Evaluating and curating sources by credibility and relevance...` | `⚖️ 正在根据可信度和相关性评估并筛选来源...` | [search/skills/curator.py:54](<F:/Desk/面试简历/gpt-researcher-main/search/skills/curator.py:54>) |
| 009 | `🏅 Verified and ranked top {len(curated_sources)} most reliable sources` | `🏅 已核验并排序最可靠的前 {len(curated_sources)} 个来源` | [search/skills/curator.py:81](<F:/Desk/面试简历/gpt-researcher-main/search/skills/curator.py:81>) |
| 010 | `🚫 Source verification failed: {str(e)}` | `🚫 来源核验失败：{str(e)}` | [search/skills/curator.py:93](<F:/Desk/面试简历/gpt-researcher-main/search/skills/curator.py:93>) |
| 011 | `🎨 Analyzing research context for visualization opportunities...` | `🎨 正在分析研究上下文，寻找适合可视化的内容...` | [search/skills/image_generator.py:110](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:110>) |
| 012 | `🖼️ Identified {len(image_concepts)} visualization concepts, generating images...` | `🖼️ 已识别 {len(image_concepts)} 个可视化概念，正在生成图片...` | [search/skills/image_generator.py:125](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:125>) |
| 013 | `🖼️ Generating image {index + 1}/{len(image_concepts)}: {concept['title'][:50]}...` | `🖼️ 正在生成图片 {index + 1}/{len(image_concepts)}：{concept['title'][:50]}...` | [search/skills/image_generator.py:139](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:139>) |
| 014 | `✅ {len(generated_images)} images ready for report embedding` | `✅ {len(generated_images)} 张图片已准备好，可嵌入报告` | [search/skills/image_generator.py:174](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:174>) |
| 015 | `⚠️ No images could be generated` | `⚠️ 未能生成任何图片` | [search/skills/image_generator.py:181](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:181>) |
| 016 | `🎨 Analyzing report for image generation opportunities...` | `🎨 正在分析报告，寻找适合配图的内容...` | [search/skills/image_generator.py:500](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:500>) |
| 017 | `📝 No sections identified that would benefit from images` | `📝 未发现适合配图的章节` | [search/skills/image_generator.py:513](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:513>) |
| 018 | `🔍 Found {len(suggestions)} sections that would benefit from images` | `🔍 已找到 {len(suggestions)} 个适合配图的章节` | [search/skills/image_generator.py:522](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:522>) |
| 019 | `🖼️ Generating image {i+1}/{len(suggestions)}: {suggestion['section_header'][:50]}...` | `🖼️ 正在生成图片 {i+1}/{len(suggestions)}：{suggestion['section_header'][:50]}...` | [search/skills/image_generator.py:533](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:533>) |
| 020 | `✅ Image generated for: {suggestion['section_header'][:50]}` | `✅ 已为以下章节生成图片：{suggestion['section_header'][:50]}` | [search/skills/image_generator.py:554](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:554>) |
| 021 | `⚠️ Failed to generate image: {str(e)[:100]}` | `⚠️ 图片生成失败：{str(e)[:100]}` | [search/skills/image_generator.py:563](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:563>) |
| 022 | `🎉 Successfully generated and embedded {len(generated_images)} images` | `🎉 已成功生成并嵌入 {len(generated_images)} 张图片` | [search/skills/image_generator.py:576](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:576>) |
| 023 | `🎨 Found {len(placeholders)} image placeholders to process` | `🎨 发现 {len(placeholders)} 个待处理的图片占位符` | [search/skills/image_generator.py:683](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:683>) |
| 024 | `🖼️ Generating image {i+1}/{len(placeholders)}: {image_description[:60]}...` | `🖼️ 正在生成图片 {i+1}/{len(placeholders)}：{image_description[:60]}...` | [search/skills/image_generator.py:698](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:698>) |
| 025 | `✅ Generated: {image_description[:40]}...` | `✅ 已生成：{image_description[:40]}...` | [search/skills/image_generator.py:730](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:730>) |
| 026 | `⚠️ Failed to generate: {str(e)[:80]}` | `⚠️ 生成失败：{str(e)[:80]}` | [search/skills/image_generator.py:747](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:747>) |
| 027 | `🎉 Successfully generated {len(generated_images)} inline images` | `🎉 已成功生成 {len(generated_images)} 张文内图片` | [search/skills/image_generator.py:762](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:762>) |
| 028 | `🌐 Browsing the web to learn more about the task: {query}...` | `🌐 正在浏览网页，以进一步了解任务：{query}...` | [search/skills/researcher.py:61](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:61>) |
| 029 | `🤔 Planning the research strategy and subtasks...` | `🤔 正在规划研究策略和子任务...` | [search/skills/researcher.py:77](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:77>) |
| 030 | `🔍 Starting the research task for '{self.researcher.query}'...` | `🔍 正在启动“{self.researcher.query}”的研究任务...` | [search/skills/researcher.py:119](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:119>) |
| 031 | `🧐 I was unable to find relevant context in the provided sources...` | `🧐 未能在提供的来源中找到相关上下文...` | [search/skills/researcher.py:156](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:156>) |
| 032 | `Finalized research step.\n💸 Total Research Costs: ${self.researcher.get_costs()}` | `研究步骤已完成。\n💸 研究总费用：${self.researcher.get_costs()}` | [search/skills/researcher.py:258](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:258>) |
| 033 | `🗂️  I will conduct my research based on the following queries: {sub_queries}...` | `🗂️  将围绕以下查询开展研究：{sub_queries}...` | [search/skills/researcher.py:345](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:345>) |
| 034 | `⚡ MCP research disabled by configuration` | `⚡ 已根据配置禁用 MCP 研究` | [search/skills/researcher.py:390](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:390>) |
| 035 | `🚀 MCP Fast: Running once for main query (performance mode)` | `🚀 MCP 快速模式：仅针对主查询执行一次（性能优先模式）` | [search/skills/researcher.py:400](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:400>) |
| 036 | `🔍 MCP Deep: Will run for each sub-query (thorough mode)` | `🔍 MCP 深度模式：将对每个子查询分别执行（全面研究模式）` | [search/skills/researcher.py:415](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:415>) |
| 037 | `🗂️ I will conduct my research based on the following queries: {sub_queries}...` | `🗂️ 将围绕以下查询开展研究：{sub_queries}...` | [search/skills/researcher.py:438](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:438>) |
| 038 | `✅ Cached {len(mcp_results)} MCP results from query {i}/{len(queries)}` | `✅ 已缓存查询 {i}/{len(queries)} 的 {len(mcp_results)} 条 MCP 结果` | [search/skills/researcher.py:531](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:531>) |
| 039 | `⚠️ MCP research error for query {i}, continuing with other sources` | `⚠️ 查询 {i} 的 MCP 研究出错，将继续使用其他来源` | [search/skills/researcher.py:540](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:540>) |
| 040 | `\n🔍 Running research for '{sub_query}'...` | `\n🔍 正在研究“{sub_query}”...` | [search/skills/researcher.py:586](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:586>)<br>[search/skills/researcher.py:867](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:867>) |
| 041 | `⚠️ Skipping Tavily MCP (redundant with direct Tavily retriever) to avoid double API cost` | `⚠️ 正在跳过 Tavily MCP（与直接调用 Tavily 检索器重复），以避免重复的 API 费用` | [search/skills/researcher.py:604](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:604>) |
| 042 | `♻️ Reusing cached MCP results ({len(mcp_context)} sources) for: {sub_query}` | `♻️ 正在为以下查询复用缓存的 MCP 结果（{len(mcp_context)} 个来源）：{sub_query}` | [search/skills/researcher.py:629](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:629>) |
| 043 | `🔍 Running deep MCP research for: {sub_query}` | `🔍 正在对以下查询开展 MCP 深度研究：{sub_query}` | [search/skills/researcher.py:641](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:641>) |
| 044 | `🔌 MCP cache unavailable, running MCP research for: {sub_query}` | `🔌 MCP 缓存不可用，正在对以下查询执行 MCP 研究：{sub_query}` | [search/skills/researcher.py:653](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:653>) |
| 045 | ` (cached)` | `（来自缓存）` | [search/skills/researcher.py:681](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:681>) |
| 046 | `📚 Combined research context: {mcp_count} MCP sources{cache_status}, {'web content' if web_available else 'no web content'}` | `📚 已合并研究上下文：{mcp_count} 个 MCP 来源{cache_status}，{'网络内容' if web_available else '无网络内容'}` | [search/skills/researcher.py:685](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:685>) |
| 047 | `web content` | `网络内容` | [search/skills/researcher.py:685](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:685>) |
| 048 | `no web content` | `无网络内容` | [search/skills/researcher.py:685](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:685>) |
| 049 | `🤷 No content found for '{sub_query}'...` | `🤷 未找到“{sub_query}”的相关内容...` | [search/skills/researcher.py:694](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:694>) |
| 050 | `❌ Error processing '{sub_query}': {str(e)}` | `❌ 处理“{sub_query}”时出错：{str(e)}` | [search/skills/researcher.py:714](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:714>) |
| 051 | `🧠 Stage 1: Selecting optimal MCP tools for: {query}` | `🧠 阶段 1：正在为以下查询选择最合适的 MCP 工具：{query}` | [search/skills/researcher.py:749](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:749>) |
| 052 | `🎯 MCP research completed: {result_count} intelligent results obtained` | `🎯 MCP 研究已完成：获得 {result_count} 条智能研究结果` | [search/skills/researcher.py:766](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:766>) |
| 053 | `ℹ️ No relevant information found via MCP for: {query}` | `ℹ️ 未通过 MCP 找到以下查询的相关信息：{query}` | [search/skills/researcher.py:777](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:777>) |
| 054 | `⚠️ MCP research error: {str(e)} - continuing with other sources` | `⚠️ MCP 研究出错：{str(e)}——将继续使用其他来源` | [search/skills/researcher.py:788](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:788>) |
| 055 | `✅ Added source url to research: {url}\n` | `✅ 已将来源网址加入研究：{url}\n` | [search/skills/researcher.py:890](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:890>) |
| 056 | `🤔 Researching for relevant information across multiple sources...\n` | `🤔 正在从多个来源研究相关信息...\n` | [search/skills/researcher.py:991](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:991>) |
| 057 | `🔌 Consulting MCP server(s) for information on: {query}` | `🔌 正在向 MCP 服务器查询以下主题的信息：{query}` | [search/skills/researcher.py:1037](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:1037>) |
| 058 | `✓ Retrieved {result_count} results from MCP server` | `✓ 已从 MCP 服务器检索到 {result_count} 条结果` | [search/skills/researcher.py:1058](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:1058>) |
| 059 | `ℹ️ No relevant information found from MCP server for: {query}` | `ℹ️ 未从 MCP 服务器找到以下查询的相关信息：{query}` | [search/skills/researcher.py:1077](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:1077>) |
| 060 | `❌ Error retrieving information from MCP server: {str(e)}` | `❌ 从 MCP 服务器检索信息时出错：{str(e)}` | [search/skills/researcher.py:1091](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:1091>) |
| 061 | `📊 Research Progress: {progress}%` | `📊 研究进度：{progress}%` | [search/skills/researcher.py:1170](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:1170>) |
| 062 | `🖼️ {len(available_images)} pre-generated images available for embedding` | `🖼️ 有 {len(available_images)} 张预先生成的图片可供嵌入` | [search/skills/writer.py:96](<F:/Desk/面试简历/gpt-researcher-main/search/skills/writer.py:96>) |
| 063 | `✍️ Writing report for '{self.researcher.query}'...` | `✍️ 正在撰写“{self.researcher.query}”的报告...` | [search/skills/writer.py:104](<F:/Desk/面试简历/gpt-researcher-main/search/skills/writer.py:104>) |
| 064 | `📝 Report written for '{self.researcher.query}'` | `📝 已完成“{self.researcher.query}”的报告撰写` | [search/skills/writer.py:133](<F:/Desk/面试简历/gpt-researcher-main/search/skills/writer.py:133>) |
| 065 | `✍️ Writing conclusion for '{self.researcher.query}'...` | `✍️ 正在撰写“{self.researcher.query}”的结论...` | [search/skills/writer.py:153](<F:/Desk/面试简历/gpt-researcher-main/search/skills/writer.py:153>) |
| 066 | `📝 Conclusion written for '{self.researcher.query}'` | `📝 已完成“{self.researcher.query}”的结论撰写` | [search/skills/writer.py:172](<F:/Desk/面试简历/gpt-researcher-main/search/skills/writer.py:172>) |
| 067 | `✍️ Writing introduction for '{self.researcher.query}'...` | `✍️ 正在撰写“{self.researcher.query}”的引言...` | [search/skills/writer.py:184](<F:/Desk/面试简历/gpt-researcher-main/search/skills/writer.py:184>) |
| 068 | `📝 Introduction written for '{self.researcher.query}'` | `📝 已完成“{self.researcher.query}”的引言撰写` | [search/skills/writer.py:203](<F:/Desk/面试简历/gpt-researcher-main/search/skills/writer.py:203>) |
| 069 | `🌳 Generating subtopics for '{self.researcher.query}'...` | `🌳 正在为“{self.researcher.query}”生成子主题...` | [search/skills/writer.py:215](<F:/Desk/面试简历/gpt-researcher-main/search/skills/writer.py:215>) |
| 070 | `📊 Subtopics generated for '{self.researcher.query}'` | `📊 已为“{self.researcher.query}”生成子主题` | [search/skills/writer.py:232](<F:/Desk/面试简历/gpt-researcher-main/search/skills/writer.py:232>) |
| 071 | `📑 Generating draft section titles for '{self.researcher.query}'...` | `📑 正在为“{self.researcher.query}”生成章节标题草案...` | [search/skills/writer.py:244](<F:/Desk/面试简历/gpt-researcher-main/search/skills/writer.py:244>) |
| 072 | `🗂️ Draft section titles generated for '{self.researcher.query}'` | `🗂️ 已为“{self.researcher.query}”生成章节标题草案` | [search/skills/writer.py:264](<F:/Desk/面试简历/gpt-researcher-main/search/skills/writer.py:264>) |
| 073 | `Running in depth research on topic: {topic}` | `正在对以下主题开展深入研究：{topic}` | [multi_agents/ag2/agents/orchestrator.py:140](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/ag2/agents/orchestrator.py:140>) |
| 074 | `Reviewing draft...` | `正在审阅草稿...` | [multi_agents/ag2/agents/orchestrator.py:153](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/ag2/agents/orchestrator.py:153>) |
| 075 | `Revising draft based on feedback...` | `正在根据反馈修订草稿...` | [multi_agents/ag2/agents/orchestrator.py:159](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/ag2/agents/orchestrator.py:159>) |
| 076 | `Starting the research process for query '{self.task.get('query')}'...` | `正在启动查询“{self.task.get('query')}”的研究流程...` | [multi_agents/ag2/agents/orchestrator.py:178](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/ag2/agents/orchestrator.py:178>)<br>[multi_agents/agents/orchestrator.py:118](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/orchestrator.py:118>) |
| 077 | `Initial research complete.` | `初步研究已完成。` | [multi_agents/ag2/agents/orchestrator.py:182](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/ag2/agents/orchestrator.py:182>) |
| 078 | `Planned sections: {plan.get('sections')}` | `已规划的章节：{plan.get('sections')}` | [multi_agents/ag2/agents/orchestrator.py:186](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/ag2/agents/orchestrator.py:186>) |
| 079 | `Human feedback: {human_feedback.get('human_feedback')}` | `人工反馈：{human_feedback.get('human_feedback')}` | [multi_agents/ag2/agents/orchestrator.py:190](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/ag2/agents/orchestrator.py:190>) |
| 080 | `Revised sections: {plan.get('sections')}` | `修订后的章节：{plan.get('sections')}` | [multi_agents/ag2/agents/orchestrator.py:193](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/ag2/agents/orchestrator.py:193>) |
| 081 | `Writing final report...` | `正在撰写最终报告...` | [multi_agents/ag2/agents/orchestrator.py:209](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/ag2/agents/orchestrator.py:209>) |
| 082 | `Publishing final report...` | `正在发布最终报告...` | [multi_agents/ag2/agents/orchestrator.py:213](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/ag2/agents/orchestrator.py:213>) |
| 083 | `Research task completed.` | `研究任务已完成。` | [multi_agents/ag2/agents/orchestrator.py:215](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/ag2/agents/orchestrator.py:215>) |
| 084 | `Running parallel research for the following queries: {queries}` | `正在针对以下查询并行开展研究：{queries}` | [multi_agents/agents/editor.py:151](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/editor.py:151>) |
| 085 | `Checking facts in the assembled draft...` | `正在核查合并草稿中的事实...` | [multi_agents/agents/fact_checker.py:39](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/fact_checker.py:39>) |
| 086 | `Draft accepted by Fact Checker.` | `草稿已通过事实核查。` | [multi_agents/agents/fact_checker.py:52](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/fact_checker.py:52>) |
| 087 | `Fact Checker found issues: {review}...` | `事实核查发现以下问题：{review}...` | [multi_agents/agents/fact_checker.py:57](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/fact_checker.py:57>) |
| 088 | `Publishing final research report based on retrieved data...` | `正在根据检索到的资料发布最终研究报告...` | [multi_agents/agents/publisher.py:73](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/publisher.py:73>) |
| 089 | `Running initial research on the following query: {query}` | `正在针对以下查询开展初步研究：{query}` | [multi_agents/agents/researcher.py:40](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/researcher.py:40>) |
| 090 | `Running in depth research on the following report topic: {topic}` | `正在对以下报告主题开展深入研究：{topic}` | [multi_agents/agents/researcher.py:53](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/researcher.py:53>) |
| 091 | `Review feedback is: {response}...` | `审阅反馈：{response}...` | [multi_agents/agents/reviewer.py:51](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/reviewer.py:51>) |
| 092 | `Revision notes: {revision.get('revision_notes')}` | `修订说明：{revision.get('revision_notes')}` | [multi_agents/agents/reviser.py:63](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/reviser.py:63>) |
| 093 | `Generating visualizations for the research report...` | `正在为研究报告生成可视化图表...` | [multi_agents/agents/visualizer.py:33](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/visualizer.py:33>) |
| 094 | `Generated diagrams.` | `图表已生成。` | [multi_agents/agents/visualizer.py:45](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/visualizer.py:45>) |
| 095 | `No suitable data for visualizations.` | `没有适合可视化的数据。` | [multi_agents/agents/visualizer.py:50](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/visualizer.py:50>) |
| 096 | `Writing final research report based on research data...` | `正在根据研究资料撰写最终研究报告...` | [multi_agents/agents/writer.py:101](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/writer.py:101>) |
| 097 | `Rewriting layout based on guidelines...` | `正在根据要求重新编排报告结构...` | [multi_agents/agents/writer.py:132](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/writer.py:132>) |

## B. MCP 状态与工具调用

| 编号 | 英文原文 | 中文译文（拟） | 源码位置 |
| --- | --- | --- | --- |
| 098 | `✅ {stage} completed: {result_count} results` | `✅ {stage}已完成：{result_count} 条结果` | [search/mcp/streaming.py:73](<F:/Desk/面试简历/gpt-researcher-main/search/mcp/streaming.py:73>) |
| 099 | `✅ {stage} completed` | `✅ {stage}已完成` | [search/mcp/streaming.py:75](<F:/Desk/面试简历/gpt-researcher-main/search/mcp/streaming.py:75>) |
| 100 | `🧠 Using LLM to select {selected_count} most relevant tools from {total_count} available` | `🧠 正在使用大语言模型，从 {total_count} 个可用工具中选择 {selected_count} 个最相关的工具` | [search/mcp/streaming.py:79](<F:/Desk/面试简历/gpt-researcher-main/search/mcp/streaming.py:79>) |
| 101 | `🔍 Executing tool {step}/{total}: {tool_name}` | `🔍 正在执行工具 {step}/{total}：{tool_name}` | [search/mcp/streaming.py:83](<F:/Desk/面试简历/gpt-researcher-main/search/mcp/streaming.py:83>) |
| 102 | `✅ MCP research completed: {result_count} results obtained ({total_chars:,} chars)` | `✅ MCP 研究已完成：获得 {result_count} 条结果（{total_chars:,} 个字符）` | [search/mcp/streaming.py:88](<F:/Desk/面试简历/gpt-researcher-main/search/mcp/streaming.py:88>) |
| 103 | `✅ MCP research completed: {result_count} results obtained` | `✅ MCP 研究已完成：获得 {result_count} 条结果` | [search/mcp/streaming.py:90](<F:/Desk/面试简历/gpt-researcher-main/search/mcp/streaming.py:90>) |
| 104 | `🔧 Initializing MCP retriever for query: {self.query}` | `🔧 正在为查询初始化 MCP 检索器：{self.query}` | [search/retrievers/mcp/retriever.py:85](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:85>) |
| 105 | `🔧 Found {len(self.mcp_configs)} MCP server configurations` | `🔧 找到 {len(self.mcp_configs)} 个 MCP 服务器配置` | [search/retrievers/mcp/retriever.py:86](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:86>) |
| 106 | `❌ CRITICAL: No MCP server configurations found. Please check documentation.` | `❌ 严重错误：未找到 MCP 服务器配置。请查阅文档。` | [search/retrievers/mcp/retriever.py:89](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:89>) |
| 107 | `MCP retriever cannot proceed without server configurations.` | `缺少服务器配置，MCP 检索器无法继续运行。` | [search/retrievers/mcp/retriever.py:130](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:130>) |
| 108 | `Stage 1` | `阶段 1` | [search/retrievers/mcp/retriever.py:138](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:138>) |
| 109 | `Getting all available MCP tools` | `正在获取所有可用的 MCP 工具` | [search/retrievers/mcp/retriever.py:138](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:138>) |
| 110 | `No MCP tools available, skipping MCP research` | `没有可用的 MCP 工具，跳过 MCP 研究` | [search/retrievers/mcp/retriever.py:142](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:142>) |
| 111 | `Stage 2` | `阶段 2` | [search/retrievers/mcp/retriever.py:146](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:146>) |
| 112 | `Selecting most relevant tools` | `正在选择最相关的工具` | [search/retrievers/mcp/retriever.py:146](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:146>) |
| 113 | `No relevant tools selected, skipping MCP research` | `未选出相关工具，跳过 MCP 研究` | [search/retrievers/mcp/retriever.py:150](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:150>) |
| 114 | `Stage 3` | `阶段 3` | [search/retrievers/mcp/retriever.py:154](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:154>) |
| 115 | `Conducting research with selected tools` | `正在使用所选工具开展研究` | [search/retrievers/mcp/retriever.py:154](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:154>) |
| 116 | `Error in MCP search: {str(e)}` | `MCP 搜索出错：{str(e)}` | [search/retrievers/mcp/retriever.py:192](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:192>) |
| 117 | `❌ MCP retriever cannot proceed without server configurations.` | `❌ 缺少服务器配置，MCP 检索器无法继续运行。` | [search/retrievers/mcp/retriever.py:218](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:218>) |
| 118 | `❌ Error in MCP search: {str(e)}` | `❌ MCP 搜索出错：{str(e)}` | [search/retrievers/mcp/retriever.py:296](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:296>) |
| 119 | `📋 Loaded {len(all_tools)} total tools from MCP servers` | `📋 已从 MCP 服务器加载共 {len(all_tools)} 个工具` | [search/retrievers/mcp/retriever.py:314](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:314>) |
| 120 | `No tools available from MCP servers` | `MCP 服务器没有可用工具` | [search/retrievers/mcp/retriever.py:318](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:318>) |
| 121 | `Error getting MCP tools: {str(e)}` | `获取 MCP 工具时出错：{str(e)}` | [search/retrievers/mcp/retriever.py:323](<F:/Desk/面试简历/gpt-researcher-main/search/retrievers/mcp/retriever.py:323>) |

## C. 浏览器连接、聊天和任务错误

| 编号 | 英文原文 | 中文译文（拟） | 源码位置 |
| --- | --- | --- | --- |
| 122 | `Error: {exc}` | `错误：{exc}` | [backend/server/app.py:442](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:442>)<br>[backend/server/server_utils.py:147](<F:/Desk/面试简历/gpt-researcher-main/backend/server/server_utils.py:147>) |
| 123 | `Research task cancelled after client disconnect` | `客户端断开连接后，研究任务已取消` | [backend/server/server_utils.py:141](<F:/Desk/面试简历/gpt-researcher-main/backend/server/server_utils.py:141>) |
| 124 | `Invalid human feedback message` | `人工反馈消息无效` | [backend/server/server_utils.py:161](<F:/Desk/面试简历/gpt-researcher-main/backend/server/server_utils.py:161>) |
| 125 | `Human feedback content must be a string` | `人工反馈内容必须是字符串` | [backend/server/server_utils.py:165](<F:/Desk/面试简历/gpt-researcher-main/backend/server/server_utils.py:165>) |
| 126 | `Human feedback queue is not initialized` | `人工反馈队列尚未初始化` | [backend/server/server_utils.py:181](<F:/Desk/面试简历/gpt-researcher-main/backend/server/server_utils.py:181>) |
| 127 | `No message provided.` | `未提供消息。` | [backend/server/server_utils.py:205](<F:/Desk/面试简历/gpt-researcher-main/backend/server/server_utils.py:205>) |
| 128 | `Chat functionality is not available. Please check the server configuration.` | `聊天功能不可用。请检查服务器配置。` | [backend/server/server_utils.py:214](<F:/Desk/面试简历/gpt-researcher-main/backend/server/server_utils.py:214>) |
| 129 | `Error: Invalid message format - {str(e)}` | `错误：消息格式无效——{str(e)}` | [backend/server/server_utils.py:245](<F:/Desk/面试简历/gpt-researcher-main/backend/server/server_utils.py:245>) |
| 130 | `Error processing your message: {str(e)}` | `处理你的消息时出错：{str(e)}` | [backend/server/server_utils.py:252](<F:/Desk/面试简历/gpt-researcher-main/backend/server/server_utils.py:252>) |
| 131 | `Error: {e}` | `错误：{e}` | [backend/server/server_utils.py:291](<F:/Desk/面试简历/gpt-researcher-main/backend/server/server_utils.py:291>) |
| 132 | `Task already running. Please wait.` | `任务正在运行，请稍候。` | [backend/server/server_utils.py:317](<F:/Desk/面试简历/gpt-researcher-main/backend/server/server_utils.py:317>) |
| 133 | `Unknown command received by server` | `服务器收到未知命令` | [backend/server/server_utils.py:336](<F:/Desk/面试简历/gpt-researcher-main/backend/server/server_utils.py:336>) |
| 134 | `🔧 MCP enabled with strategy '{mcp_strategy}' and {len(mcp_configs)} server(s)` | `🔧 已启用 MCP，策略为“{mcp_strategy}”，共 {len(mcp_configs)} 个服务器` | [backend/server/websocket_manager.py:155](<F:/Desk/面试简历/gpt-researcher-main/backend/server/websocket_manager.py:155>) |

## D. 相关接口错误（可能由页面展示）

| 编号 | 英文原文 | 中文译文（拟） | 源码位置 |
| --- | --- | --- | --- |
| 135 | `PostgreSQL storage is unavailable` | `PostgreSQL 存储服务不可用` | [backend/server/app.py:127](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:127>) |
| 136 | `Report not found` | `未找到报告` | [backend/server/app.py:163](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:163>)<br>[backend/server/app.py:230](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:230>)<br>[backend/server/app.py:247](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:247>)<br>[backend/server/app.py:265](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:265>)<br>[backend/server/app.py:273](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:273>)<br>[backend/server/app.py:282](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:282>) |
| 137 | `Artifact not found` | `未找到报告附件` | [backend/server/app.py:190](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:190>)<br>[backend/server/app.py:192](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:192>)<br>[backend/server/app.py:200](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:200>) |
| 138 | `Research run not found` | `未找到研究任务` | [backend/server/app.py:290](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:290>)<br>[backend/server/app.py:300](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:300>)<br>[backend/server/app.py:309](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:309>)<br>[backend/server/app.py:315](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:315>)<br>[backend/server/app.py:503](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:503>) |
| 139 | `Evidence not found` | `未找到证据` | [backend/server/app.py:512](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:512>) |
| 140 | `Uploaded file is too large` | `上传的文件过大` | [backend/server/app.py:561](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:561>) |
| 141 | `Document not found` | `未找到文档` | [backend/server/app.py:586](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:586>)<br>[backend/server/app.py:594](<F:/Desk/面试简历/gpt-researcher-main/backend/server/app.py:594>) |

## E. 结果标题和引用包装（可能进入正文或上下文）

| 编号 | 英文原文 | 中文译文（拟） | 源码位置 |
| --- | --- | --- | --- |
| 142 | `LLM Analysis: {query}` | `大语言模型分析：{query}` | [search/mcp/research.py:140](<F:/Desk/面试简历/gpt-researcher-main/search/mcp/research.py:140>) |
| 143 | `Result from {tool_name} #{i+1}` | `来自 {tool_name} 的结果 #{i+1}` | [search/mcp/research.py:183](<F:/Desk/面试简历/gpt-researcher-main/search/mcp/research.py:183>) |
| 144 | `Result from {tool_name}` | `来自 {tool_name} 的结果` | [search/mcp/research.py:190](<F:/Desk/面试简历/gpt-researcher-main/search/mcp/research.py:190>)<br>[search/mcp/research.py:216](<F:/Desk/面试简历/gpt-researcher-main/search/mcp/research.py:216>)<br>[search/mcp/research.py:238](<F:/Desk/面试简历/gpt-researcher-main/search/mcp/research.py:238>)<br>[search/mcp/research.py:247](<F:/Desk/面试简历/gpt-researcher-main/search/mcp/research.py:247>)<br>[search/mcp/research.py:255](<F:/Desk/面试简历/gpt-researcher-main/search/mcp/research.py:255>)<br>[search/mcp/research.py:265](<F:/Desk/面试简历/gpt-researcher-main/search/mcp/research.py:265>) |
| 145 | `\n\n*Source: {title} ({url})*` | `\n\n*来源：{title} ({url})*` | [search/skills/researcher.py:832](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:832>) |
| 146 | `\n\n*Source: {title}*` | `\n\n*来源：{title}*` | [search/skills/researcher.py:834](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:834>) |

## F. 文档研究错误、图片替代文字及聊天检索回退

| 编号 | 英文原文 | 中文译文（拟） | 源码位置 |
| --- | --- | --- | --- |
| 147 | `Document not found` | `未找到文档` | [backend/server/document_service.py:54](<F:/Desk/面试简历/gpt-researcher-main/backend/server/document_service.py:54>) |
| 148 | `The document parser returned no text` | `文档解析器未返回文本` | [backend/server/document_service.py:96](<F:/Desk/面试简历/gpt-researcher-main/backend/server/document_service.py:96>) |
| 149 | `Embedding dimension {actual_dimensions} does not match EMBEDDING_DIMENSION={configured_dimensions}` | `向量维度 {actual_dimensions} 与 EMBEDDING_DIMENSION={configured_dimensions} 不一致` | [backend/server/document_service.py:104](<F:/Desk/面试简历/gpt-researcher-main/backend/server/document_service.py:104>) |
| 150 | `EMBEDDING_KWARGS must be valid JSON` | `EMBEDDING_KWARGS 必须是有效的 JSON` | [backend/server/document_service.py:29](<F:/Desk/面试简历/gpt-researcher-main/backend/server/document_service.py:29>) |
| 151 | `Query embedding dimension does not match active profile` | `查询向量的维度与当前启用的配置不一致` | [backend/server/document_service.py:166](<F:/Desk/面试简历/gpt-researcher-main/backend/server/document_service.py:166>) |
| 152 | `Illustration: {clean_prompt}` | `插图：{clean_prompt}` | [search/llm_provider/image/image_generator.py:435](<F:/Desk/面试简历/gpt-researcher-main/search/llm_provider/image/image_generator.py:435>) |
| 153 | `Illustration: {prompt[:120]}` | `插图：{prompt[:120]}` | [search/llm_provider/image/modelslab_image_generator.py:205](<F:/Desk/面试简历/gpt-researcher-main/search/llm_provider/image/modelslab_image_generator.py:205>) |
| 154 | `MCP Result {i+1}` | `MCP 结果 {i+1}` | [search/skills/researcher.py:827](<F:/Desk/面试简历/gpt-researcher-main/search/skills/researcher.py:827>) |
| 155 | `Web search is disabled - TAVILY_API_KEY not configured` | `网络搜索已禁用：未配置 TAVILY_API_KEY` | [backend/chat/chat.py:123](<F:/Desk/面试简历/gpt-researcher-main/backend/chat/chat.py:123>)<br>[backend/chat/chat.py:126](<F:/Desk/面试简历/gpt-researcher-main/backend/chat/chat.py:126>) |

## G. 前端直接展示的英文字段：建议增加中文显示名称

目前日志折叠面板的更新标题直接拼接 `Object.keys(...).join(...)`，字段小标题直接显示 `{data.field}`。即使后端句子全部中文化，这些位置仍可能出现英文。下面依据研究状态和草稿状态定义列出已知字段。

| 原字段（程序中保留） | 界面显示名称（拟） |
| --- | --- |
| `task` | 任务 |
| `initial_research` | 初步研究 |
| `sections` | 章节 |
| `research_data` | 研究资料 |
| `human_feedback` | 人工反馈 |
| `plan_revision_count` | 计划修订次数 |
| `title` | 标题 |
| `headers` | 报告标题结构 |
| `date` | 日期 |
| `table_of_contents` | 目录 |
| `introduction` | 引言 |
| `conclusion` | 结论 |
| `sources` | 来源 |
| `report` | 报告 |
| `fact_check_notes` | 事实核查意见 |
| `diagrams` | 图表 |
| `fact_check_revision_count` | 事实核查修订次数 |
| `topic` | 主题 |
| `draft` | 草稿 |
| `review` | 审阅意见 |
| `revision_notes` | 修订说明 |
| `draft_revision_count` | 草稿修订次数 |

依据：[Accordion.tsx](F:/Desk/面试简历/gpt-researcher-main/frontend/nextjs/components/Task/Accordion.tsx)、[ResearchState](F:/Desk/面试简历/gpt-researcher-main/multi_agents/memory/research.py)、[DraftState](F:/Desk/面试简历/gpt-researcher-main/multi_agents/memory/draft.py)。只更改展示名称，不修改这些状态定义中的键。

### 直接输出 JSON 的另一处

[multi_agents/agents/writer.py:114](F:/Desk/面试简历/gpt-researcher-main/multi_agents/agents/writer.py:114) 在 verbose 模式下，会将 `research_layout_content` 整体 `json.dumps` 后发送给页面，可能显示 `table_of_contents`、`introduction`、`conclusion`、`sources` 等英文键，以及中文的 Unicode 转义。建议确认后由展示层使用上述中文标签呈现内容；保留原 JSON 键和原始事件数据。单纯翻译英文句子不能解决原始 JSON 的展示问题。

### MCP 策略动态值

C 组的 `{mcp_strategy}` 可能显示 `fast`、`deep` 等配置值。如果希望这一处也中文化，可仅在显示时映射：`fast` → `快速`、`deep` → `深度`、`disabled` → `禁用`；原配置和判断值保持英文。未识别的策略保留原值。

## H. 需要保留或单独处理的英文

| 类型 | 示例 | 处理建议 |
| --- | --- | --- |
| 事件和协议标识 | `logs`、`error`、`scraping_content`、`report_complete`、`pong` | 保持原样；用于通信和判断，不是待翻译文案。 |
| 外部工具和来源 | 工具名、网页英文标题、模型名、URL | 保留真实内容；本次不新增翻译调用。 |
| 动态异常 | `{str(e)}`、Axios 的 `error.message`、数据库或模型服务错误 | 固定前缀可中文化；不改写未知错误，避免丢失诊断信息。 |
| 历史研究记录 | 已保存的英文 logs | 修改源码只影响新生成消息；不自动回写历史记录，不修改数据库。 |
| 后端控制台专用日志 | `DEEP RESEARCH: depth=...`、`logger.debug(...)` | 不与 Web 流程状态混算；运维日志中文化属于另外的范围。 |
| 前端开发者控制台 | `Error processing log:`、`Error parsing WebSocket message:` | 不在普通页面展示，本次不替换。 |
| 来源识别规则 | Accordion 中匹配 `📃 Source:` 的正则 | 属于解析规则，不能当普通文案直接翻译。若以后更改对应来源标签，应保留旧日志解析兼容。 |
| 已中文化的业务提示词 | 模型输入模板 | 本次不重复改动。E/F 组中部分结果包装也可能成为模型上下文，已单列说明。 |

## 确认后的实施边界

1. A–F 组：按对照替换静态文案；同一文本的全部列明位置一起修改，保留动态参数和逻辑。
2. G 组：若一并确认，增加中文展示映射和 JSON 展示适配，保持原数据结构。这部分不属于单纯字符串替换。
3. 保留 H 组中的协议、外部内容、开发者专用日志；不清空或改写历史记录。
4. 核对差异，验证变量格式化与页面显示。Docker 后端需要重新构建并运行新镜像，才会在新研究中使用新文案；当前整理阶段不执行构建。

**当前状态：文案及显示适配已实施。代码规范、类型、翻译覆盖和显示函数测试通过；生产构建因字体下载受阻未完成。**
