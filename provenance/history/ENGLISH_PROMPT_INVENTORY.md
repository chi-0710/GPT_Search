# 知行研究助手（ResearchRunner）英文提示词清单

整理日期：2026-09-18。仅新增本清单，没有修改任何运行代码或提示词。

## 统计口径与范围

本清单按“源码位置/模板组”归类，一组可能含 system、user、多种风格或角色示例，不等于模型调用次数。
范围为仓库内维护的模型指令、提示模板及模型可见辅助说明。英文注释、普通日志、UI 文案、搜索到的外部文章、历史生成报告、测试问题数据不混算为运行时提示词。
正文附录保留原始源码块，便于查看英文全文、动态占位符及拼接条件；其中的注释与调用代码不是提示词正文。
动态搜索内容、用户自定义提示、MCP 服务返回的工具说明和依赖库内置提示无法用仓库静态扫描穷尽；这部分单独说明。

## 主要发现

- 普通/资源/大纲/深度报告写作、子章节写作、引言结论、快速问答、报告聊天及常规多智能体编审提示已主要中文化。
- 搜索词生成、来源筛选、自动角色选择仍为英文；角色选择示例可能让生成的角色名称和角色提示继续使用英文。
- 深度研究的中间查询、追问、learnings 也仍由英文提示引导。
- MCP 和图像生成存在完整英文模板。
- 已有 LANGUAGE=Chinese (Simplified) 不会自动翻译这些独立模板；仅被相应模板读取时才约束输出。
- 工作记录中的 Starting / Scraping / Writing 等通常是程序写死的日志，不是模型提示词；翻译提示词不会自动翻译这些日志。
- JSON 键、工具名、协议标记和占位符不应作为普通英文文案盲目翻译。

## 索引

| 编号 | 分类 | 文件与位置 | 模板/函数 | 用途 | 使用范围 |
| --- | --- | --- | --- | --- | --- |
| 1 | 核心研究与检索 | [search/prompts.py:213](search/prompts.py) | `generate_search_queries_prompt` | 生成搜索词；含实时上下文补充指令 | 主研究流程的对应环节使用 |
| 2 | 核心研究与检索 | [search/prompts.py:492](search/prompts.py) | `auto_agent_instructions` | 自动选择智能体角色；包含金融、商业、旅行三组英文角色示例 | 主研究流程的对应环节使用 |
| 3 | 核心研究与检索 | [search/prompts.py:318](search/prompts.py) | `curate_sources` | 评估和筛选来源，要求保留数据并返回 JSON | 主研究流程的对应环节使用 |
| 4 | 核心研究与检索 | [search/prompts.py:520](search/prompts.py) | `generate_summary_prompt` | 按查询摘要来源文本 | generate_summary_prompt 仅确认定义，未找到仓库内直接调用 |
| 5 | 核心研究与检索 | [search/actions/agent_creator.py:65](search/actions/agent_creator.py) | `handle_json_error` | 角色生成失败时的默认英文角色提示 | 按对应功能启用时使用 |
| 6 | 核心研究与检索 | [search/actions/agent_creator.py:18](search/actions/agent_creator.py) | `choose_agent` | 发送给角色选择模型的 task: 输入包装 | 按对应功能启用时使用 |
| 7 | 核心研究与检索 | [search/actions/report_generation.py:115](search/actions/report_generation.py) | `summarize_url` | Summarize the following content from ... 网页摘要指令 | 导出辅助函数；不代表每次研究都会调用 |
| 8 | 递归深度研究 | [search/skills/deep_research.py:259](search/skills/deep_research.py) | `generate_search_queries` | 深度研究搜索词：system + user，JSON 数组 | 按对应功能启用时使用 |
| 9 | 递归深度研究 | [search/skills/deep_research.py:291](search/skills/deep_research.py) | `generate_research_plan` | 深度研究追问规划：system + user，questions JSON | 按对应功能启用时使用 |
| 10 | 递归深度研究 | [search/skills/deep_research.py:344](search/skills/deep_research.py) | `process_research_results` | 提取 learnings 和 followUpQuestions：system + user | 按对应功能启用时使用 |
| 11 | MCP | [search/prompts.py:40](search/prompts.py) | `generate_mcp_tool_selection_prompt` | 选择工具及相关性评分、理由 | 按对应功能启用时使用 |
| 12 | MCP | [search/prompts.py:86](search/prompts.py) | `generate_mcp_research_prompt` | 调用工具开展研究、失败替代与结果综合 | 按对应功能启用时使用 |
| 13 | 配图 | [search/prompts.py:122](search/prompts.py) | `generate_image_analysis_prompt` | 判断报告哪些章节适合配图 | 模板定义；未找到仓库内直接调用，实际流程还有独立模板 |
| 14 | 配图 | [search/prompts.py:178](search/prompts.py) | `generate_image_prompt_enhancement` | 配图描述增强、颜色和风格要求 | 模板定义；未找到仓库内直接调用，实际流程还有独立模板 |
| 15 | 配图 | [search/skills/image_generator.py:187](search/skills/image_generator.py) | `_plan_image_concepts` | 报告前规划配图：英文 system + 规划模板 | 按对应功能启用时使用 |
| 16 | 配图 | [search/skills/image_generator.py:276](search/skills/image_generator.py) | `analyze_report_for_images` | 报告后配图分析的英文 system 消息 | 按对应功能启用时使用 |
| 17 | 配图 | [search/skills/image_generator.py:376](search/skills/image_generator.py) | `_build_analysis_prompt` | 报告后配图分析详细模板 | 按对应功能启用时使用 |
| 18 | 配图 | [search/llm_provider/image/image_generator.py:159](search/llm_provider/image/image_generator.py) | `_build_enhanced_prompt` | 暗色、亮色、通用风格三个分支和最终图像生成模板 | 按对应功能启用时使用 |
| 19 | 配图 | [search/actions/report_generation.py:209](search/actions/report_generation.py) | `generate_report` | 中文写作提示后追加的 AVAILABLE IMAGES 英文嵌图指令；自定义提示还使用 Context: 包装 | 按对应功能启用时使用 |
| 20 | AG2 多智能体 | [multi_agents/ag2/agents/orchestrator.py:57](multi_agents/ag2/agents/orchestrator.py) | `_initialize_ag2_team` | 8 个角色的 system_message：协调、规划、研究、审阅、修订、写作、发布、人类反馈 | AG2 独立实现；不要与默认 LangGraph 流程混同 |
| 21 | Deep Agents 实验实现 | [deep_agents/agent.py:11](deep_agents/agent.py) | `CHIEF_EDITOR_PROMPT` | 主编智能体：规划、分派、审核、合稿 | 独立实验实现；不等同于网页的普通研究流程 |
| 22 | Deep Agents 实验实现 | [deep_agents/agent.py:47](deep_agents/agent.py) | `RESEARCHER_PROMPT` | 研究子智能体：调用研究工具并写章节 | 独立实验实现；不等同于网页的普通研究流程 |
| 23 | Deep Agents 实验实现 | [deep_agents/agent.py:63](deep_agents/agent.py) | `build_agent` | 附加 guidelines 指令和研究子智能体英文能力描述 | 独立实验实现；不等同于网页的普通研究流程 |
| 24 | Deep Agents 实验实现 | [deep_agents/main.py:33](deep_agents/main.py) | `build_request` | 用户任务包装：研究主题及最多章节数 | 按对应功能启用时使用 |
| 25 | 独立示例 | [backend/report_type/deep_research/example.py:55](backend/report_type/deep_research/example.py) | `generate_feedback` | 澄清研究方向：system + user | 示例脚本，不是现行 DeepResearchSkill 的实现 |
| 26 | 独立示例 | [backend/report_type/deep_research/example.py:87](backend/report_type/deep_research/example.py) | `generate_serp_queries` | 生成搜索词：system + user | 示例脚本，不是现行 DeepResearchSkill 的实现 |
| 27 | 独立示例 | [backend/report_type/deep_research/example.py:119](backend/report_type/deep_research/example.py) | `process_serp_result` | 提取研究结论和后续问题：system + user | 示例脚本，不是现行 DeepResearchSkill 的实现 |
| 28 | 评测脚本 | [deep_agents/benchmark.py:34](deep_agents/benchmark.py) | `SYSTEM_PROMPT` | 基准测试研究员系统提示 | 仅运行对应评测时使用 |
| 29 | 评测脚本 | [deep_agents/benchmark.py:43](deep_agents/benchmark.py) | `BASELINE_TOOL_GUIDE` | 基线 Tavily 工具说明 | 仅运行对应评测时使用 |
| 30 | 评测脚本 | [deep_agents/benchmark.py:51](deep_agents/benchmark.py) | `GPTR_TOOL_GUIDE` | ResearchRunner 工具说明 | 仅运行对应评测时使用 |
| 31 | 评测脚本 | [deep_agents/hybrid_benchmark.py:71](deep_agents/hybrid_benchmark.py) | `FILES_GUIDE` | 私有文件检索和使用要求 | 仅运行对应评测时使用 |
| 32 | 评测脚本 | [deep_agents/hybrid_benchmark.py:121](deep_agents/hybrid_benchmark.py) | `TASK` | 尽职调查任务请求及检查数据 | 仅运行对应评测时使用 |
| 33 | 评测脚本 | [deep_agents/report_benchmark.py:65](deep_agents/report_benchmark.py) | `REPORT_REQUEST` | 评测报告生成请求 | 仅运行对应评测时使用 |
| 34 | 评测脚本 | [deep_agents/report_benchmark.py:78](deep_agents/report_benchmark.py) | `JUDGE_TEMPLATE` | 两个报告的对比评分 | 仅运行对应评测时使用 |
| 35 | 评测脚本 | [deep_agents/recency_benchmark.py:191](deep_agents/recency_benchmark.py) | `GRADER_TEMPLATE` | 按事实检查点评估报告 | 仅运行对应评测时使用 |
| 36 | 评测脚本 | [deep_agents/drb_generate.py:35](deep_agents/drb_generate.py) | `REPORT_SYSTEM_PROMPT` | DeepResearch Bench 专用研究系统指令 | 仅运行对应评测时使用 |
| 37 | 评测脚本 | [evals/simple_evals/simpleqa_eval.py:14](evals/simple_evals/simpleqa_eval.py) | `GRADER_TEMPLATE` | SimpleQA 正确性评判模板 | 仅运行对应评测时使用 |
| 38 | 模型可见的辅助文本 | [search/prompts.py:557](search/prompts.py) | `pretty_print_docs` | Source/Title/Content、Context from ... 或 Granite 文档包装 | 包含模型专用格式；不是独立角色提示 |
| 39 | 模型可见的辅助文本 | [search/prompts.py:773](search/prompts.py) | `pretty_print_docs` | Source/Title/Content、Context from ... 或 Granite 文档包装 | 包含模型专用格式；不是独立角色提示 |
| 40 | 模型可见的辅助文本 | [search/prompts.py:787](search/prompts.py) | `pretty_print_docs` | Source/Title/Content、Context from ... 或 Granite 文档包装 | 包含模型专用格式；不是独立角色提示 |
| 41 | 模型可见的辅助文本 | [search/prompts.py:825](search/prompts.py) | `pretty_print_docs` | Source/Title/Content、Context from ... 或 Granite 文档包装 | 包含模型专用格式；不是独立角色提示 |
| 42 | 模型可见的辅助文本 | [search/prompts.py:566](search/prompts.py) | `join_local_web_documents` | Source/Title/Content、Context from ... 或 Granite 文档包装 | 包含模型专用格式；不是独立角色提示 |
| 43 | 模型可见的辅助文本 | [search/prompts.py:776](search/prompts.py) | `join_local_web_documents` | Source/Title/Content、Context from ... 或 Granite 文档包装 | 包含模型专用格式；不是独立角色提示 |
| 44 | 模型可见的辅助文本 | [search/prompts.py:800](search/prompts.py) | `join_local_web_documents` | Source/Title/Content、Context from ... 或 Granite 文档包装 | 包含模型专用格式；不是独立角色提示 |
| 45 | 模型可见的辅助文本 | [search/prompts.py:836](search/prompts.py) | `join_local_web_documents` | Source/Title/Content、Context from ... 或 Granite 文档包装 | 包含模型专用格式；不是独立角色提示 |
| 46 | 模型可见的辅助文本 | [search/prompts.py:818](search/prompts.py) | `_get_content` | Source/Title/Content、Context from ... 或 Granite 文档包装 | 包含模型专用格式；不是独立角色提示 |
| 47 | 模型可见的辅助文本 | [backend/chat/chat.py:56](backend/chat/chat.py) | `__init__` | quick_search 工具及参数的英文 description；聊天主 system 已是中文 | 按对应功能启用时使用 |
| 48 | 模型可见的辅助文本 | [deep_agents/tools.py:9](deep_agents/tools.py) | `build_research_tools` | quick_search/deep_research 工具 docstring 会作为模型可见说明 | 按对应功能启用时使用 |
| 49 | 模型可见的辅助文本 | [search/utils/tools.py:230](search/utils/tools.py) | `create_search_tool` | 搜索工具的英文 docstring 和失败提示 | 按对应功能启用时使用 |

