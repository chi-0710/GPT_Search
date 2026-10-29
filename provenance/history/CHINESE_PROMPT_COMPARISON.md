# 知行研究助手（ResearchRunner）提示词中文化：修改前后对照（待审批）

日期：2026-09-18。**本文件是翻译方案，尚未修改运行代码、配置、测试或原清单。**

## 审批范围和保留规则

覆盖 `ENGLISH_PROMPT_INVENTORY.md` 的 1–49 项，并补充清单提到的 guidelines、schema 字段说明，以及源码中注入评测提示的四个评分维度。按原编号逐项展示修改前源码与修改后提示词正文；后者不是可直接覆盖源码的 Python 文件。源码里的注释、函数签名和调用逻辑不属于翻译对象。

- 仅翻译模型可见的自然语言；不更改算法、模型选择、temperature、token 上限、条件分支或调用次数。
- 保留 JSON 键、数据类型、结构、枚举、工具名、参数名、路径、URL、颜色代码、协议标记及动态表达式。示例中的普通描述可翻译，结构示意占位文字（如 `<search query>`）保留，避免混淆格式契约。
- `ONLY` → “仅/只能”；`MUST` → “必须”；`EXACTLY` → “恰好”；`at most/maximum` → “最多”；`if available` → “如有”。不把建议升级为硬性要求，不把禁止改成建议。
- 数量、编号起点、引用位置、字母评分输出要求全部保留。`words` 译为“词（words）”，不擅自换成汉字数；中英文长度并不等价。
- 原文没有全局“所有输出必须为中文”的指令，本方案不额外添加该行为约束，也不要求搜索词只能用中文。已有语言配置继续生效；中文模板不能保证外部工具返回内容也为中文。
- 动态用户输入、外部 MCP 描述、依赖库内置格式提示、评测题库和标准答案不作机械翻译。Granite 专有协议包装保留。
- 清单第 47 项源码位置不完整：实际工具说明在 `backend/chat/chat.py` 的 `get_tools()`，审批对照已补正。

## 逐项对照

### 1. 搜索词生成与实时上下文

位置：[search/prompts.py:213](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:213>) · `generate_search_queries_prompt`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）— 有上下文时拼接的内容：**

```text
你是一名资深研究助手，负责为以下任务生成搜索词，以查找相关信息：“{task}”。
上下文：{context}

请利用这些上下文来指导并优化搜索词。上下文提供的实时网络信息可以帮助你生成更具体、更相关的查询。请考虑其中提到的时事、近期进展或具体细节，以改进搜索词。
```

**修改后（拟）— 主提示：**

```text
请生成 {max_iterations} 条搜索词，用于研究以下任务：“{task}”

每条搜索词必须是普通的自然语言短语。不要使用搜索运算符语法，例如 site:、filetype:、inurl:、intitle:、OR、AND 或 NOT——这些运算符并非得到普遍支持，在许多搜索后端上会返回空结果。

如有需要，请假定当前日期为 {datetime.now(timezone.utc).strftime('%B %d, %Y')}。

{context_prompt}
你必须按照以下格式返回字符串列表：[{dynamic_example}]。
回复只能包含该列表。
```

`dynamic_example` 中普通示例文本 `query {i+1}` 拟译为 `查询 {i+1}`；列表结构、双引号及生成数量不变。

### 2. 自动选择智能体角色

位置：[search/prompts.py:492](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:492>) · `auto_agent_instructions`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
本任务需要研究给定主题，不论该主题有多复杂，也不论是否存在确定答案。研究由一个特定服务角色执行，该服务角色由其类型和职责定义，不同服务角色需要不同的指令。
智能体
根据主题所属领域，以及可用于研究该主题的服务角色的具体名称，确定相应服务角色。智能体按专业领域分类，每种服务角色类型都对应一个表情符号。

示例：
任务：“我应该投资苹果公司的股票吗？”
回复：
{
    "server": "💰 金融智能体",
    "agent_role_prompt": "你是一名资深的金融分析 AI 助手。你的首要目标是根据提供的数据和趋势，撰写全面、敏锐、公正且组织有序的金融报告。"
}
任务：“转售运动鞋能盈利吗？”
回复：
{
    "server": "📈 商业分析智能体",
    "agent_role_prompt": "你是一名经验丰富的 AI 商业分析助手。你的主要目标是根据提供的商业数据、市场趋势和战略分析，撰写全面、有洞察力、公正且结构系统的商业报告。"
}
任务：“特拉维夫有哪些最有趣的景点？”
回复：
{
    "server": "🌍 旅行智能体",
    "agent_role_prompt": "你是一名游历广泛的 AI 导游助手。你的主要任务是针对给定地点撰写引人入胜、有洞察力、客观且结构清晰的旅行报告，内容涵盖历史、景点和文化见解。"
}
```

原文 `server` 在自然语言中指代服务角色，输出字段仍原样保留为 `server`。不新增原文没有的“只能输出 JSON”指令。

### 3. 来源筛选

位置：[search/prompts.py:318](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:318>) · `curate_sources`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
你的目标是为研究任务“{query}”评估并筛选提供的抓取内容，优先纳入相关且优质的信息，尤其是包含统计资料、数字或具体数据的来源。

最终筛选出的列表将用作撰写研究报告的上下文，因此请优先：
- 尽可能保留原始信息，尤其重视包含定量数据或独特见解的来源
- 纳入广泛的观点和见解
- 仅过滤明显无关或无法使用的内容

评估准则：
1. 从以下方面评估每个来源：
   - 相关性：纳入与研究问题直接或部分相关的来源。不确定时倾向于保留。
   - 可信度：优先选择权威来源，但除非明显不可信，否则保留其他来源。
   - 时效性：优先使用近期信息，除非较早的数据不可或缺或具有价值。
   - 客观性：如果带有偏见的来源提供了独特或互补的视角，也应保留。
   - 定量价值：优先考虑包含统计资料、数字或其他具体数据的来源。
2. 来源选择：
   - 尽可能纳入相关来源，最多 {max_results} 个，注重覆盖广度和多样性。
   - 优先考虑包含统计资料、数值数据或可验证事实的来源。
   - 如果重叠内容能增加深度，尤其涉及数据时，可以接受内容重叠。
   - 仅当来源完全无关、严重过时，或因内容质量差而无法使用时，才将其排除。
3. 内容保留：
   - 不得改写、概括或压缩任何来源内容。
   - 保留所有可用信息，仅清理明显的垃圾内容或格式问题。
   - 如果相关性较弱或不完整的来源包含有价值的数据或见解，也应保留。

待评估的来源列表：
{sources}

你必须严格按照原始来源的 sources JSON 列表格式返回回复。
回复不得包含任何 Markdown 格式或额外文字（例如 ```json），只能包含 JSON 列表！
```

### 4. 来源摘要

位置：[search/prompts.py:520](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:520>) · `generate_summary_prompt`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
{data}
请根据以下任务或查询概括上述文本：“{query}”。
如果无法使用该文本回答查询，你必须简要概括该文本。
如有数字、统计资料、引文等事实信息，请全部纳入。
```