## 配置及动态补充

- `deep_agents/task.json` 的 guidelines 有 3 条英文要求：完整回答原问题、各节提供超链接来源、APA 格式；follow_guidelines 当前为 false，是否注入由该开关决定。
- `multi_agents/task.json` 与 `multi_agents/ag2/task.json` 的 guidelines 已是中文；其中英文 query 是示例研究问题。
- `search/utils/validators.py` 的 `Task name` 等字段说明会通过结构化输出 schema 间接进入提示。
- `search/utils/llm.py` 的 `parser.get_format_instructions()` 注入依赖库生成的输出格式提示，原文不是本仓库常量。
- `AGENT_ROLE`、custom_prompt、外部 MCP 工具 descriptions、用户任务、动态生成的图片描述和研究角色，语言取决于运行输入。
- Granite 提示族中的角色分隔符与 document_id 等协议格式应保持兼容。
- 测试目录中 `tests/test-openai-llm.py` 的 `sup?` 属于测试输入；`tests/test_deep_research_parsing.py` 断言英文 JSON 指令，后续翻译时需同步调整断言。其他测试任务/假数据不作为生产模板。
- `deep_agents/*benchmark.py` 的 TASKS、TOPICS，以及 evals 输入文件属于评测题目/标准答案数据；评测控制提示已列入上表。
- `.claude/`、`skills/`、`AGENTS.md` 是面向开发助手的说明，不是研究运行时发给业务模型的提示。

## 原始源码附录

### 1. 生成搜索词；含实时上下文补充指令

位置：`search/prompts.py:213` · `generate_search_queries_prompt`

使用范围：主研究流程的对应环节使用。

````python
    def generate_search_queries_prompt(
        question: str,
        parent_query: str,
        report_type: str,
        max_iterations: int = 3,
        context: List[Dict[str, Any]] = [],
    ):
        """Generates the search queries prompt for the given question.
        Args:
            question (str): The question to generate the search queries prompt for
            parent_query (str): The main question (only relevant for detailed reports)
            report_type (str): The report type
            max_iterations (int): The maximum number of search queries to generate
            context (str): Context for better understanding of the task with realtime web information

        Returns: str: The search queries prompt for the given question
        """

        if (
            report_type == ReportType.DetailedReport.value
            or report_type == ReportType.SubtopicReport.value
        ):
            task = f"{parent_query} - {question}"
        else:
            task = question

        context_prompt = f"""
You are a seasoned research assistant tasked with generating search queries to find relevant information for the following task: "{task}".
Context: {context}

Use this context to inform and refine your search queries. The context provides real-time web information that can help you generate more specific and relevant queries. Consider any current events, recent developments, or specific details mentioned in the context that could enhance the search queries.
""" if context else ""

        dynamic_example = ", ".join([f'"query {i+1}"' for i in range(max_iterations)])

        return f"""Write {max_iterations} search queries to research the following task: "{task}"

Each query must be a plain natural language phrase. Do not use search operator syntax
such as site:, filetype:, inurl:, intitle:, OR, AND, or NOT — these operators are
not universally supported and will return empty results on many search backends.

Assume the current date is {datetime.now(timezone.utc).strftime('%B %d, %Y')} if required.

{context_prompt}
You must respond with a list of strings in the following format: [{dynamic_example}].
The response should contain ONLY the list.
"""
````

### 2. 自动选择智能体角色；包含金融、商业、旅行三组英文角色示例

位置：`search/prompts.py:492` · `auto_agent_instructions`

使用范围：主研究流程的对应环节使用。

````python
    def auto_agent_instructions():
        return """
This task involves researching a given topic, regardless of its complexity or the availability of a definitive answer. The research is conducted by a specific server, defined by its type and role, with each server requiring distinct instructions.
Agent
The server is determined by the field of the topic and the specific name of the server that could be utilized to research the topic provided. Agents are categorized by their area of expertise, and each server type is associated with a corresponding emoji.

examples:
task: "should I invest in apple stocks?"
response:
{
    "server": "💰 Finance Agent",
    "agent_role_prompt": "You are a seasoned finance analyst AI assistant. Your primary goal is to compose comprehensive, astute, impartial, and methodically arranged financial reports based on provided data and trends."
}
task: "could reselling sneakers become profitable?"
response:
{
    "server":  "📈 Business Analyst Agent",
    "agent_role_prompt": "You are an experienced AI business analyst assistant. Your main objective is to produce comprehensive, insightful, impartial, and systematically structured business reports based on provided business data, market trends, and strategic analysis."
}
task: "what are the most interesting sites in Tel Aviv?"
response:
{
    "server":  "🌍 Travel Agent",
    "agent_role_prompt": "You are a world-travelled AI tour guide assistant. Your main purpose is to draft engaging, insightful, unbiased, and well-structured travel reports on given locations, including history, attractions, and cultural insights."
}
"""
````

### 3. 评估和筛选来源，要求保留数据并返回 JSON

位置：`search/prompts.py:318` · `curate_sources`

使用范围：主研究流程的对应环节使用。

````python
    def curate_sources(query, sources, max_results=10):
        return f"""Your goal is to evaluate and curate the provided scraped content for the research task: "{query}"
    while prioritizing the inclusion of relevant and high-quality information, especially sources containing statistics, numbers, or concrete data.

The final curated list will be used as context for creating a research report, so prioritize:
- Retaining as much original information as possible, with extra emphasis on sources featuring quantitative data or unique insights
- Including a wide range of perspectives and insights
- Filtering out only clearly irrelevant or unusable content

EVALUATION GUIDELINES:
1. Assess each source based on:
   - Relevance: Include sources directly or partially connected to the research query. Err on the side of inclusion.
   - Credibility: Favor authoritative sources but retain others unless clearly untrustworthy.
   - Currency: Prefer recent information unless older data is essential or valuable.
   - Objectivity: Retain sources with bias if they provide a unique or complementary perspective.
   - Quantitative Value: Give higher priority to sources with statistics, numbers, or other concrete data.
2. Source Selection:
   - Include as many relevant sources as possible, up to {max_results}, focusing on broad coverage and diversity.
   - Prioritize sources with statistics, numerical data, or verifiable facts.
   - Overlapping content is acceptable if it adds depth, especially when data is involved.
   - Exclude sources only if they are entirely irrelevant, severely outdated, or unusable due to poor content quality.
3. Content Retention:
   - DO NOT rewrite, summarize, or condense any source content.
   - Retain all usable information, cleaning up only clear garbage or formatting issues.
   - Keep marginally relevant or incomplete sources if they contain valuable data or insights.

SOURCES LIST TO EVALUATE:
{sources}

You MUST return your response in the EXACT sources JSON list format as the original sources.
The response MUST not contain any markdown format or additional text (like ```json), just the JSON list!
"""
````

### 4. 按查询摘要来源文本

位置：`search/prompts.py:520` · `generate_summary_prompt`

使用范围：generate_summary_prompt 仅确认定义，未找到仓库内直接调用。

````python
    def generate_summary_prompt(query, data):
        """Generates the summary prompt for the given question and text.
        Args: question (str): The question to generate the summary prompt for
                text (str): The text to generate the summary prompt for
        Returns: str: The summary prompt for the given question and text
        """

        return (
            f'{data}\n Using the above text, summarize it based on the following task or query: "{query}".\n If the '
            f"query cannot be answered using the text, YOU MUST summarize the text in short.\n Include all factual "
            f"information such as numbers, stats, quotes, etc if available. "
        )
````

### 5. 角色生成失败时的默认英文角色提示

位置：`search/actions/agent_creator.py:65` · `handle_json_error`

使用范围：按对应功能启用时使用。

````python
async def handle_json_error(response: str | None):
    """Handle JSON parsing errors from LLM responses.

    Attempts to recover agent information from malformed JSON responses
    using json_repair and regex extraction as fallbacks.

    Args:
        response: The LLM response string that failed initial JSON parsing.

    Returns:
        A tuple of (agent_name, agent_role_prompt). Returns default agent
        if all parsing attempts fail.
    """
    try:
        agent_dict = json_repair.loads(response)
        if agent_dict.get("server") and agent_dict.get("agent_role_prompt"):
            return agent_dict["server"], agent_dict["agent_role_prompt"]
    except Exception as e:
        error_type = type(e).__name__
        error_msg = str(e)
        logger.warning(
            f"Failed to parse agent JSON with json_repair: {error_type}: {error_msg}",
            exc_info=True
        )
        if response:
            logger.debug(f"LLM response that failed to parse: {response[:500]}...")

    json_string = extract_json_with_regex(response)
    if json_string:
        try:
            json_data = json.loads(json_string)
            return json_data["server"], json_data["agent_role_prompt"]
        except json.JSONDecodeError as e:
            logger.warning(
                f"Failed to decode JSON from regex extraction: {str(e)}",
                exc_info=True
            )

    logger.info("No valid JSON found in LLM response. Falling back to default agent.")
    return "Default Agent", (
        "You are an AI critical thinker research assistant. Your sole purpose is to write well written, "
        "critically acclaimed, objective and structured reports on given text."
    )
````

### 6. 发送给角色选择模型的 task: 输入包装

位置：`search/actions/agent_creator.py:18` · `choose_agent`

使用范围：按对应功能启用时使用。

````python
async def choose_agent(
    query,
    cfg,
    parent_query=None,
    cost_callback: callable = None,
    headers=None,
    prompt_family: type[PromptFamily] | PromptFamily = PromptFamily,
    **kwargs
):
    """
    Chooses the agent automatically
    Args:
        parent_query: In some cases the research is conducted on a subtopic from the main query.
            The parent query allows the agent to know the main context for better reasoning.
        query: original query
        cfg: Config
        cost_callback: callback for calculating llm costs
        prompt_family: Family of prompts

    Returns:
        agent: Agent name
        agent_role_prompt: Agent role prompt
    """
    query = f"{parent_query} - {query}" if parent_query else f"{query}"
    response = None  # Initialize response to ensure it's defined

    try:
        response = await create_chat_completion(
            model=cfg.smart_llm_model,
            messages=[
                {"role": "system", "content": f"{prompt_family.auto_agent_instructions()}"},
                {"role": "user", "content": f"task: {query}"},
            ],
            temperature=0.15,
            llm_provider=cfg.smart_llm_provider,
            llm_kwargs=cfg.llm_kwargs,
            cost_callback=cost_callback,
            **kwargs
        )

        agent_dict = json.loads(response)
        return agent_dict["server"], agent_dict["agent_role_prompt"]

    except Exception as e:
        return await handle_json_error(response)
````

### 7. Summarize the following content from ... 网页摘要指令

位置：`search/actions/report_generation.py:115` · `summarize_url`

使用范围：导出辅助函数；不代表每次研究都会调用。

````python
async def summarize_url(
    url: str,
    content: str,
    role: str,
    config: Config,
    websocket=None,
    cost_callback: callable = None,
    **kwargs
) -> str:
    """
    Summarize the content of a URL.

    Args:
        url (str): The URL to summarize.
        content (str): The content of the URL.
        role (str): The role of the agent.
        config (Config): Configuration object.
        websocket: WebSocket connection for streaming output.
        cost_callback (callable, optional): Callback for calculating LLM costs.

    Returns:
        str: The summarized content.
    """
    try:
        summary = await create_chat_completion(
            model=config.smart_llm_model,
            messages=[
                {"role": "system", "content": f"{role}"},
                {"role": "user", "content": f"Summarize the following content from {url}:\n\n{content}"},
            ],
            temperature=0.25,
            llm_provider=config.smart_llm_provider,
            stream=True,
            websocket=websocket,
            max_tokens=config.smart_token_limit,
            llm_kwargs=config.llm_kwargs,
            cost_callback=cost_callback,
            **kwargs
        )
        return summary
    except Exception as e:
        logger.error(f"Error in summarizing URL: {e}")
    return ""
````

### 8. 深度研究搜索词：system + user，JSON 数组

位置：`search/skills/deep_research.py:259` · `generate_search_queries`

使用范围：按对应功能启用时使用。

````python
    async def generate_search_queries(self, query: str, num_queries: int = 3) -> List[Dict[str, str]]:
        """Generate SERP queries for research"""
        messages = [
            {
                "role": "system",
                "content": (
                    "You are an expert researcher generating search queries. "
                    "Return valid JSON only. Do not include markdown, code fences, bullets, numbering, or prose."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"Given the following prompt, generate {num_queries} unique search queries to research the topic thoroughly. "
                    "For each query, provide a research goal.\n\n"
                    "Return ONLY a JSON array of objects using this exact schema:\n"
                    '[{"query": "<search query>", "researchGoal": "<research goal>"}]\n\n'
                    f"Prompt: {query}"
                ),
            },
        ]

        response = await create_chat_completion(
            messages=messages,
            llm_provider=self.researcher.cfg.strategic_llm_provider,
            model=self.researcher.cfg.strategic_llm_model,
            reasoning_effort=self.researcher.cfg.reasoning_effort,
            temperature=0.4
        )

        return parse_search_queries_response(response, num_queries)
````

### 9. 深度研究追问规划：system + user，questions JSON

位置：`search/skills/deep_research.py:291` · `generate_research_plan`

使用范围：按对应功能启用时使用。

````python
    async def generate_research_plan(self, query: str, num_questions: int = 3) -> List[str]:
        """Generate follow-up questions to clarify research direction"""
        # Get initial search results from all retrievers to inform query generation
        all_search_results = []
        for retriever in self.researcher.retrievers:
            try:
                results = await get_search_results(
                    query,
                    retriever,
                    researcher=self.researcher
                )
                all_search_results.extend(results)
            except Exception as e:
                logger.warning(f"Error with retriever {retriever.__name__}: {e}")
        search_results = all_search_results
        logger.info(f"Initial web knowledge obtained: {len(search_results)} results")

        # Get current time for context
        current_time = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        messages = [
            {
                "role": "system",
                "content": (
                    "You are an expert researcher. Your task is to analyze the original query and search results, "
                    "then generate targeted questions that explore different aspects and time periods of the topic. "
                    "Return valid JSON only."
                ),
            },
            {"role": "user",
             "content": f"""Original query: {query}

Current time: {current_time}

Search results:
{search_results}

Based on these results, the original query, and the current time, generate {num_questions} unique questions. Each question should explore a different aspect or time period of the topic, considering recent developments up to {current_time}.

Return ONLY a JSON object using this exact schema:
{{"questions": ["<question 1>", "<question 2>"]}}"""}
        ]

        response = await create_chat_completion(
            messages=messages,
            llm_provider=self.researcher.cfg.strategic_llm_provider,
            model=self.researcher.cfg.strategic_llm_model,
            reasoning_effort=ReasoningEfforts.High.value,
            temperature=0.4
        )

        return parse_follow_up_questions_response(response, num_questions)
````

### 10. 提取 learnings 和 followUpQuestions：system + user

位置：`search/skills/deep_research.py:344` · `process_research_results`

使用范围：按对应功能启用时使用。

````python
    async def process_research_results(self, query: str, context: str, num_learnings: int = 3) -> Dict[str, List[str]]:
        """Process research results to extract learnings and follow-up questions"""
        messages = [
            {
                "role": "system",
                "content": (
                    "You are an expert researcher analyzing search results. "
                    "Return valid JSON only."
                ),
            },
            {"role": "user",
             "content": (
                 f"Given the following research results for the query '{query}', extract key learnings and suggest "
                 "follow-up questions. For each learning, include a citation to the source URL if available.\n\n"
                 "Return ONLY a JSON object using this exact schema:\n"
                 '{"learnings": [{"insight": "<insight>", "sourceUrl": "<url or empty string>"}], '
                 '"followUpQuestions": ["<question 1>", "<question 2>"]}\n\n'
                 f"Research results:\n{context}"
             )}
        ]

        response = await create_chat_completion(
            messages=messages,
            llm_provider=self.researcher.cfg.strategic_llm_provider,
            model=self.researcher.cfg.strategic_llm_model,
            temperature=0.4,
            reasoning_effort=ReasoningEfforts.High.value,
            # Needs headroom for reasoning tokens on reasoning models
            max_tokens=4000
        )

        return parse_research_results_response(response, num_learnings)
````

### 11. 选择工具及相关性评分、理由

位置：`search/prompts.py:40` · `generate_mcp_tool_selection_prompt`

使用范围：按对应功能启用时使用。

````python
    def generate_mcp_tool_selection_prompt(query: str, tools_info: List[Dict], max_tools: int = 3) -> str:
        """
        Generate prompt for LLM-based MCP tool selection.
        
        Args:
            query: The research query
            tools_info: List of available tools with their metadata
            max_tools: Maximum number of tools to select
            
        Returns:
            str: The tool selection prompt
        """
        import json
        
        return f"""You are a research assistant helping to select the most relevant tools for a research query.

RESEARCH QUERY: "{query}"

AVAILABLE TOOLS:
{json.dumps(tools_info, indent=2)}

TASK: Analyze the tools and select EXACTLY {max_tools} tools that are most relevant for researching the given query.

SELECTION CRITERIA:
- Choose tools that can provide information, data, or insights related to the query
- Prioritize tools that can search, retrieve, or access relevant content
- Consider tools that complement each other (e.g., different data sources)
- Exclude tools that are clearly unrelated to the research topic

Return a JSON object with this exact format:
{{
  "selected_tools": [
    {{
      "index": 0,
      "name": "tool_name",
      "relevance_score": 9,
      "reason": "Detailed explanation of why this tool is relevant"
    }}
  ],
  "selection_reasoning": "Overall explanation of the selection strategy"
}}

Select exactly {max_tools} tools, ranked by relevance to the research query.
"""
````