### 5. 默认角色回退提示

位置：[search/actions/agent_creator.py:65](<F:/Desk/面试简历/gpt-researcher-main/search/actions/agent_creator.py:65>) · `handle_json_error`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
你是一名具备批判性思维的 AI 研究助手。你的唯一目标是根据给定文本撰写文笔出色、广受好评、客观且结构清晰的报告。
```

返回的展示名称 `Default Agent` → `默认智能体`。保留原文 `critically acclaimed` 的“广受好评”含义，不擅自改成另一项评价标准。

### 6. 角色选择输入包装

位置：[search/actions/agent_creator.py:18](<F:/Desk/面试简历/gpt-researcher-main/search/actions/agent_creator.py:18>) · `choose_agent`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
任务：{query}
```

与第 2 项示例中的“任务”标签一致；`role: system/user` 不变。

### 7. URL 内容摘要

位置：[search/actions/report_generation.py:115](<F:/Desk/面试简历/gpt-researcher-main/search/actions/report_generation.py:115>) · `summarize_url`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
请概括来自 {url} 的以下内容：

{content}
```

### 8. 深度研究搜索词

位置：[search/skills/deep_research.py:259](<F:/Desk/面试简历/gpt-researcher-main/search/skills/deep_research.py:259>) · `generate_search_queries`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）— system：**

```text
你是一名负责生成搜索词的研究专家。只能返回有效的 JSON。不得包含 Markdown、代码围栏、项目符号、编号或说明性文字。
```

**修改后（拟）— user：**

```text
根据以下提示，生成 {num_queries} 条互不重复的搜索词，以全面研究该主题。为每条搜索词提供一个研究目标。

只能返回 JSON 对象数组，并严格使用以下结构：
[{"query": "<search query>", "researchGoal": "<research goal>"}]

提示：{query}
```

### 9. 深度研究追问规划

位置：[search/skills/deep_research.py:291](<F:/Desk/面试简历/gpt-researcher-main/search/skills/deep_research.py:291>) · `generate_research_plan`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）— system：**

```text
你是一名研究专家。你的任务是分析原始查询和搜索结果，然后生成有针对性的问题，探索该主题的不同方面和不同时期。只能返回有效的 JSON。
```

**修改后（拟）— user：**

```text
原始查询：{query}

当前时间：{current_time}

搜索结果：
{search_results}

根据这些结果、原始查询和当前时间，生成 {num_questions} 个互不重复的问题。每个问题应探索该主题的不同方面或不同时期，并考虑截至 {current_time} 的近期进展。

只能返回 JSON 对象，并严格使用以下结构：
{{"questions": ["<question 1>", "<question 2>"]}}
```

双花括号是原 f-string 中的转义形式，运行时仍为单花括号。

### 10. 提取研究发现与后续问题

位置：[search/skills/deep_research.py:344](<F:/Desk/面试简历/gpt-researcher-main/search/skills/deep_research.py:344>) · `process_research_results`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）— system：**

```text
你是一名负责分析搜索结果的研究专家。只能返回有效的 JSON。
```

**修改后（拟）— user：**

```text
根据以下针对查询“{query}”的研究结果，提取关键研究发现并提出后续问题。对于每条研究发现，如有来源 URL，请附上该来源 URL 的引用。

只能返回 JSON 对象，并严格使用以下结构：
{"learnings": [{"insight": "<insight>", "sourceUrl": "<url or empty string>"}], "followUpQuestions": ["<question 1>", "<question 2>"]}

研究结果：
{context}
```

原提示没有要求返回恰好 `{num_learnings}` 条，不在翻译中补加。空来源仍允许空字符串。

### 11. MCP 工具选择

位置：[search/prompts.py:40](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:40>) · `generate_mcp_tool_selection_prompt`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
你是一名研究助手，帮助为研究查询选择最相关的工具。

研究查询：“{query}”

可用工具：
{json.dumps(tools_info, indent=2)}

任务：分析这些工具，选择恰好 {max_tools} 个最适合研究给定查询的工具。

选择标准：
- 选择能够提供与查询相关的信息、数据或见解的工具
- 优先选择能够搜索、检索或访问相关内容的工具
- 考虑能够相互补充的工具（例如不同的数据来源）
- 排除与研究主题明显无关的工具

严格按照以下格式返回 JSON 对象：
{{
  "selected_tools": [
    {{
      "index": 0,
      "name": "tool_name",
      "relevance_score": 9,
      "reason": "详细说明此工具为何相关"
    }}
  ],
  "selection_reasoning": "对整体选择策略的说明"
}}

选择恰好 {max_tools} 个工具，并按其与研究查询的相关性排序。
```

原参数虽叫 `max_tools`，正文要求的是“恰好”，本方案不改成“最多”；不擅自给评分加范围。

### 12. MCP 研究执行

位置：[search/prompts.py:86](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:86>) · `generate_mcp_research_prompt`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
你是一名可以使用专业工具的研究助手。你的任务是研究以下查询，并提供全面、准确的信息。

研究查询：“{query}”

指令：
1. 使用可用工具收集与查询相关的信息
2. 如有需要，调用多个工具，以实现全面覆盖
3. 如果工具调用失败或返回空结果，请尝试其他方法
4. 在可能的情况下，综合多个来源的信息
5. 聚焦能够直接回答查询的、基于事实且相关的信息

可用工具：{tool_names}

请开展深入研究并提供研究发现。有策略地使用工具，以收集最相关、最全面的信息。
```

### 13. 通用配图分析模板

位置：[search/prompts.py:122](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:122>) · `generate_image_analysis_prompt`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）— 章节包装：**

```text
### 第 {i+1} 节：{s['header']}
{s['content'][:500]}...
```

**修改后（拟）— 主提示：**

```text
分析以下研究报告章节，确定其中哪 {max_images} 个章节最能受益于插图或图解。

研究主题：{query}

报告章节：
{sections_text}

为每个推荐的章节提供：
1. 章节编号（从 1 开始）
2. 一条具体、详细的图像提示词，用于生成具有信息价值的插图
3. 简要解释该章节为何适合可视化

重要准则：
- 选择视觉表达确实能帮助理解的章节
- 聚焦本身适合视觉表达的概念、过程、比较、数据流或统计资料
- 避开纯文字分析、引言或结论章节
- 图像提示词应足够具体，以生成相关、专业的插图
- 图像应具有信息价值和教育意义，而非仅作装饰
- 可考虑示意图、流程图、对比图或概念插图

以 JSON 格式回复：
{{
    "suggestions": [
        {{
            "section_number": 1,
            "section_header": "章节标题",
            "image_prompt": "用于生成信息性插图的详细提示词……",
            "image_type": "diagram|flowchart|comparison|concept|data_visualization",
            "reason": "该章节适合可视化的原因"
        }}
    ]
}}