### 12. 调用工具开展研究、失败替代与结果综合

位置：`search/prompts.py:86` · `generate_mcp_research_prompt`

使用范围：按对应功能启用时使用。

````python
    def generate_mcp_research_prompt(query: str, selected_tools: List) -> str:
        """
        Generate prompt for MCP research execution with selected tools.
        
        Args:
            query: The research query
            selected_tools: List of selected MCP tools
            
        Returns:
            str: The research execution prompt
        """
        # Handle cases where selected_tools might be strings or objects with .name attribute
        tool_names = []
        for tool in selected_tools:
            if hasattr(tool, 'name'):
                tool_names.append(tool.name)
            else:
                tool_names.append(str(tool))
        
        return f"""You are a research assistant with access to specialized tools. Your task is to research the following query and provide comprehensive, accurate information.

RESEARCH QUERY: "{query}"

INSTRUCTIONS:
1. Use the available tools to gather relevant information about the query
2. Call multiple tools if needed to get comprehensive coverage
3. If a tool call fails or returns empty results, try alternative approaches
4. Synthesize information from multiple sources when possible
5. Focus on factual, relevant information that directly addresses the query

AVAILABLE TOOLS: {tool_names}

Please conduct thorough research and provide your findings. Use the tools strategically to gather the most relevant and comprehensive information."""
````

### 13. 判断报告哪些章节适合配图

位置：`search/prompts.py:122` · `generate_image_analysis_prompt`

使用范围：模板定义；未找到仓库内直接调用，实际流程还有独立模板。

````python
    def generate_image_analysis_prompt(
        query: str,
        sections: List[Dict[str, Any]],
        max_images: int = 3,
    ) -> str:
        """Generate prompt for analyzing which report sections need images.
        
        Args:
            query: The research query.
            sections: List of report sections with header and content.
            max_images: Maximum number of images to suggest.
            
        Returns:
            str: The analysis prompt.
        """
        sections_text = "\n\n".join([
            f"### Section {i+1}: {s['header']}\n{s['content'][:500]}..."
            for i, s in enumerate(sections)
        ])
        
        return f"""Analyze the following research report sections and identify which {max_images} sections would benefit MOST from a visual illustration or diagram.

RESEARCH TOPIC: {query}

REPORT SECTIONS:
{sections_text}

For each recommended section, provide:
1. The section number (1-indexed)
2. A specific, detailed image prompt that would create an informative illustration
3. A brief explanation of why this section benefits from visualization

IMPORTANT GUIDELINES:
- Choose sections where visual representation would genuinely aid understanding
- Focus on concepts, processes, comparisons, data flows, or statistics that are inherently visual
- Avoid sections that are purely textual analysis, introductions, or conclusions
- The image prompt should be specific enough to generate a relevant, professional illustration
- Images should be informative and educational, not decorative
- Consider diagrams, flowcharts, comparison charts, or conceptual illustrations

Respond in JSON format:
{{
    "suggestions": [
        {{
            "section_number": 1,
            "section_header": "Section Title",
            "image_prompt": "Detailed prompt for generating an informative illustration...",
            "image_type": "diagram|flowchart|comparison|concept|data_visualization",
            "reason": "Why this section benefits from visualization"
        }}
    ]
}}

Return ONLY the JSON, no additional text."""
````

### 14. 配图描述增强、颜色和风格要求

位置：`search/prompts.py:178` · `generate_image_prompt_enhancement`

使用范围：模板定义；未找到仓库内直接调用，实际流程还有独立模板。

````python
    def generate_image_prompt_enhancement(
        base_prompt: str,
        section_content: str,
        research_topic: str,
    ) -> str:
        """Enhance an image prompt with context for better generation.
        
        Args:
            base_prompt: The base image generation prompt.
            section_content: Content from the report section.
            research_topic: The main research topic.
            
        Returns:
            str: Enhanced image prompt.
        """
        return f"""Create a professional, informative illustration for a research report.

RESEARCH TOPIC: {research_topic}

IMAGE DESCRIPTION: {base_prompt}

CONTEXT FROM REPORT:
{section_content[:800]}

STYLE REQUIREMENTS:
- Professional and clean design suitable for academic/business reports
- Clear, easy-to-understand visual elements
- Modern, minimalist aesthetic
- Use a professional color palette (blues, teals, grays)
- Avoid excessive text in the image
- High contrast for readability
- If showing data or comparisons, use clear labels and legends
- Suitable for both digital viewing and printing"""
````

### 15. 报告前规划配图：英文 system + 规划模板

位置：`search/skills/image_generator.py:187` · `_plan_image_concepts`

使用范围：按对应功能启用时使用。

````python
    async def _plan_image_concepts(
        self,
        context: str,
        query: str,
    ) -> List[Dict[str, Any]]:
        """Use LLM to identify the best visualization opportunities from research context.
        
        Args:
            context: The research context to analyze.
            query: The main research query.
            
        Returns:
            List of image concept dictionaries with title, prompt, and section_hint.
        """
        # Truncate context if too long
        max_context_length = 6000
        truncated_context = context[:max_context_length] if len(context) > max_context_length else context
        
        planning_prompt = f"""Analyze this research context and identify 2-3 concepts that would significantly benefit from professional diagram/infographic illustrations.

RESEARCH QUERY: {query}

RESEARCH CONTEXT:
{truncated_context}

For each visualization opportunity, provide:
1. title: A short descriptive title (e.g., "System Architecture", "Comparison Chart")
2. prompt: A detailed image generation prompt describing exactly what to visualize, including layout and key elements (minimum 30 words)
3. section_hint: Which section of the report this image relates to

Focus on:
- Architecture/system diagrams
- Process flows and workflows
- Comparison charts
- Data visualizations
- Conceptual illustrations

IMPORTANT: Return ONLY a valid JSON array. No markdown, no explanation.

Example output:
[
  {{
    "title": "System Architecture Overview",
    "prompt": "A layered architecture diagram showing the frontend application on top, connecting to an API gateway in the middle, which routes to microservices at the bottom. Use clean boxes with connecting arrows, modern tech aesthetic.",
    "section_hint": "Architecture"
  }}
]

Return 2-3 visualization concepts as a JSON array:"""

        try:
            response = await create_chat_completion(
                model=self.cfg.fast_llm_model,
                messages=[
                    {"role": "system", "content": "You are a visualization expert. Return only valid JSON arrays."},
                    {"role": "user", "content": planning_prompt}
                ],
                temperature=0.4,
                llm_provider=self.cfg.fast_llm_provider,
                max_tokens=4000,  # headroom for reasoning tokens on reasoning models
                llm_kwargs=self.cfg.llm_kwargs,
                cost_callback=self.researcher.add_costs,
            )
            
            # Parse JSON response
            response = response.strip()
            # Remove markdown code blocks if present
            if response.startswith("```"):
                response = re.sub(r'^```(?:json)?\n?', '', response)
                response = re.sub(r'\n?```$', '', response)
            
            concepts = json.loads(response)
            
            # Validate and limit to max_images
            valid_concepts = []
            for concept in concepts[:self.max_images]:
                if isinstance(concept, dict) and 'title' in concept and 'prompt' in concept:
                    valid_concepts.append(concept)
            
            logger.info(f"Planned {len(valid_concepts)} image concepts")
            return valid_concepts
            
        except json.JSONDecodeError as e:
            logger.error(f"Failed to parse image planning response: {e}")
            return []
        except Exception as e:
            logger.error(f"Error during image planning: {e}")
            return []
````

### 16. 报告后配图分析的英文 system 消息

位置：`search/skills/image_generator.py:276` · `analyze_report_for_images`

使用范围：按对应功能启用时使用。

````python
    async def analyze_report_for_images(
        self,
        report: str,
        query: str,
    ) -> List[Dict[str, Any]]:
        """Analyze a report to identify sections that would benefit from images.
        
        Uses LLM to identify 2-3 key concepts or sections in the report
        that would be enhanced by visual illustrations.
        
        Args:
            report: The markdown report text.
            query: The original research query.
            
        Returns:
            List of dictionaries with section info and suggested image prompts.
        """
        if not self.is_enabled():
            return []
        
        # Extract sections from the report
        sections = self._extract_sections(report)
        
        if not sections:
            logger.warning("No sections found in report for image analysis")
            return []
        
        # Use LLM to identify best sections for images
        try:
            analysis_prompt = self._build_analysis_prompt(query, sections)
            
            response = await create_chat_completion(
                model=self.cfg.fast_llm_model,
                messages=[
                    {"role": "system", "content": "You are an expert at identifying content that would benefit from visual illustrations."},
                    {"role": "user", "content": analysis_prompt},
                ],
                temperature=0.3,
                llm_provider=self.cfg.fast_llm_provider,
                stream=False,
                websocket=None,
                max_tokens=4000,  # headroom for reasoning tokens on reasoning models
                llm_kwargs=self.cfg.llm_kwargs,
            )
            
            # Parse the response
            image_suggestions = self._parse_analysis_response(response, sections)
            return image_suggestions[:self.max_images]
            
        except Exception as e:
            logger.error(f"Error analyzing report for images: {e}")
            return []
````

### 17. 报告后配图分析详细模板

位置：`search/skills/image_generator.py:376` · `_build_analysis_prompt`

使用范围：按对应功能启用时使用。

````python
    def _build_analysis_prompt(
        self,
        query: str,
        sections: List[Dict[str, Any]],
    ) -> str:
        """Build prompt for LLM to analyze which sections need images.
        
        Args:
            query: The research query.
            sections: List of extracted sections.
            
        Returns:
            The analysis prompt string.
        """
        sections_text = "\n\n".join([
            f"### Section {i+1}: {s['header']}\n{s['content'][:500]}..."
            for i, s in enumerate(sections)
        ])
        
        return f"""Analyze the following research report sections and identify which {self.max_images} sections would benefit MOST from a visual illustration or diagram.

RESEARCH TOPIC: {query}

REPORT SECTIONS:
{sections_text}

For each recommended section, provide:
1. The section number (1-indexed)
2. A specific, detailed image prompt that would create an informative illustration
3. A brief explanation of why this section benefits from visualization

IMPORTANT:
- Choose sections where visual representation would genuinely aid understanding
- Focus on concepts, processes, comparisons, or data that are inherently visual
- Avoid sections that are purely textual analysis or conclusions
- The image prompt should be specific enough to generate a relevant, professional illustration
- Do NOT suggest images for introduction or conclusion sections

Respond in JSON format:
{{
    "suggestions": [
        {{
            "section_number": 1,
            "section_header": "Section Title",
            "image_prompt": "Detailed prompt for generating an informative illustration...",
            "reason": "Why this section benefits from visualization"
        }}
    ]
}}

Return ONLY the JSON, no additional text."""
````

### 18. 暗色、亮色、通用风格三个分支和最终图像生成模板

位置：`search/llm_provider/image/image_generator.py:159` · `_build_enhanced_prompt`

使用范围：按对应功能启用时使用。

````python
    def _build_enhanced_prompt(self, prompt: str, context: str = "", style: str = "dark") -> str:
        """Build an enhanced prompt with explicit styling instructions.
        
        Args:
            prompt: Base image prompt.
            context: Additional context from research.
            style: Image style - "dark" (matches app theme), "light", or "auto".
            
        Returns:
            Enhanced prompt string with styling instructions.
        """
        # Style-specific color palettes
        if style == "dark":
            # Dark mode matching the ResearchRunner app theme
            style_instructions = """
STYLE REQUIREMENTS - DARK MODE THEME:
- Dark background (#0d1117 or similar deep charcoal/navy)
- Primary accent color: Teal/Cyan (#14b8a6, #0d9488)
- Secondary colors: Slate grays (#374151, #4b5563), subtle purple accents
- Glowing, neon-like effects for highlights and important elements
- Modern, tech-forward, futuristic aesthetic
- Clean lines with subtle gradients
- High contrast elements that pop against dark background
- Sleek, minimalist design with visual depth
- Icons and diagrams with luminous teal outlines
- Professional infographic style suitable for tech/research context"""
        elif style == "light":
            style_instructions = """
STYLE REQUIREMENTS - LIGHT MODE:
- Clean white or light gray background
- Primary colors: Deep blue (#1e40af), teal (#0d9488)
- Professional, corporate aesthetic
- Subtle shadows for depth
- High readability with dark text elements
- Modern flat design with occasional gradients"""
        else:
            style_instructions = """
STYLE REQUIREMENTS - PROFESSIONAL:
- Sophisticated color palette (teals, blues, grays)
- Clean, modern design
- High contrast for readability
- Professional infographic style"""

        styled_prompt = f"""Create a professional, high-quality illustration for a research report.

SUBJECT: {prompt}

{style_instructions}

TECHNICAL REQUIREMENTS:
- No text, labels, or watermarks in the image
- Clear visual hierarchy
- Well-balanced composition
- Suitable for both digital viewing and printing
- Vector-style or clean photorealistic rendering
- Resolution and detail appropriate for report embedding

AVOID:
- Cartoonish or childish styles
- Cluttered or busy designs  
- Bright white backgrounds (for dark mode)
- Low quality or pixelated elements
- Generic stock photo aesthetics"""

        if context:
            styled_prompt += f"\n\nRESEARCH CONTEXT: {context[:300]}"
        
        return styled_prompt
````

### 19. 中文写作提示后追加的 AVAILABLE IMAGES 英文嵌图指令；自定义提示还使用 Context: 包装

位置：`search/actions/report_generation.py:209` · `generate_report`

使用范围：按对应功能启用时使用。

````python
async def generate_report(
    query: str,
    context,
    agent_role_prompt: str,
    report_type: str,
    tone: Tone,
    report_source: str,
    websocket,
    cfg,
    main_topic: str = "",
    existing_headers: list = [],
    relevant_written_contents: list = [],
    cost_callback: callable = None,
    custom_prompt: str = "", # This can be any prompt the user chooses with the context
    headers=None,
    prompt_family: type[PromptFamily] | PromptFamily = PromptFamily,
    available_images: list = None,
    **kwargs
):
    """
    generates the final report
    Args:
        query:
        context:
        agent_role_prompt:
        report_type:
        websocket:
        tone:
        cfg:
        main_topic:
        existing_headers:
        relevant_written_contents:
        cost_callback:
        prompt_family: Family of prompts
        available_images: Pre-generated images to embed in the report

    Returns:
        report:

    """
    available_images = available_images or []
    generate_prompt = get_prompt_by_report_type(report_type, prompt_family)
    report = ""

    if report_type == "subtopic_report":
        content = f"{generate_prompt(query, existing_headers, relevant_written_contents, main_topic, context, report_format=cfg.report_format, tone=tone, total_words=cfg.total_words, language=cfg.language)}"
    elif custom_prompt:
        content = f"{custom_prompt}\n\nContext: {context}"
    else:
        content = f"{generate_prompt(query, context, report_source, report_format=cfg.report_format, tone=tone, total_words=cfg.total_words, language=cfg.language)}"
    
    # Add available images instruction if images were pre-generated
    if available_images:
        images_info = "\n".join([
            f"- Image {i+1}: ![{img.get('title', img.get('alt_text', 'Illustration'))}]({img['url']}) - {img.get('section_hint', 'General')}"
            for i, img in enumerate(available_images)
        ])
        content += f"""

AVAILABLE IMAGES:
You have the following pre-generated images available. Embed them in relevant sections of your report using the exact markdown syntax provided:

{images_info}

Place each image on its own line after the relevant section header or paragraph. Use all available images where they add value to the content."""
    try:
        report = await create_chat_completion(
            model=cfg.smart_llm_model,
            messages=[
                {"role": "system", "content": f"{agent_role_prompt}"},
                {"role": "user", "content": content},
            ],
            temperature=0.35,
            llm_provider=cfg.smart_llm_provider,
            stream=True,
            websocket=websocket,
            max_tokens=cfg.smart_token_limit,
            llm_kwargs=cfg.llm_kwargs,
            cost_callback=cost_callback,
            **kwargs
        )
    except Exception:
        try:
            report = await create_chat_completion(
                model=cfg.smart_llm_model,
                messages=[
                    {"role": "user", "content": f"{agent_role_prompt}\n\n{content}"},
                ],
                temperature=0.35,
                llm_provider=cfg.smart_llm_provider,
                stream=True,
                websocket=websocket,
                max_tokens=cfg.smart_token_limit,
                llm_kwargs=cfg.llm_kwargs,
                cost_callback=cost_callback,
                **kwargs
            )
        except Exception as e:
            print(f"Error in generate_report: {e}")

    return report
````

### 20. 8 个角色的 system_message：协调、规划、研究、审阅、修订、写作、发布、人类反馈

位置：`multi_agents/ag2/agents/orchestrator.py:57` · `_initialize_ag2_team`

使用范围：AG2 独立实现；不要与默认 LangGraph 流程混同。

````python
    def _initialize_ag2_team(self):
        llm_config = self._llm_config()

        agents = {
            "chief_editor": ConversableAgent(
                name="ChiefEditor",
                system_message="You coordinate the multi-agent research workflow.",
                llm_config=llm_config,
            ),
            "editor": ConversableAgent(
                name="Editor",
                system_message="You plan the research outline from initial findings.",
                llm_config=llm_config,
            ),
            "researcher": ConversableAgent(
                name="Researcher",
                system_message="You perform initial and deep research tasks.",
                llm_config=llm_config,
            ),
            "reviewer": ConversableAgent(
                name="Reviewer",
                system_message="You review drafts against the given guidelines.",
                llm_config=llm_config,
            ),
            "reviser": ConversableAgent(
                name="Reviser",
                system_message="You revise drafts based on reviewer feedback.",
                llm_config=llm_config,
            ),
            "writer": ConversableAgent(
                name="Writer",
                system_message="You compile the final report from research drafts.",
                llm_config=llm_config,
            ),
            "publisher": ConversableAgent(
                name="Publisher",
                system_message="You publish the final report in the requested formats.",
                llm_config=llm_config,
            ),
            "human": UserProxyAgent(
                name="Human",
                system_message="You provide optional feedback on the research plan.",
                human_input_mode="ALWAYS"
                if self.task.get("include_human_feedback")
                else "NEVER",
                code_execution_config=False,
            ),
        }

        group_chat = GroupChat(
            agents=list(agents.values()),
            messages=[],
            max_round=1,
            speaker_selection_method="manual",
        )
        manager = GroupChatManager(groupchat=group_chat, llm_config=llm_config)
        return agents, manager
````

### 21. 主编智能体：规划、分派、审核、合稿

位置：`deep_agents/agent.py:11` · `CHIEF_EDITOR_PROMPT`

使用范围：独立实验实现；不等同于网页的普通研究流程。

````python
CHIEF_EDITOR_PROMPT = """You are the Chief Editor of an autonomous research team. \
Your job is to produce a comprehensive, well-cited research report on the topic \
the user gives you.

You do not research topics yourself. You coordinate, review, and edit. Follow \
this workflow:

1. **Scope**: Use `quick_search` once to get an overview of the topic.
2. **Plan**: Use `write_todos` to plan the report: one todo per section \
(respect the maximum number of sections in the request), plus todos for review \
and final assembly.
3. **Delegate**: For each section, call the `task` tool with the `researcher` \
subagent. Issue all section tasks in a single turn so they run in parallel. \
Tell each researcher:
   - the section topic and the overall report topic,
   - the exact file to write its draft to: `sections/<two-digit-index>-<slug>.md`.
4. **Review**: Read each draft with `read_file`. If a draft is off-topic, \
shallow, or violates the guidelines, delegate a revision to the `researcher` \
subagent with concrete feedback. Fix small issues yourself with `edit_file`.
5. **Assemble**: Write the final report to `report.md`. It must contain:
   - a title and a short introduction you write yourself,
   - a table of contents,
   - the section drafts, edited for flow and consistent formatting (remove \
per-section source lists),
   - a conclusion you write yourself,
   - a single deduplicated **References** section at the end containing every \
source cited across sections.

Rules:
- Never write a section's body from your own knowledge; section content must \
come from researcher drafts.
- Keep your own messages short; the report lives in files, not in chat.
- When you are done, reply with a one-paragraph summary of the report and the \
path `report.md`.
{guidelines}"""
````