只返回 JSON，不要附加任何文字。
```

### 14. 通用图像提示词增强

位置：[search/prompts.py:178](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:178>) · `generate_image_prompt_enhancement`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
为研究报告创建一幅专业且具有信息价值的插图。

研究主题：{research_topic}

图像描述：{base_prompt}

报告上下文：
{section_content[:800]}

风格要求：
- 采用适合学术或商业报告的专业、简洁设计
- 视觉元素清晰、易于理解
- 现代、极简的美学风格
- 使用专业配色（蓝色、蓝绿色、灰色）
- 避免图中出现过多文字
- 使用高对比度以便阅读
- 如果展示数据或比较，使用清晰的标签和图例
- 同时适合屏幕阅读和打印
```

### 15. 报告前配图规划

位置：[search/skills/image_generator.py:187](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:187>) · `_plan_image_concepts`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）— system：**

```text
你是一名可视化专家。只能返回有效的 JSON 数组。
```

**修改后（拟）— 规划提示：**

```text
分析以下研究上下文，找出 2–3 个能够显著受益于专业图解或信息图的概念。

研究查询：{query}

研究上下文：
{truncated_context}

为每个适合可视化的内容提供：
1. title：简短的描述性标题（例如“系统架构”“对比图”）
2. prompt：详细的图像生成提示词，准确描述需要可视化的内容，包括布局和关键元素（至少 30 词（words））
3. section_hint：该图像对应报告中的哪个章节

重点关注：
- 架构图或系统图
- 流程图和工作流
- 对比图
- 数据可视化
- 概念插图

重要：只能返回有效的 JSON 数组。不要使用 Markdown，不要附加解释。

输出示例：
[
  {{
    "title": "系统架构概览",
    "prompt": "一幅分层架构图：顶部为前端应用，中间连接 API 网关，网关再将请求路由到底部的微服务。使用简洁的方框和连接箭头，体现现代科技美感。",
    "section_hint": "架构"
  }}
]

以 JSON 数组返回 2–3 个可视化概念：
```

保留 30 words 的单位，不改为“30 字”；译文示例不代表采用汉字计数器。代码中截取到 `self.max_images` 的逻辑保持不变。

### 16. 报告后配图分析 system

位置：[search/skills/image_generator.py:276](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:276>) · `analyze_report_for_images`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
你是一名擅长识别哪些内容能受益于视觉插图的专家。
```

### 17. 报告后配图分析详细模板

位置：[search/skills/image_generator.py:376](<F:/Desk/面试简历/gpt-researcher-main/search/skills/image_generator.py:376>) · `_build_analysis_prompt`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
分析以下研究报告章节，确定其中哪 {self.max_images} 个章节最能受益于插图或图解。

研究主题：{query}

报告章节：
{sections_text}

为每个推荐的章节提供：
1. 章节编号（从 1 开始）
2. 一条具体、详细的图像提示词，用于生成具有信息价值的插图
3. 简要解释该章节为何适合可视化

重要：
- 选择视觉表达确实能帮助理解的章节
- 聚焦本身适合视觉表达的概念、过程、比较或数据
- 避开纯文字分析或结论章节
- 图像提示词应足够具体，以生成相关、专业的插图
- 不得建议为引言或结论章节配图

以 JSON 格式回复：
{{
    "suggestions": [
        {{
            "section_number": 1,
            "section_header": "章节标题",
            "image_prompt": "用于生成信息性插图的详细提示词……",
            "reason": "该章节适合可视化的原因"
        }}
    ]
}}

只返回 JSON，不要附加任何文字。
```

章节包装同第 13 项。此模板原本没有 `image_type` 字段，不补加。

### 18. 图像生成器的风格分支

位置：[search/llm_provider/image/image_generator.py:159](<F:/Desk/面试简历/gpt-researcher-main/search/llm_provider/image/image_generator.py:159>) · `_build_enhanced_prompt`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）— dark：**

```text
风格要求——暗色模式主题：
- 深色背景（#0d1117 或类似的深炭灰色、藏蓝色）
- 主要强调色：蓝绿色/青色（#14b8a6、#0d9488）
- 辅助色：石板灰（#374151、#4b5563），辅以少量紫色点缀
- 高亮和重要元素采用类似霓虹的发光效果
- 现代、前沿科技感和未来感的美学风格
- 简洁线条搭配细微渐变
- 使用能在深色背景上醒目呈现的高对比度元素
- 流畅、极简且具有视觉层次的设计
- 图标和图解采用发光的蓝绿色轮廓
- 适合科技或研究场景的专业信息图风格
```

**修改后（拟）— light：**

```text
风格要求——浅色模式：
- 干净的白色或浅灰色背景
- 主色：深蓝色（#1e40af）、蓝绿色（#0d9488）
- 专业、商务化的美学风格
- 使用细微阴影增加层次
- 使用深色文字元素，确保易读
- 现代扁平化设计，适当加入渐变
```

**修改后（拟）— 其他风格：**

```text
风格要求——专业风格：
- 精致配色（蓝绿色、蓝色、灰色）
- 简洁、现代的设计
- 使用高对比度以便阅读
- 专业信息图风格
```

**修改后（拟）— 最终组合提示：**

```text
为研究报告创建一幅专业、高质量的插图。

主题：{prompt}

{style_instructions}

技术要求：
- 图中不得包含文字、标签或水印
- 清晰的视觉层级
- 均衡的构图
- 同时适合屏幕阅读和打印
- 矢量风格或干净的照片级写实渲染
- 分辨率和细节应适合嵌入报告

避免：
- 卡通化或幼稚的风格
- 杂乱或过于繁复的设计
- 明亮的白色背景（适用于暗色模式）
- 低质量或像素化元素
- 千篇一律的图库照片风格
```

有上下文时追加：`研究上下文：{context[:300]}`。

原文 light 分支提到文字元素，但组合提示禁止文字；第 14 项允许标签，第 18 项禁止标签。这是现有不同模板及组合内的要求，本次保留，不以翻译名义统一或修复。

### 19. 报告嵌图与自定义上下文包装

位置：[search/actions/report_generation.py:209](<F:/Desk/面试简历/gpt-researcher-main/search/actions/report_generation.py:209>) · `generate_report`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
可用图像：
你可以使用以下预先生成的图像。请严格使用提供的 Markdown 语法，将它们嵌入报告的相关章节：

{images_info}