### 22. 研究子智能体：调用研究工具并写章节

位置：`deep_agents/agent.py:47` · `RESEARCHER_PROMPT`

使用范围：独立实验实现；不等同于网页的普通研究流程。

````python
RESEARCHER_PROMPT = """You are a research specialist on a report-writing team. \
You will be given one section of a larger report to research and a file path to \
write your draft to.

Follow these steps:
1. Call `deep_research` exactly once with the section topic as `query` and the \
overall report topic as `parent_query`. It returns a detailed, cited markdown \
report - this is your source material.
2. Write the draft to the file path you were given using `write_file`. The \
draft must be well-structured markdown, keep all in-text citations and source \
URLs from the research, and start with a `##` heading for the section.
3. Reply with only: a summary of the section in at most 150 words, the number \
of sources used, and the file path. Never paste the full draft into your reply.
"""
````

### 23. 附加 guidelines 指令和研究子智能体英文能力描述

位置：`deep_agents/agent.py:63` · `build_agent`

使用范围：独立实验实现；不等同于网页的普通研究流程。

````python
def build_agent(task: dict, run_dir: str):
    model = os.environ.get("STRATEGIC_LLM") or task.get("model", "openai:gpt-5.4")
    quick_search, deep_research = build_research_tools(task)

    guidelines = ""
    if task.get("follow_guidelines") and task.get("guidelines"):
        rules = "\n".join(f"- {g}" for g in task["guidelines"])
        guidelines = f"\nThe report MUST follow these guidelines:\n{rules}"

    researcher_subagent = {
        "name": "researcher",
        "description": (
            "Conducts in-depth, citation-grade research on one report section "
            "and writes a draft to a file. Give it one section topic, the "
            "overall report topic, and the target file path."
        ),
        "system_prompt": RESEARCHER_PROMPT,
        "tools": [quick_search, deep_research],
    }

    return create_deep_agent(
        model=model,
        tools=[quick_search],
        system_prompt=CHIEF_EDITOR_PROMPT.format(guidelines=guidelines),
        subagents=[researcher_subagent],
        backend=FilesystemBackend(root_dir=run_dir, virtual_mode=True),
    )
````

### 24. 用户任务包装：研究主题及最多章节数

位置：`deep_agents/main.py:33` · `build_request`

使用范围：按对应功能启用时使用。

````python
def build_request(task: dict) -> str:
    return (
        f"Research the following topic and produce a report: {task['query']}\n"
        f"The report should have at most {task.get('max_sections', 3)} sections."
    )
````

### 25. 澄清研究方向：system + user

位置：`backend/report_type/deep_research/example.py:55` · `generate_feedback`

使用范围：示例脚本，不是现行 DeepResearchSkill 的实现。

````python
    async def generate_feedback(self, query: str, num_questions: int = 3) -> List[str]:
        """Generate follow-up questions to clarify research direction"""
        messages = [
            {
                "role": "system",
                "content": (
                    "You are an expert researcher helping to clarify research directions. "
                    "Return valid JSON only."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"Given the following query from the user, ask some follow up questions to clarify the research direction. "
                    f"Return a maximum of {num_questions} questions, but feel free to return less if the original query is clear.\n\n"
                    'Return ONLY a JSON object using this exact schema:\n{"questions": ["<question 1>", "<question 2>"]}\n\n'
                    f"Query: {query}"
                ),
            },
        ]

        response = await create_chat_completion(
            messages=messages,
            llm_provider=LLM_PROVIDER,
            model=O3_MINI_MODEL,  # Using reasoning model for better question generation
            temperature=0.7,
            max_tokens=500,
            reasoning_effort=ReasoningEfforts.High.value
        )

        return parse_follow_up_questions_response(response, num_questions)
````

### 26. 生成搜索词：system + user

位置：`backend/report_type/deep_research/example.py:87` · `generate_serp_queries`

使用范围：示例脚本，不是现行 DeepResearchSkill 的实现。

````python
    async def generate_serp_queries(self, query: str, num_queries: int = 3) -> List[Dict[str, str]]:
        """Generate SERP queries for research"""
        messages = [
            {
                "role": "system",
                "content": (
                    "You are an expert researcher generating search queries. "
                    "Return valid JSON only. Do not include markdown, code fences, bullets, numbering, or prose."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"Given the following prompt, generate {num_queries} unique search queries to research the topic thoroughly. "
                    "For each query, provide a research goal.\n\n"
                    'Return ONLY a JSON array of objects using this exact schema:\n'
                    '[{"query": "<search query>", "researchGoal": "<research goal>"}]\n\n'
                    f"Prompt: {query}"
                ),
            },
        ]

        response = await create_chat_completion(
            messages=messages,
            llm_provider=LLM_PROVIDER,
            model=GPT4_MODEL,  # Using GPT-4 for general task
            temperature=0.7,
            max_tokens=1000
        )

        return parse_search_queries_response(response, num_queries)
````

### 27. 提取研究结论和后续问题：system + user

位置：`backend/report_type/deep_research/example.py:119` · `process_serp_result`

使用范围：示例脚本，不是现行 DeepResearchSkill 的实现。

````python
    async def process_serp_result(self, query: str, context: str, num_learnings: int = 3) -> Dict[str, List[str]]:
        """Process research results to extract learnings and follow-up questions"""
        messages = [
            {
                "role": "system",
                "content": (
                    "You are an expert researcher analyzing search results. "
                    "Return valid JSON only."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"Given the following research results for the query '{query}', extract key learnings and suggest "
                    "follow-up questions. For each learning, include a citation to the source URL if available.\n\n"
                    'Return ONLY a JSON object using this exact schema:\n'
                    '{"learnings": [{"insight": "<insight>", "sourceUrl": "<url or empty string>"}], '
                    '"followUpQuestions": ["<question 1>", "<question 2>"]}\n\n'
                    f"Research results:\n{context}"
                ),
            },
        ]

        response = await create_chat_completion(
            messages=messages,
            llm_provider=LLM_PROVIDER,
            model=O3_MINI_MODEL,  # Using reasoning model for analysis
            temperature=0.7,
            max_tokens=1000,
            reasoning_effort=ReasoningEfforts.High.value
        )

        return parse_research_results_response(response, num_learnings)
````

### 28. 基准测试研究员系统提示

位置：`deep_agents/benchmark.py:34` · `SYSTEM_PROMPT`

使用范围：仅运行对应评测时使用。

````python
SYSTEM_PROMPT = f"""You are an expert researcher. Today's date is \
{datetime.now().strftime('%B %d, %Y')}. Use your tools to research the user's \
question and answer it accurately. Always ground your answer in researched \
sources rather than your own memory - your training data predates today, so \
anything time-sensitive (versions, prices, events, results) must come from \
research. If the research does not surface the answer, say you could not find \
it instead of guessing. End your final reply with a short, direct answer to \
the question."""
````

### 29. 基线 Tavily 工具说明

位置：`deep_agents/benchmark.py:43` · `BASELINE_TOOL_GUIDE`

使用范围：仅运行对应评测时使用。

````python
BASELINE_TOOL_GUIDE = """

## `internet_search`

Use this to run an internet search for a given query. You can specify the max
number of results to return, the topic, and whether raw content should be
included. Run as many searches with refined queries as you need."""
````

### 30. ResearchRunner 工具说明

位置：`deep_agents/benchmark.py:51` · `GPTR_TOOL_GUIDE`

使用范围：仅运行对应评测时使用。

````python
GPTR_TOOL_GUIDE = """

## `quick_search`

Your primary tool for factual lookups and for discovering what is currently
true. Run it as many times as you need with refined queries (names, dates,
alternate phrasings). For multi-part questions, decompose them and look up
each fact separately. Before any deep research, use it to establish the
current state of the topic (latest names, versions, events, dates) so later
queries do not carry stale assumptions.

## `deep_research`

A full research pipeline that is slow but has much deeper recall (it scrapes
and reads dozens of pages). Escalate to it when repeated quick_search calls
cannot surface the fact, or the task genuinely requires deep multi-source
research. Phrase its queries neutrally ("the current X lineup and pricing") -
never bake names, versions or facts recalled from your own memory into a
query, because the pipeline will faithfully research whatever you assert.
When several sources disagree, prefer the most authoritative source (e.g. an
encyclopedia or official page). Before finalizing an answer or report, verify
load-bearing specifics (scores, prices, versions, names, dates) with
quick_search rather than trusting a single summary."""
````

### 31. 私有文件检索和使用要求

位置：`deep_agents/hybrid_benchmark.py:71` · `FILES_GUIDE`

使用范围：仅运行对应评测时使用。

````python
FILES_GUIDE = """

## Internal documents

The internal company documents for this task are mounted on your file tools:
use `ls` to explore the directories and `read_file` to read documents. They
are the only source for internal company facts. Prefer the most recent
document when several vintages of the same report exist."""
````

### 32. 尽职调查任务请求及检查数据

位置：`deep_agents/hybrid_benchmark.py:121` · `TASK`

使用范围：仅运行对应评测时使用。