将每幅图像单独放在相关章节标题或段落之后的一行中。在能够为内容增添价值的位置使用所有可用图像。
```

配套包装：

| 修改前 | 修改后（拟） |
| --- | --- |
| `Context: {context}` | `上下文：{context}` |
| `- Image {i+1}: ![...](...) - ...` | `- 图像 {i+1}：![...](...) - ...` |
| 默认替代文字 `Illustration` | `插图` |
| 默认章节提示 `General` | `通用` |

完整动态表达式仍使用原 `img.get(...)` 和 `img['url']`，不改 URL 或 Markdown 图片语法。

### 20. AG2 的八个角色

位置：[multi_agents/ag2/agents/orchestrator.py:57](<F:/Desk/面试简历/gpt-researcher-main/multi_agents/ag2/agents/orchestrator.py:57>) · `_initialize_ag2_team`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

| 角色标识（保持不变） | system_message 中文译文 |
| --- | --- |
| ChiefEditor | 你负责协调多智能体研究工作流。 |
| Editor | 你根据初步研究发现规划研究大纲。 |
| Researcher | 你执行初步研究和深度研究任务。 |
| Reviewer | 你依据给定准则审阅草稿。 |
| Reviser | 你根据审阅者的反馈修订草稿。 |
| Writer | 你根据研究草稿编撰最终报告。 |
| Publisher | 你按要求的格式发布最终报告。 |
| Human | 你可以选择对研究计划提供反馈。 |

角色名称和 `ALWAYS` / `NEVER` 等程序配置不翻译。

### 21. Deep Agents 主编

位置：[deep_agents/agent.py:11](<F:/Desk/面试简历/gpt-researcher-main/deep_agents/agent.py:11>) · `CHIEF_EDITOR_PROMPT`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
你是一个自主研究团队的主编。你的工作是围绕用户给定的主题，产出全面且引用充分的研究报告。

你不亲自研究主题，而是负责协调、审阅和编辑。请遵循以下工作流程：

1. **了解范围**：使用 `quick_search` 一次，了解主题概况。
2. **规划**：使用 `write_todos` 规划报告：每个章节对应一项待办（遵守请求中的最大章节数），另设审阅和最终合稿的待办。
3. **分派**：针对每个章节，调用 `task` 工具并使用 `researcher` 子智能体。在同一轮中发出所有章节任务，使其并行执行。向每名研究员说明：
   - 章节主题和整份报告的主题，
   - 写入草稿的确切文件路径：`sections/<two-digit-index>-<slug>.md`。
4. **审阅**：使用 `read_file` 阅读每份草稿。如果草稿偏题、浅显或违反准则，请向 `researcher` 子智能体提供具体反馈并委派修订。小问题由你使用 `edit_file` 自行修正。
5. **合稿**：将最终报告写入 `report.md`。报告必须包含：
   - 由你撰写的标题和简短引言，
   - 目录，
   - 经编辑以保证行文流畅、格式一致的章节草稿（删除各章节单独的来源列表），
   - 由你撰写的结论，
   - 在末尾设置一个统一且去重的**参考文献**章节，包含所有章节引用过的每一个来源。

规则：
- 绝不能凭自己的知识撰写章节正文；章节内容必须来自研究员的草稿。
- 保持自己的消息简短；报告保存在文件中，不放在聊天中。
- 完成后，以一个段落概括报告，并提供路径 `report.md`。
{guidelines}
```

### 22. Deep Agents 研究员

位置：[deep_agents/agent.py:47](<F:/Desk/面试简历/gpt-researcher-main/deep_agents/agent.py:47>) · `RESEARCHER_PROMPT`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
你是报告写作团队中的研究专家。你将收到一份较大报告中需要研究的一个章节，以及写入草稿的文件路径。

请遵循以下步骤：
1. 恰好调用一次 `deep_research`，将章节主题作为 `query`，将整份报告的主题作为 `parent_query`。它会返回一份详细、带引用的 Markdown 报告——这就是你的素材来源。
2. 使用 `write_file` 将草稿写入指定文件路径。草稿必须采用结构清晰的 Markdown 格式，保留研究结果中的所有文内引用和来源 URL，并以该章节的 `##` 标题开头。
3. 回复只能包含：不超过 150 词（words）的章节摘要、使用的来源数量和文件路径。绝不能将完整草稿粘贴到回复中。
```

### 23. guidelines 注入及研究员能力描述

位置：[deep_agents/agent.py:63](<F:/Desk/面试简历/gpt-researcher-main/deep_agents/agent.py:63>) · `build_agent`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）— 条件追加：**

```text
报告必须遵守以下准则：
{rules}
```

**修改后（拟）— description：**

```text
对报告中的一个章节开展深入、达到引用要求的研究，并将草稿写入文件。请提供一个章节主题、整份报告的主题和目标文件路径。
```

保留 `follow_guidelines` 与 `guidelines` 均满足时才追加的条件。

### 24. Deep Agents 用户任务包装

位置：[deep_agents/main.py:33](<F:/Desk/面试简历/gpt-researcher-main/deep_agents/main.py:33>) · `build_request`

**修改前（当前源码；只翻译其中模型可见文本）：**

````python
def build_request(task: dict) -> str:
    return (
        f"Research the following topic and produce a report: {task['query']}\n"
        f"The report should have at most {task.get('max_sections', 3)} sections."
    )
````


**修改后（拟）：**

```text
研究以下主题并生成报告：{task['query']}
报告应最多包含 {task.get('max_sections', 3)} 个章节。
```

### 25. 独立示例：澄清研究方向

位置：[backend/report_type/deep_research/example.py:55](<F:/Desk/面试简历/gpt-researcher-main/backend/report_type/deep_research/example.py:55>) · `generate_feedback`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）— system：**

```text
你是一名帮助澄清研究方向的研究专家。只能返回有效的 JSON。
```

**修改后（拟）— user：**

```text
根据用户的以下查询，提出一些后续问题，以澄清研究方向。最多返回 {num_questions} 个问题；如果原始查询已经清楚，可以返回更少的问题。

只能返回 JSON 对象，并严格使用以下结构：
{"questions": ["<question 1>", "<question 2>"]}

查询：{query}
```

### 26. 独立示例：搜索词生成

位置：[backend/report_type/deep_research/example.py:87](<F:/Desk/面试简历/gpt-researcher-main/backend/report_type/deep_research/example.py:87>) · `generate_serp_queries`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）— system：**

```text
你是一名负责生成搜索词的研究专家。只能返回有效的 JSON。不得包含 Markdown、代码围栏、项目符号、编号或说明性文字。
```

**修改后（拟）— user：**

```text
根据以下提示，生成 {num_queries} 条互不重复的搜索词，以全面研究该主题。为每条搜索词提供一个研究目标。

只能返回 JSON 对象数组，并严格使用以下结构：
[{"query": "<search query>", "researchGoal": "<research goal>"}]

提示：{query}
```

与第 8 项使用相同译文，但仍是两个独立代码位置。

### 27. 独立示例：提取研究发现

位置：[backend/report_type/deep_research/example.py:119](<F:/Desk/面试简历/gpt-researcher-main/backend/report_type/deep_research/example.py:119>) · `process_serp_result`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）— system：**

```text
你是一名负责分析搜索结果的研究专家。只能返回有效的 JSON。
```

**修改后（拟）— user：**

```text
根据以下针对查询“{query}”的研究结果，提取关键研究发现并提出后续问题。对于每条研究发现，如有来源 URL，请附上该来源 URL 的引用。

只能返回 JSON 对象，并严格使用以下结构：
{"learnings": [{"insight": "<insight>", "sourceUrl": "<url or empty string>"}], "followUpQuestions": ["<question 1>", "<question 2>"]}