````python
TASK = {
    "id": "veltrix_dd",
    "prompt": (
        "You are preparing a due-diligence brief on Veltrix Dynamics B.V., a "
        "Rotterdam-based warehouse robotics (AMR) company we are evaluating. "
        "Internal company documents are available to your research tools if "
        "they support document research. Write a brief covering: (1) the "
        "company's funding history and current financial trajectory, (2) its "
        "product line and 2026 roadmap including pricing, (3) operational "
        "performance and key risks, and (4) how it is positioned against the "
        "current competitive landscape in warehouse AMRs (Geek+, Locus "
        "Robotics, Exotec), using up-to-date market facts. Be specific about "
        "numbers, dates and names, and cite your sources."
    ),
    # Facts that exist ONLY in the private corpus.
    "internal_checkpoints": [
        "Veltrix has raised $142M total, most recently an $85M Series C in October 2025 led by Meridian Growth Partners at a $610M post-money valuation.",
        "Q1 2026 revenue was $21.4M (+67% YoY) with RaaS ARR of $46.9M; the 2026 revenue target is $96M.",
        "The current VLX-400 lists at $48,500 per robot, with RaaS at $1,950 per robot per month.",
        "The VLX-500 launches in Q4 2026 at a $56,000 list price, with 950 kg payload and a -30°C cold rating.",
        "The deployed fleet is 3,120 robots across 47 sites, with 99.2% availability in Q1 2026.",
        "Key risks include customer concentration (top 3 customers = 44% of ARR) and the VLX-500's solid-state LiDAR cold-chamber certification (in test cycle 2 of 3), which could slip the launch to Q2 2027.",
        "Veltrix's moat is cold-chain: certified operation down to -25°C today, 61% of ARR from frozen/chilled distribution centers, and a 71% win rate in cold-chain RFPs.",
        "A February 11, 2026 firmware regression (v4.7.2) caused localization drift at 14 sites and was resolved within 36 hours with no SLA penalties.",
    ],
    # Real-world market facts verified from public sources (July 2026).
    "web_checkpoints": [
        "Geek+ (Geekplus) went public on the Hong Kong Stock Exchange in July 2025 - the first IPO in the AMR warehouse robotics market - raising roughly $280M at about a $2.8B market cap.",
        "Geek+ is the global warehouse-fulfillment AMR market share leader by revenue (per Interact Analysis, for roughly seven consecutive years).",
        "Locus Robotics remains private, with total funding of roughly $410-440M and 13,000+ robots deployed.",
        "Exotec is a French warehouse robotics unicorn known for its Skypod ASRS system and remains private (roughly $446M raised).",
    ],
    "official_domains": [],
}
````

### 33. 评测报告生成请求

位置：`deep_agents/report_benchmark.py:65` · `REPORT_REQUEST`

使用范围：仅运行对应评测时使用。

````python
REPORT_REQUEST = (
    "Write a well-researched, well-structured report on the following topic. "
    "Ground every claim in researched sources and cite them with URLs. "
    "Topic: {topic}"
)
````

### 34. 两个报告的对比评分

位置：`deep_agents/report_benchmark.py:78` · `JUDGE_TEMPLATE`

使用范围：仅运行对应评测时使用。

````python
JUDGE_TEMPLATE = """You are an expert evaluator of research reports. Two research \
assistants were given the same topic and wrote the reports below. Judge them on \
exactly one dimension:

{dimension_question}

Topic: {topic}

=== REPORT A ===
{report_a}

=== REPORT B ===
{report_b}

=== END OF REPORTS ===

Reply with exactly one letter: "A" if Report A is better on this dimension, \
"B" if Report B is better, or "T" if they are genuinely tied. Do not reply \
with anything else."""
````

### 35. 按事实检查点评估报告

位置：`deep_agents/recency_benchmark.py:191` · `GRADER_TEMPLATE`

使用范围：仅运行对应评测时使用。

````python
GRADER_TEMPLATE = """You are grading a technical report against ground-truth \
checkpoints. The ground truth below was verified from official sources and is \
authoritative - judge ONLY whether the report is consistent with it. Ignore \
your own knowledge if it conflicts with the ground truth.

For each checkpoint, grade the report as:
- CORRECT: the report states the checkpoint fact, or gives specifics fully consistent with it
- PARTIAL: the report addresses the topic but is incomplete or vague on the fact
- MISSING: the report does not address the topic of this checkpoint
- WRONG: the report makes a claim that CONTRADICTS the checkpoint (e.g. states an older version as current, wrong price, deprecated API as current practice)

Checkpoints:
{checkpoints}

=== REPORT ===
{report}
=== END REPORT ===

Reply with JSON only, no other text: an array with one object per checkpoint in \
order: [{{"checkpoint": 1, "grade": "CORRECT|PARTIAL|MISSING|WRONG", "evidence": "short quote or note"}}, ...]"""
````

### 36. DeepResearch Bench 专用研究系统指令

位置：`deep_agents/drb_generate.py:35` · `REPORT_SYSTEM_PROMPT`

使用范围：仅运行对应评测时使用。

````python
REPORT_SYSTEM_PROMPT = f"""You are an expert research analyst. Today's date \
is {datetime.now().strftime('%B %d, %Y')}. Use your tools to research the \
user's request thoroughly, then write a comprehensive, in-depth research \
report that fully addresses it.

Requirements for the final report:
- It must be a complete, well-structured markdown report (headings, sections,
  and where useful tables) - not a chat reply or a summary of what you did.
- Ground every substantive claim in researched sources, not your own memory -
  your training data predates today.
- Cite sources inline throughout the body as markdown links, e.g.
  ([reuters.com](https://www.reuters.com/...)), immediately after the claims
  they support. Every section should carry citations; aim to cite every
  distinct source your research surfaced that you actually used. Do not
  invent URLs - only cite URLs that appeared in your research results.
- Be comprehensive: cover breadth (all angles of the request) and depth
  (specific figures, dates, names) rather than generic statements.

Your final message must be the report itself, nothing else."""
````

### 37. SimpleQA 正确性评判模板

位置：`evals/simple_evals/simpleqa_eval.py:14` · `GRADER_TEMPLATE`

使用范围：仅运行对应评测时使用。

````python
GRADER_TEMPLATE = """
Your job is to look at a question, a gold target, and a predicted answer, and then assign a grade of either ["CORRECT", "INCORRECT", "NOT_ATTEMPTED"].
First, I will give examples of each grade, and then you will grade a new example.


The following are examples of CORRECT predicted answers.
```
Question: What are the names of Barack Obama's children?
Gold target: Malia Obama and Sasha Obama
Predicted answer 1: sasha and malia obama
Predicted answer 2: most people would say Malia and Sasha, but I'm not sure and would have to double check
Predicted answer 3: Barack Obama has two daughters. Their names are Malia Ann and Natasha Marian, but they are commonly referred to as Malia Obama and Sasha Obama. Malia was born on July 4, 1998, and Sasha was born on June 10, 2001.
```
These predicted answers are all CORRECT because:
    - They fully contain the important information in the gold target.
    - They do not contain any information that contradicts the gold target.
    - Only semantic meaning matters; capitalization, punctuation, grammar, and order don't matter.
    - Hedging and guessing are permissible, provided that the gold target is fully included and the response contains no incorrect information or contradictions.


The following are examples of INCORRECT predicted answers.
```
Question: What are the names of Barack Obama's children?
Gold target: Malia and Sasha
Predicted answer 1: Malia.
Predicted answer 2: Malia, Sasha, and Susan.
Predicted answer 3: Barack Obama does not have any children.
Predicted answer 4: I think it's either Malia and Sasha. Or it could be Malia and Jackie. Or it could be Joey and Malia.
Predicted answer 4: While I don't know their exact names, I can tell you that Barack Obama has three children.
Predicted answer 5: It's possible you may mean Betsy and Olivia. However, you should clarify further details with updated references if necessary. Is that the correct answer?
Predicted answer 6: It may be the case that Obama's child is named James. However, it's recommended to confirm the most accurate and updated information since this could change over time. This model may not always reflect the most current information.
```
These predicted answers are all INCORRECT because:
    - A factual statement in the answer contradicts the gold target. Incorrect statements that have some hedging (e.g., "it is possible that", "although i'm not sure, i think") are also considered incorrect.


The following are examples of NOT_ATTEMPTED predicted answers.
```
Question: What are the names of Barack Obama's children?
Gold target: Malia and Sasha
Predicted answer 1: I don't know.
Predicted answer 2: I need more context about which Obama you are talking about.
Predicted answer 3: Without researching the web, I cannot answer this question. However, I can tell you that Barack Obama has two children.
Predicted answer 4: Barack Obama has two children. I know that one of them is Malia, but I'm not sure about the other one.
```
These predicted answers are all NOT_ATTEMPTED because:
    - The important information in the gold target is not included in the answer.
    - No statements in the answer contradict the gold target.


Also note the following things:
- For grading questions where the gold target is a number, the predicted answer needs to be correct to the last significant figure in the gold answer. For example, consider a question "How many citations does the Transformer Paper have?" with gold target "120k". 
    - Predicted answers "120k", "124k", and 115k" are all CORRECT. 
    - Predicted answers "100k" and "113k" are INCORRECT. 
    - Predicted answers "around 100k" and "more than 50k" are considered NOT_ATTEMPTED because they neither confirm nor contradict the gold target.
- The gold target may contain more information than the question. In such cases, the predicted answer only needs to contain the information that is in the question.
    - For example, consider the question "What episode did Derek and Meredith get legally married in Grey's Anatomy?" with gold target "Season 7, Episode 20: White Wedding". Either "Season 7, Episode 20" or "White Wedding" would be considered a CORRECT answer.
- Do not punish predicted answers if they omit information that would be clearly inferred from the question.
    - For example, consider the question "What city is OpenAI headquartered in?" and the gold target "San Francisco, California". The predicted answer "San Francisco" would be considered CORRECT, even though it does not include "California".
    - Consider the question "What award did A pretrainer's guide to training data: Measuring the effects of data age, domain coverage, quality, & toxicity win at NAACL '24?", the gold target is "Outstanding Paper Award". The predicted answer "Outstanding Paper" would be considered CORRECT, because "award" is presumed in the question.
    - For the question "What is the height of Jason Wei in meters?", the gold target is "1.73 m". The predicted answer "1.75" would be considered CORRECT, because meters is specified in the question.
    - For the question "What is the name of Barack Obama's wife?", the gold target is "Michelle Obama". The predicted answer "Michelle" would be considered CORRECT, because the last name can be presumed.
- Do not punish for typos in people's name if it's clearly the same name. 
    - For example, if the gold target is "Hyung Won Chung", you can consider the following predicted answers as correct: "Hyoong Won Choong", "Hyungwon Chung", or "Hyun Won Chung".


Here is a new example. Simply reply with either CORRECT, INCORRECT, NOT ATTEMPTED. Don't apologize or correct yourself if there was a mistake; we are just trying to grade the answer.
```
Question: {question}
Gold target: {target}
Predicted answer: {predicted_answer}
```

Grade the predicted answer of this new question as one of:
A: CORRECT
B: INCORRECT
C: NOT_ATTEMPTED

Just return the letters "A", "B", or "C", with no text around it.
""".strip()
````