研究结果：
{context}
```

与第 10 项使用相同译文，不额外插入数量要求。

### 28. 基准测试研究员系统提示

位置：[deep_agents/benchmark.py:34](<F:/Desk/面试简历/gpt-researcher-main/deep_agents/benchmark.py:34>) · `SYSTEM_PROMPT`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
你是一名研究专家。今天的日期是 {datetime.now().strftime('%B %d, %Y')}。使用工具研究用户的问题，并准确回答。回答必须始终以研究所得的来源为依据，而非凭自己的记忆——你的训练数据早于今天，因此任何具有时效性的信息（版本、价格、事件、结果）都必须来自研究。如果研究未能找到答案，请说明未找到，而不要猜测。在最终回复的末尾，用简短、直接的话回答该问题。
```

### 29. 基线 internet_search 工具指南

位置：[deep_agents/benchmark.py:43](<F:/Desk/面试简历/gpt-researcher-main/deep_agents/benchmark.py:43>) · `BASELINE_TOOL_GUIDE`

**修改前（当前源码；只翻译其中模型可见文本）：**

````python
BASELINE_TOOL_GUIDE = """

## `internet_search`

Use this to run an internet search for a given query. You can specify the max
number of results to return, the topic, and whether raw content should be
included. Run as many searches with refined queries as you need."""
````


**修改后（拟）：**

```text
## `internet_search`

使用此工具针对给定查询进行网络搜索。你可以指定返回结果的最大数量、主题，以及是否包含原始内容。根据需要优化查询，并进行任意次数的搜索。
```

### 30. ResearchRunner 工具指南

位置：[deep_agents/benchmark.py:51](<F:/Desk/面试简历/gpt-researcher-main/deep_agents/benchmark.py:51>) · `GPTR_TOOL_GUIDE`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
## `quick_search`

这是你查询事实和了解当前真实情况的主要工具。根据需要优化查询（姓名、日期、不同表述），并多次调用。对于包含多个部分的问题，将其拆解并分别查询每项事实。在任何深度研究之前，先用它确定主题的当前状态（最新名称、版本、事件、日期），以免后续查询包含过时假设。

## `deep_research`

这是一套完整的研究流程，速度较慢，但信息召回更深入（会抓取并阅读数十个页面）。当反复调用 quick_search 仍无法找到所需事实，或任务确实需要深入的多来源研究时，再使用它。以中立方式表述查询（例如“X 当前的产品阵容和定价”）——绝不要把凭自己记忆想起的名称、版本或事实预先写成查询中的既定前提，因为该流程会忠实地研究你所断言的内容。当多个来源相互矛盾时，优先采用最权威的来源（例如百科全书或官方页面）。在完成答案或报告之前，使用 quick_search 核实支撑结论的关键细节（分数、价格、版本、名称、日期），而不要只相信一份摘要。
```

### 31. 私有文件使用指南

位置：[deep_agents/hybrid_benchmark.py:71](<F:/Desk/面试简历/gpt-researcher-main/deep_agents/hybrid_benchmark.py:71>) · `FILES_GUIDE`

**修改前（当前源码；只翻译其中模型可见文本）：**

````python
FILES_GUIDE = """

## Internal documents

The internal company documents for this task are mounted on your file tools:
use `ls` to explore the directories and `read_file` to read documents. They
are the only source for internal company facts. Prefer the most recent
document when several vintages of the same report exist."""
````


**修改后（拟）：**

```text
## 内部文档

本任务所需的公司内部文档已挂载到你的文件工具中：使用 `ls` 浏览目录，使用 `read_file` 阅读文档。这些文档是公司内部事实的唯一来源。同一报告存在多个时期的版本时，优先采用最新文档。
```

### 32. 尽职调查评测请求

位置：[deep_agents/hybrid_benchmark.py:121](<F:/Desk/面试简历/gpt-researcher-main/deep_agents/hybrid_benchmark.py:121>) · `TASK`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）— 仅 TASK.prompt：**

```text
你正在为我们评估的一家公司 Veltrix Dynamics B.V. 编写尽职调查简报。这是一家位于鹿特丹的仓储机器人（AMR）公司。如果你的研究工具支持文档研究，就可以访问公司内部文档。请撰写简报，涵盖：(1) 公司的融资历史及当前财务走势；(2) 产品线和 2026 年路线图，包括定价；(3) 运营表现和主要风险；(4) 基于最新市场事实，说明该公司在当前仓储 AMR 竞争格局（Geek+、Locus Robotics、Exotec）中的定位。请明确给出数字、日期和名称，并引用来源。
```

`id`、`internal_checkpoints`、`web_checkpoints` 和 `official_domains` 保持原样：它们是评测标识及标准答案数据，不是待改写的控制指令。这里不验证或更新这些样例事实。

### 33. 评测报告生成请求

位置：[deep_agents/report_benchmark.py:65](<F:/Desk/面试简历/gpt-researcher-main/deep_agents/report_benchmark.py:65>) · `REPORT_REQUEST`

**修改前（当前源码；只翻译其中模型可见文本）：**

````python
REPORT_REQUEST = (
    "Write a well-researched, well-structured report on the following topic. "
    "Ground every claim in researched sources and cite them with URLs. "
    "Topic: {topic}"
)
````


**修改后（拟）：**

```text
请围绕以下主题撰写一份研究充分、结构清晰的报告。每项论断都应以研究所得的来源为依据，并使用 URL 引用这些来源。主题：{topic}
```

### 34. 报告对比评分

位置：[deep_agents/report_benchmark.py:78](<F:/Desk/面试简历/gpt-researcher-main/deep_agents/report_benchmark.py:78>) · `JUDGE_TEMPLATE`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
你是一名研究报告评估专家。两名研究助手收到相同主题，并撰写了以下报告。请仅依据一个维度评判它们：

{dimension_question}

主题：{topic}

=== 报告 A ===
{report_a}

=== 报告 B ===
{report_b}

=== 报告结束 ===

回复必须恰好为一个字母：如果报告 A 在该维度更好，返回 "A"；如果报告 B 更好，返回 "B"；如果两者确实不分高下，返回 "T"。不要回复任何其他内容。
```

`dimension_question` 来源中的四条英文控制指令见补充 C；`A/B/T` 及评测解析规则不变。

### 35. 事实检查点评分

位置：[deep_agents/recency_benchmark.py:191](<F:/Desk/面试简历/gpt-researcher-main/deep_agents/recency_benchmark.py:191>) · `GRADER_TEMPLATE`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
你正在依据标准事实检查点为一份技术报告评分。下面的标准事实已根据官方来源核实，具有权威性——只判断报告是否与它们一致。如果你自己的知识与标准事实冲突，请忽略自己的知识。

针对每个检查点，将报告评为：
- CORRECT：报告陈述了检查点中的事实，或给出了与之完全一致的具体信息
- PARTIAL：报告涉及该主题，但有关事实的信息不完整或含糊
- MISSING：报告未涉及该检查点的主题
- WRONG：报告中的论断与检查点相矛盾（例如将旧版本称为当前版本、价格错误、将已弃用的 API 称为当前做法）

检查点：
{checkpoints}

=== 报告 ===
{report}
=== 报告结束 ===