### 38. Source/Title/Content、Context from ... 或 Granite 文档包装

位置：`search/prompts.py:557` · `pretty_print_docs`

使用范围：包含模型专用格式；不是独立角色提示。

````python
    def pretty_print_docs(docs: list[Document], top_n: int | None = None) -> str:
        """Compress the list of documents into a context string"""
        return f"\n".join(f"Source: {d.metadata.get('source')}\n"
                          f"Title: {d.metadata.get('title')}\n"
                          f"Content: {d.page_content}\n"
                          for i, d in enumerate(docs)
                          if top_n is None or i < top_n)
````

### 39. Source/Title/Content、Context from ... 或 Granite 文档包装

位置：`search/prompts.py:773` · `pretty_print_docs`

使用范围：包含模型专用格式；不是独立角色提示。

````python
    def pretty_print_docs(self, *args, **kwargs) -> str:
        return self._get_granite_class().pretty_print_docs(*args, **kwargs)
````

### 40. Source/Title/Content、Context from ... 或 Granite 文档包装

位置：`search/prompts.py:787` · `pretty_print_docs`

使用范围：包含模型专用格式；不是独立角色提示。

````python
    def pretty_print_docs(cls, docs: list[Document], top_n: int | None = None) -> str:
        if not docs:
            return ""
        all_documents = "\n\n".join([
            f"Document {doc.metadata.get('source', i)}\n" + \
            f"Title: {doc.metadata.get('title')}\n" + \
            doc.page_content
            for i, doc in enumerate(docs)
            if top_n is None or i < top_n
        ])
        return "".join([cls._DOCUMENTS_PREFIX, all_documents, cls._DOCUMENTS_SUFFIX])
````

### 41. Source/Title/Content、Context from ... 或 Granite 文档包装

位置：`search/prompts.py:825` · `pretty_print_docs`

使用范围：包含模型专用格式；不是独立角色提示。

````python
    def pretty_print_docs(cls, docs: list[Document], top_n: int | None = None) -> str:
        return "\n".join([
            cls._DOCUMENT_TEMPLATE.format(
                document_id=doc.metadata.get("source", i),
                document_content=cls._get_content(doc),
            )
            for i, doc in enumerate(docs)
            if top_n is None or i < top_n
        ])
````

### 42. Source/Title/Content、Context from ... 或 Granite 文档包装

位置：`search/prompts.py:566` · `join_local_web_documents`

使用范围：包含模型专用格式；不是独立角色提示。

````python
    def join_local_web_documents(docs_context: str, web_context: str) -> str:
        """Joins local web documents with context scraped from the internet"""
        return f"Context from local documents: {docs_context}\n\nContext from web sources: {web_context}"
````

### 43. Source/Title/Content、Context from ... 或 Granite 文档包装

位置：`search/prompts.py:776` · `join_local_web_documents`

使用范围：包含模型专用格式；不是独立角色提示。

````python
    def join_local_web_documents(self, *args, **kwargs) -> str:
        return self._get_granite_class().join_local_web_documents(*args, **kwargs)
````

### 44. Source/Title/Content、Context from ... 或 Granite 文档包装

位置：`search/prompts.py:800` · `join_local_web_documents`

使用范围：包含模型专用格式；不是独立角色提示。

````python
    def join_local_web_documents(cls, docs_context: str | list, web_context: str | list) -> str:
        """Joins local web documents using Granite's preferred format"""
        if isinstance(docs_context, str) and docs_context.startswith(cls._DOCUMENTS_PREFIX):
            docs_context = docs_context[len(cls._DOCUMENTS_PREFIX):]
        if isinstance(web_context, str) and web_context.endswith(cls._DOCUMENTS_SUFFIX):
            web_context = web_context[:-len(cls._DOCUMENTS_SUFFIX)]
        all_documents = "\n\n".join([docs_context, web_context])
        return "".join([cls._DOCUMENTS_PREFIX, all_documents, cls._DOCUMENTS_SUFFIX])
````

### 45. Source/Title/Content、Context from ... 或 Granite 文档包装

位置：`search/prompts.py:836` · `join_local_web_documents`

使用范围：包含模型专用格式；不是独立角色提示。

````python
    def join_local_web_documents(cls, docs_context: str | list, web_context: str | list) -> str:
        """Joins local web documents using Granite's preferred format"""
        return "\n\n".join([docs_context, web_context])
````

### 46. Source/Title/Content、Context from ... 或 Granite 文档包装

位置：`search/prompts.py:818` · `_get_content`

使用范围：包含模型专用格式；不是独立角色提示。

````python
    def _get_content(doc: Document) -> str:
        doc_content = doc.page_content
        if title := doc.metadata.get("title"):
            doc_content = f"Title: {title}\n{doc_content}"
        return doc_content.strip()
````

### 47. quick_search 工具及参数的英文 description；聊天主 system 已是中文

位置：`backend/chat/chat.py:56` · `__init__`

使用范围：按对应功能启用时使用。

````python
    def __init__(
        self,
        report: str,
        config_path="default",
        headers=None,
        vector_store=None
    ):
        self.report = report
        self.headers = headers
        self.config = Config(config_path)
        self.vector_store = vector_store
        self.retriever = None
        self.search_metadata = None
        
        # Initialize Tavily client (optional - only if API key is available)
        tavily_api_key = os.environ.get("TAVILY_API_KEY")
        if tavily_api_key:
            self.tavily_client = TavilyClient(api_key=tavily_api_key)
        else:
            self.tavily_client = None
            logger.warning("TAVILY_API_KEY not set - web search in chat will be disabled")
        
        # Process document and create vector store if not provided
        if not self.vector_store and False:
            self._setup_vector_store()
````

### 48. quick_search/deep_research 工具 docstring 会作为模型可见说明

位置：`deep_agents/tools.py:9` · `build_research_tools`

使用范围：按对应功能启用时使用。

````python
def build_research_tools(task: dict, cost_tracker: dict | None = None):
    """Build the research tools with the task's source config bound in.

    The report source ("web", "local" or "hybrid") is task configuration, not
    something the agent should decide per call, so it is closed over here
    rather than exposed as a tool argument. For "local" and "hybrid", set the
    DOC_PATH env var to your documents directory.

    Pass a dict as cost_tracker to accumulate ResearchRunner API costs under
    its "total" key.
    """
    report_source = task.get("source", "web")

    def _track_cost(researcher: Research):
        if cost_tracker is not None:
            cost_tracker["total"] = cost_tracker.get("total", 0.0) + researcher.get_costs()

    async def quick_search(query: str) -> str:
        """Run a fast web search and return the top results with snippets and URLs.

        Use this to scope a topic, verify a fact, or gather just enough context
        to plan a report outline. Results are raw snippets - iterate with
        refined queries if the first search does not surface what you need.
        For in-depth, citation-grade research on a section, use deep_research
        instead.
        """
        researcher = Research(query=query, report_type="research_report", verbose=False)
        results = await researcher.quick_search(query)
        _track_cost(researcher)
        if isinstance(results, list):
            formatted = "\n\n".join(
                f"[{i}] {r.get('title') or ''}\n{r.get('body') or r.get('content') or ''}\n"
                f"URL: {r.get('href') or r.get('url') or ''}".strip()
                for i, r in enumerate(results, 1)
            )
            return formatted or "No results found."
        return results or "No results found."

    async def deep_research(query: str, parent_query: str = "") -> str:
        """Run the full ResearchRunner pipeline on a query.

        Plans sub-queries, gathers and validates dozens of sources (web pages
        and/or local documents, depending on the task configuration), and
        returns a detailed markdown research report with in-text citations and
        a reference list. This is slow (a few minutes) and thorough - use it
        once per report section, not for quick lookups.

        Args:
            query: The research question or section topic to investigate.
            parent_query: The overall report topic, when researching a section
                of a larger report. Keeps the research focused in context.
        """
        report_type = "subtopic_report" if parent_query else "research_report"
        researcher = Research(
            query=query,
            report_type=report_type,
            report_source=report_source,
            parent_query=parent_query,
            verbose=False,
        )
        await researcher.conduct_research()
        report = await researcher.write_report()
        _track_cost(researcher)

        sources = researcher.get_source_urls()
        if sources:
            report += "\n\n### Sources\n\n" + "\n".join(f"- {url}" for url in sources)
        return report

    return quick_search, deep_research
````

### 49. 搜索工具的英文 docstring 和失败提示

位置：`search/utils/tools.py:230` · `create_search_tool`

使用范围：按对应功能启用时使用。

````python
def create_search_tool(search_function: Callable[[str], Dict]) -> Callable:
    """
    Create a standardized search tool for use with tool-enabled chat completions.
    
    Args:
        search_function: Function that takes a query string and returns search results
        
    Returns:
        LangChain tool function decorated with @tool
    """
    @tool
    def search_tool(query: str) -> str:
        """Search for current events or online information when you need new knowledge that doesn't exist in the current context"""
        try:
            results = search_function(query)
            if results and 'results' in results:
                search_content = f"Search results for '{query}':\n\n"
                for result in results['results'][:5]:
                    search_content += f"Title: {result.get('title', '')}\n"
                    search_content += f"Content: {result.get('content', '')[:300]}...\n"
                    search_content += f"URL: {result.get('url', '')}\n\n"
                return search_content
            else:
                return f"No search results found for: {query}"
        except Exception as e:
            error_type = type(e).__name__
            error_msg = str(e)
            logger.error(
                f"Search tool error: {error_type}: {error_msg}",
                exc_info=True
            )
            # Provide context-aware error messages
            if "api" in error_msg.lower() or "key" in error_msg.lower():
                return f"Search failed: API key issue. Please verify your search API credentials are configured correctly."
            elif "timeout" in error_msg.lower() or "timed out" in error_msg.lower():
                return f"Search timed out. The search request took too long. Please try again with a different query."
            elif "rate limit" in error_msg.lower() or "quota" in error_msg.lower():
                return f"Search rate limit exceeded. Please wait a moment before trying again."
            else:
                return f"Search encountered an error: {error_msg}. Please check your search provider configuration."
    
    return search_tool
````