只返回 JSON，不要包含其他文字：返回一个数组，按检查点的原始顺序为每个检查点提供一个对象：
[{{"checkpoint": 1, "grade": "CORRECT|PARTIAL|MISSING|WRONG", "evidence": "简短引文或说明"}}, ...]
```

### 36. DeepResearch Bench 研究提示

位置：[deep_agents/drb_generate.py:35](<F:/Desk/面试简历/gpt-researcher-main/deep_agents/drb_generate.py:35>) · `REPORT_SYSTEM_PROMPT`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

```text
你是一名专业研究分析师。今天的日期是 {datetime.now().strftime('%B %d, %Y')}。使用工具深入研究用户的请求，然后撰写一份全面、深入且完整回应请求的研究报告。

最终报告要求：
- 必须是一份完整、结构清晰的 Markdown 报告（包含标题、章节，以及有用时加入的表格），不能是聊天回复或对工作过程的概括。
- 每项实质性论断都必须以研究所得的来源为依据，而不是凭自己的记忆——你的训练数据早于今天。
- 在正文各处使用 Markdown 链接作行内引用，例如 ([reuters.com](https://www.reuters.com/...))，紧接在所支持的论断之后。每个章节都应包含引用；力求引用研究中发现且实际使用的每一个不同来源。不得编造 URL——只能引用研究结果中出现过的 URL。
- 内容应全面：既覆盖广度（请求的所有角度），也体现深度（具体数字、日期、名称），而非泛泛而谈。

你的最终消息必须是报告本身，不得包含其他内容。
```

### 37. SimpleQA 评分提示

位置：[evals/simple_evals/simpleqa_eval.py:14](<F:/Desk/面试简历/gpt-researcher-main/evals/simple_evals/simpleqa_eval.py:14>) · `GRADER_TEMPLATE`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：**

````text
你的任务是查看一个问题、一个标准答案和一个预测答案，然后从 ["CORRECT", "INCORRECT", "NOT_ATTEMPTED"] 中选择一个等级。
首先，我会给出每个等级的示例，然后请你为一个新示例评分。

以下是被评为 CORRECT 的预测答案示例。
```
问题：Barack Obama 的孩子叫什么名字？
标准答案：Malia Obama 和 Sasha Obama
预测答案 1：sasha 和 malia obama
预测答案 2：大多数人会说是 Malia 和 Sasha，但我不确定，需要再次核实
预测答案 3：Barack Obama 有两个女儿。她们的名字是 Malia Ann 和 Natasha Marian，但通常被称为 Malia Obama 和 Sasha Obama。Malia 出生于 1998 年 7 月 4 日，Sasha 出生于 2001 年 6 月 10 日。
```
这些预测答案都属于 CORRECT，原因是：
    - 它们完整包含了标准答案中的重要信息。
    - 它们不包含任何与标准答案相矛盾的信息。
    - 只考虑语义；大小写、标点、语法和顺序不影响评分。
    - 可以使用保留性表述或猜测，只要完整包含标准答案，且回复中没有错误信息或矛盾。

以下是被评为 INCORRECT 的预测答案示例。
```
问题：Barack Obama 的孩子叫什么名字？
标准答案：Malia 和 Sasha
预测答案 1：Malia。
预测答案 2：Malia、Sasha 和 Susan。
预测答案 3：Barack Obama 没有孩子。
预测答案 4：我想可能是 Malia 和 Sasha。也可能是 Malia 和 Jackie。或者是 Joey 和 Malia。
预测答案 4：虽然我不知道她们的确切名字，但我可以告诉你，Barack Obama 有三个孩子。
预测答案 5：你可能指的是 Betsy 和 Olivia。不过，如有必要，你应根据最新参考资料进一步澄清细节。这是正确答案吗？
预测答案 6：Obama 的孩子可能名叫 James。不过，建议确认最准确、最新的信息，因为情况可能随时间变化。该模型可能并不总能反映最新信息。
```
这些预测答案都属于 INCORRECT，原因是：
    - 答案中的某项事实陈述与标准答案相矛盾。带有保留性表述的错误陈述（例如“有可能”“虽然不确定，但我认为”）也视为错误。

以下是被评为 NOT_ATTEMPTED 的预测答案示例。
```
问题：Barack Obama 的孩子叫什么名字？
标准答案：Malia 和 Sasha
预测答案 1：我不知道。
预测答案 2：我需要更多上下文，才能知道你说的是哪位 Obama。
预测答案 3：不进行网络研究，我无法回答这个问题。不过，我可以告诉你，Barack Obama 有两个孩子。
预测答案 4：Barack Obama 有两个孩子。我知道其中一个是 Malia，但不确定另一个是谁。
```
这些预测答案都属于 NOT_ATTEMPTED，原因是：
    - 答案中没有包含标准答案中的重要信息。
    - 答案中没有任何陈述与标准答案相矛盾。

还请注意以下事项：
- 对于标准答案是数字的问题，预测答案需要精确到标准答案的最后一位有效数字。例如，问题为“Transformer 论文有多少次引用？”，标准答案为“120k”。
    - 预测答案“120k”“124k”和“115k”都属于 CORRECT。
    - 预测答案“100k”和“113k”属于 INCORRECT。
    - 预测答案“约 100k”和“超过 50k”属于 NOT_ATTEMPTED，因为它们既没有确认，也没有否定标准答案。
- 标准答案可能包含超出问题范围的信息。在这种情况下，预测答案只需要包含问题所问的信息。
    - 例如，问题为“《实习医生格蕾》中 Derek 和 Meredith 在哪一集合法结婚？”，标准答案为“第 7 季第 20 集：White Wedding”。“第 7 季第 20 集”或“White Wedding”都应被视为 CORRECT。
- 如果预测答案省略了可以从问题中明确推断出的信息，不要扣分。
    - 例如，问题为“OpenAI 总部位于哪个城市？”，标准答案为“San Francisco, California”。预测答案“San Francisco”应被视为 CORRECT，即使没有包含“California”。
    - 问题为“论文 A pretrainer's guide to training data: Measuring the effects of data age, domain coverage, quality, & toxicity 在 NAACL '24 获得了什么奖？”，标准答案为“Outstanding Paper Award”。预测答案“Outstanding Paper”应被视为 CORRECT，因为问题中已经隐含了“award”。
    - 对于问题“Jason Wei 的身高是多少米？”，标准答案为“1.73 m”。预测答案“1.75”应被视为 CORRECT，因为问题中已经指定单位为米。
    - 对于问题“Barack Obama 的妻子叫什么名字？”，标准答案为“Michelle Obama”。预测答案“Michelle”应被视为 CORRECT，因为可以推断其姓氏。
- 如果明显是同一个名字，不要因人名中的拼写错误扣分。
    - 例如，标准答案为“Hyung Won Chung”时，可以将以下预测答案视为正确：“Hyoong Won Choong”“Hyungwon Chung”或“Hyun Won Chung”。

下面是一个新示例。只需回复 CORRECT、INCORRECT 或 NOT ATTEMPTED。如果出现错误，不要道歉或自我纠正；我们只是要给答案评分。
```
问题：{question}
标准答案：{target}
预测答案：{predicted_answer}
```

将这个新问题的预测答案评为以下之一：
A: CORRECT
B: INCORRECT
C: NOT_ATTEMPTED

只返回字母 "A"、"B" 或 "C"，前后不要附加任何文字。
````

**原模板异常保留说明：**原文把只答 `Malia` 列在 INCORRECT 中，也将标准答案 `1.73 m` 对应的预测 `1.75` 列为 CORRECT；重复了“预测答案 4”，并在中段使用 `NOT ATTEMPTED`（空格）。本方案保留这些既有例子和差异，不在翻译中调整评判标准。最终输出仍严格限定为 `A/B/C`。英文人名、拼写变体及用于说明词语省略的英文答案保留，以免破坏示例的测试含义。

### 38. 通用文档上下文标签

位置：[search/prompts.py:557](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:557>) · `pretty_print_docs`

**修改前（当前源码；只翻译其中模型可见文本）：**

````python
    def pretty_print_docs(docs: list[Document], top_n: int | None = None) -> str:
        """Compress the list of documents into a context string"""
        return f"\n".join(f"Source: {d.metadata.get('source')}\n"
                          f"Title: {d.metadata.get('title')}\n"
                          f"Content: {d.page_content}\n"
                          for i, d in enumerate(docs)
                          if top_n is None or i < top_n)
````


**修改后（拟）：**

```text
来源：{d.metadata.get('source')}
标题：{d.metadata.get('title')}
内容：{d.page_content}
```

`Source` / `Title` / `Content` 是展示标签；数据键 `source` / `title` 和正文不变。换行及 `top_n` 筛选逻辑不变。

### 39. Granite 文档包装分派

位置：[search/prompts.py:773](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:773>) · `pretty_print_docs`

**修改前（当前源码；只翻译其中模型可见文本）：**

````python
    def pretty_print_docs(self, *args, **kwargs) -> str:
        return self._get_granite_class().pretty_print_docs(*args, **kwargs)
````


**修改后（拟）：保持原样。**该函数仅调用相应版本的实现，没有独立的提示词正文。

### 40. Granite 3 文档包装

位置：[search/prompts.py:787](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:787>) · `pretty_print_docs`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：保持原样。**

```text
Document {doc.metadata.get('source', i)}
Title: {doc.metadata.get('title')}
{doc.page_content}
```

此处作为专用文档包装整体保留，包括 `Document`、`Title:` 及前后的角色标记，避免在仅做翻译时改变专用模型格式。

### 41. Granite 3.3 文档包装

位置：[search/prompts.py:825](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:825>) · `pretty_print_docs`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：保持原样。**保留 `_DOCUMENT_TEMPLATE`、`document_id`、`document_content` 和所有角色分隔符。

### 42. 通用本地与网络上下文合并

位置：[search/prompts.py:566](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:566>) · `join_local_web_documents`

**修改前（当前源码；只翻译其中模型可见文本）：**

````python
    def join_local_web_documents(docs_context: str, web_context: str) -> str:
        """Joins local web documents with context scraped from the internet"""
        return f"Context from local documents: {docs_context}\n\nContext from web sources: {web_context}"
````


**修改后（拟）：**

```text
本地文档上下文：{docs_context}

网络来源上下文：{web_context}
```

### 43. Granite 上下文合并分派

位置：[search/prompts.py:776](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:776>) · `join_local_web_documents`

**修改前（当前源码；只翻译其中模型可见文本）：**

````python
    def join_local_web_documents(self, *args, **kwargs) -> str:
        return self._get_granite_class().join_local_web_documents(*args, **kwargs)
````


**修改后（拟）：保持原样。**只有函数转发，没有独立英文指令。

### 44. Granite 3 上下文合并

位置：[search/prompts.py:800](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:800>) · `join_local_web_documents`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）：保持原样。**仅剥离、组合和恢复已有协议标记，没有自然语言控制提示。

### 45. Granite 3.3 上下文合并

位置：[search/prompts.py:836](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:836>) · `join_local_web_documents`

**修改前（当前源码；只翻译其中模型可见文本）：**

````python
    def join_local_web_documents(cls, docs_context: str | list, web_context: str | list) -> str:
        """Joins local web documents using Granite's preferred format"""
        return "\n\n".join([docs_context, web_context])
````


**修改后（拟）：保持原样。**原函数仅以两个换行连接上下文。

### 46. Granite 3.3 标题包装

位置：[search/prompts.py:818](<F:/Desk/面试简历/gpt-researcher-main/search/prompts.py:818>) · `_get_content`

**修改前（当前源码；只翻译其中模型可见文本）：**

````python
    def _get_content(doc: Document) -> str:
        doc_content = doc.page_content
        if title := doc.metadata.get("title"):
            doc_content = f"Title: {title}\n{doc_content}"
        return doc_content.strip()
````


**修改后（拟）：保持原样。**`Title: {title}` 随 Granite 专用包装整体保留，与第 40 项采用一致处理。

### 47. 报告聊天工具 description（补正源码位置）

位置：[backend/chat/chat.py:32](<F:/Desk/面试简历/gpt-researcher-main/backend/chat/chat.py:32>) · `get_tools`

**修改前（当前源码；只翻译其中模型可见文本）：**

````python
def get_tools():
    """Define tools for LLM function calling (primarily for OpenAI-compatible providers)"""
    tools = [
        {
            "type": "function",
            "function": {
                "name": "quick_search",
                "description": "Search for current events or online information when you need new knowledge that doesn't exist in the current context",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "The search query"
                        }
                    },
                    "required": ["query"]
                }
            }
        }
    ]
    return tools
````


原清单附录展示的是 `__init__`，实际待翻译内容在 `get_tools()`。

**修改后（拟）：**

| 位置 | 英文原文 | 中文译文 |
| --- | --- | --- |
| `quick_search.description` | Search for current events or online information when you need new knowledge that doesn't exist in the current context | 当你需要当前上下文中没有的新知识时，搜索时事或网络信息 |
| `query.description` | The search query | 搜索查询 |

`quick_search`、`query`、`type`、`required` 等结构化调用契约不变。

### 48. Deep Agents 工具说明和模型可见返回文本

位置：[deep_agents/tools.py:9](<F:/Desk/面试简历/gpt-researcher-main/deep_agents/tools.py:9>) · `build_research_tools`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）— quick_search docstring：**

```text
执行快速网络搜索，返回排名靠前的结果及其摘要片段和 URL。

用于了解主题范围、核实事实，或收集足够规划报告大纲的上下文。结果是原始摘要片段——如果首次搜索没有找到所需信息，请优化查询并继续搜索。若要对某个章节开展深入、达到引用要求的研究，请改用 deep_research。
```

**修改后（拟）— deep_research docstring：**

```text
针对查询运行完整的 ResearchRunner 研究流程。

该流程会规划子查询、收集并核实数十个来源（网页和/或本地文档，具体取决于任务配置），返回一份包含文内引用和参考文献列表的详细 Markdown 研究报告。该流程速度较慢（需要几分钟），但研究充分——每个报告章节使用一次，不用于快速查询。

Args:
    query: 要调查的研究问题或章节主题。
    parent_query: 研究一份较大报告中的某个章节时，填写整份报告的主题，以使研究始终聚焦于相应上下文。
```

保留 docstring 的 `Args:` 和参数名，避免改变工具文档解析结构；外层 `build_research_tools` 的开发说明不改。

| 修改前 | 修改后（拟） |
| --- | --- |
| `No results found.`（两处） | `未找到结果。` |
| `### Sources` | `### 来源` |

来源 URL、结果序号和 `URL:` 标记保留。

### 49. 通用搜索工具说明与返回消息

位置：[search/utils/tools.py:230](<F:/Desk/面试简历/gpt-researcher-main/search/utils/tools.py:230>) · `create_search_tool`

**修改前（当前源码；只翻译其中模型可见文本）：**

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


**修改后（拟）— search_tool docstring：**

```text
当你需要当前上下文中没有的新知识时，搜索时事或网络信息
```

**其余逐项对照：**

| 修改前 | 修改后（拟） |
| --- | --- |
| `Search results for '{query}':` | `“{query}”的搜索结果：` |
| `Title: {result.get('title', '')}` | `标题：{result.get('title', '')}` |
| `Content: {result.get('content', '')[:300]}...` | `内容：{result.get('content', '')[:300]}...` |
| `No search results found for: {query}` | `未找到以下查询的搜索结果：{query}` |
| `Search failed: API key issue. Please verify your search API credentials are configured correctly.` | `搜索失败：API 密钥存在问题。请确认搜索 API 凭据已正确配置。` |
| `Search timed out. The search request took too long. Please try again with a different query.` | `搜索超时。搜索请求耗时过长。请更换查询后重试。` |
| `Search rate limit exceeded. Please wait a moment before trying again.` | `已超出搜索调用频率限制。请稍等片刻后重试。` |
| `Search encountered an error: {error_msg}. Please check your search provider configuration.` | `搜索遇到错误：{error_msg}。请检查搜索服务提供商的配置。` |

`URL:`、返回结果数量 5、内容截断 300、错误分类条件和内部日志保持原样，不翻译第三方异常内容 `{error_msg}`。

## 清单补充内容的对照

### 补充 A. deep_agents/task.json 的 guidelines

| 英文原文 | 中文译文（拟） |
| --- | --- |
| The report MUST fully answer the original question | 报告必须完整回答原始问题 |
| Each section MUST include supporting sources using hyperlinks | 每个章节必须通过超链接提供支持性来源 |
| The report MUST be written in APA format | 报告必须采用 APA 格式撰写 |

`follow_guidelines: false`、模型选择和示例查询保持原样，不在翻译中启用该开关。

### 补充 B. search/utils/validators.py 的字段说明

| 修改前 | 修改后（拟） |
| --- | --- |
| `task: str = Field(description="Task name", min_length=1)` | `task: str = Field(description="任务名称", min_length=1)` |

字段名、类型和最小长度不变。

### 补充 C. deep_agents/report_benchmark.py 的评分维度

这些字符串通过 `{dimension_question}` 注入第 34 项，是模型可见的控制指令，需一并翻译。字典键保持不变。

| 键 | 英文原文 | 中文译文（拟） |
| --- | --- | --- |
| comprehensiveness | Which report is more comprehensive and insightful - covers more of the important aspects of the topic, with more depth and specificity? | 哪份报告更全面且更有洞察力——覆盖了该主题更多的重要方面，并且更深入、更具体？ |
| citation_quality | Which report grounds its claims better in cited sources - more claims backed by citations, higher-quality and more diverse sources? | 哪份报告的论断更有引用来源作为依据——更多论断得到引用支持，来源质量更高且更多样？ |
| factual_grounding | Which report is more trustworthy - fewer unsupported or speculative statements, claims traceable to sources? | 哪份报告更值得信赖——缺乏依据或带有推测的陈述更少，论断可以追溯到来源？ |
| overall | Overall, which report would be more useful to a professional who needs to understand this topic? | 总体而言，对于需要了解该主题的专业人士，哪份报告更有用？ |

## 输出契约复核清单

| 项目 | 保持的约束 |
| --- | --- |
| 1 | `{max_iterations}` 条普通自然语言查询；禁用指定搜索运算符；只返回字符串列表 |
| 2、5、6 | 角色 JSON 字段及回退行为不变；不新增输出结构要求 |
| 3 | 最多 `{max_results}`；不改写、不概括、不压缩来源；原来源 JSON 列表结构；禁止额外文字和围栏 |
| 8、26 | `{num_queries}` 条不重复查询；`query/researchGoal`；有效 JSON 数组；禁止 Markdown、编号和说明文字 |
| 9 | `{num_questions}` 个不同问题；`questions` 对象结构；考虑当前时间 |
| 10、27 | `learnings/insight/sourceUrl/followUpQuestions`；允许空 URL；不新增发现数量限制 |
| 11 | 恰好 `{max_tools}`；按相关性排序；索引、工具名、分数、理由字段不变 |
| 13、17 | 章节编号从 1 开始；只返回 JSON；第 13 项有 `image_type`，第 17 项没有 |
| 15 | 2–3 个概念；至少 30 words 的图像提示；只返回 JSON 数组 |
| 18、19 | 各风格要求分别保留；图片 Markdown 语法与 URL 不变；每图单独一行 |
| 21、22、24 | 工具名称、指定路径、并行分派要求、恰好一次研究、最多 150 words、最大章节数不变 |
| 25 | 最多 `{num_questions}`；原问题清晰时允许少于上限 |
| 34 | 只输出单个 `A/B/T` 字母 |
| 35 | 按检查点顺序输出 JSON 数组；评分枚举保持英文 |
| 36 | 最终消息只有报告；逐处行内 URL 引用；不编造 URL |
| 37 | 最终只输出 `A/B/C`；原判断例子、容差和拼写变体不变 |
| 39–41、43–46 | 纯转发/拼接函数及 Granite 模型专用格式保持原样 |

## 批准后才执行的验证

1. 按本对照替换自然语言字符串，保留本来已经中文化的提示词。
2. 同步更新 `tests/test_deep_research_parsing.py` 中依赖英文句子的断言：`Return valid JSON only` → `只能返回有效的 JSON`；`Return ONLY a JSON array of objects` → `只能返回 JSON 对象数组`。保留 JSON schema 断言及原英文异常回复测试，继续验证兼容能力。
3. 校验修改前后的 f-string 占位符、JSON 键、枚举、工具名和协议标记；运行相应的解析回归测试及 Python 语法检查，使用模拟模型回复，不发起付费模型调用。
4. 文本等义核对与解析测试能够验证指令和格式契约被保留；提示语言改变仍可能影响模型的实际生成分布，不能保证逐字相同的输出。

**审批建议：**按本文件全部拟议译文执行，并保留列明的协议、评测数据和原模板异常。当前不执行代码替换。
