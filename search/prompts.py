import warnings
from datetime import date, datetime, timezone

from langchain_core.documents import Document

from .config import Config
from .utils.enum import ReportSource, ReportType, Tone
from .utils.enum import PromptFamily as PromptFamilyEnum
from typing import Callable, List, Dict, Any


## Prompt Families #############################################################

class PromptFamily:
    """General purpose class for prompt formatting.

    This may be overwritten with a derived class that is model specific. The
    methods are broken down into two groups:

    1. Prompt Generators: These follow a standard format and are correlated with
        the ReportType enum. They should be accessed via
        get_prompt_by_report_type

    2. Prompt Methods: These are situation-specific methods that do not have a
        standard signature and are accessed directly in the agent code.

    All derived classes must retain the same set of method names, but may
    override individual methods.
    """

    def __init__(self, config: Config):
        """Initialize with a config instance. This may be used by derived
        classes to select the correct prompting based on configured models and/
        or providers
        """
        self.cfg = config

    # MCP-specific prompts
    @staticmethod
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
        
        return f"""你是一名研究助手，帮助为研究查询选择最相关的工具。

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
"""

    @staticmethod
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
        
        return f"""你是一名可以使用专业工具的研究助手。你的任务是研究以下查询，并提供全面、准确的信息。

研究查询：“{query}”

指令：
1. 使用可用工具收集与查询相关的信息
2. 如有需要，调用多个工具，以实现全面覆盖
3. 如果工具调用失败或返回空结果，请尝试其他方法
4. 在可能的情况下，综合多个来源的信息
5. 聚焦能够直接回答查询的、基于事实且相关的信息

可用工具：{tool_names}

请开展深入研究并提供研究发现。有策略地使用工具，以收集最相关、最全面的信息。"""

    # Image generation prompts
    @staticmethod
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
            f"### 第 {i+1} 节：{s['header']}\n{s['content'][:500]}..."
            for i, s in enumerate(sections)
        ])
        
        return f"""分析以下研究报告章节，确定其中哪 {max_images} 个章节最能受益于插图或图解。

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

只返回 JSON，不要附加任何文字。"""

    @staticmethod
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
        return f"""为研究报告创建一幅专业且具有信息价值的插图。

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
- 同时适合屏幕阅读和打印"""

    @staticmethod
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
            report_type == ReportType.SubtopicReport.value
        ):
            task = f"{parent_query} - {question}"
        else:
            task = question

        context_prompt = f"""
你是一名资深研究助手，负责为以下任务生成搜索词，以查找相关信息：“{task}”。
上下文：{context}

请利用这些上下文来指导并优化搜索词。上下文提供的实时网络信息可以帮助你生成更具体、更相关的查询。请考虑其中提到的时事、近期进展或具体细节，以改进搜索词。
""" if context else ""

        dynamic_example = ", ".join([f'"查询 {i+1}"' for i in range(max_iterations)])

        return f"""请生成 {max_iterations} 条搜索词，用于研究以下任务：“{task}”

每条搜索词必须是普通的自然语言短语。不要使用搜索运算符语法，例如 site:、filetype:、inurl:、intitle:、OR、AND 或 NOT——这些运算符并非得到普遍支持，在许多搜索后端上会返回空结果。

如有需要，请假定当前日期为 {datetime.now(timezone.utc).strftime('%B %d, %Y')}。

{context_prompt}
你必须按照以下格式返回字符串列表：[{dynamic_example}]。
回复只能包含该列表。
"""

    @staticmethod
    def generate_report_prompt(
        question: str,
        context,
        report_source: str,
        report_format="apa",
        total_words=1000,
        tone=None,
        language="Chinese (Simplified)",
    ):
        """Generates the report prompt for the given question and research summary.
        Args: question (str): The question to generate the report prompt for
                research_summary (str): The research summary to generate the report prompt for
        Returns: str: The report prompt for the given question and research summary
        """

        reference_prompt = ""
        if report_source == ReportSource.Web.value:
            reference_prompt = f"""
你必须在报告末尾以参考文献形式列出所有使用过的来源 URL，确保不添加重复来源，每个来源只列一次。
每个 URL 都应使用超链接格式：[网站链接](url)
此外，在报告正文引用相关来源时，也必须加入对应 URL 的超链接：

例如：作者。（年，月日）。网页标题。网站名称。[网站链接](url)
"""
        else:
            reference_prompt = f"""
你必须在报告末尾以参考文献形式列出所有使用过的来源文档名称，确保不添加重复来源，每个来源只列一次。
"""

        tone_prompt = f"请采用{tone.value}的语气撰写报告。" if tone else ""

        return f"""
资料："{context}"
---
请使用上述资料，以详细报告的形式回答以下问题或任务："{question}"。
报告应围绕问题的答案展开，结构清晰、信息充分、深入且全面；如资料中包含事实和数字，应加以使用；篇幅至少为 {total_words} 个词。
请尽量充分利用所提供的所有相关且必要的信息，撰写尽可能完整的报告。

报告必须遵循以下全部要求：
- 你必须依据所给资料形成具体且有依据的观点，不要以笼统、空泛的结论敷衍。
- 你必须使用 Markdown 语法，并遵循 {report_format} 格式撰写报告。
- 使用清晰的 Markdown 标题组织报告：主标题使用 #，主要章节使用 ##，子章节使用 ###。
- 展示结构化数据或比较内容时，使用 Markdown 表格提高可读性。
- 你必须优先考虑来源的相关性、可靠性和重要性，优先选择可信来源。
- 在来源可信的前提下，应优先采用较新的文章，而不是较旧的文章。
- 你不得包含目录，但必须使用正确的 Markdown 标题（#、##、###）清晰组织报告。
- 使用 {report_format} 格式的文内引用，并将 Markdown 超链接放在引用该来源的句子或段落末尾，格式如下：([文内引用](url))。
- 每一项实质性论断、数据或引文都必须附带其来源的文内引用。不得引用所提供资料中没有出现的来源。
- 不要忘记在报告末尾按照 {report_format} 格式添加参考文献列表。
- {reference_prompt}
- {tone_prompt}
你必须使用以下语言撰写整份报告：{language}。当该语言为 Chinese (Simplified) 时，标题和正文均须使用简体中文；专有名词、URL 和原文引用可保留原语言。
假定当前日期为 {date.today()}。
"""

    @staticmethod
    def curate_sources(query, sources, max_results=10):
        return f"""你的目标是为研究任务“{query}”评估并筛选提供的抓取内容，优先纳入相关且优质的信息，尤其是包含统计资料、数字或具体数据的来源。

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
"""





    @staticmethod
    def auto_agent_instructions():
        return """
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
"""

    @staticmethod
    def generate_summary_prompt(query, data):
        """Generates the summary prompt for the given question and text.
        Args: question (str): The question to generate the summary prompt for
                text (str): The text to generate the summary prompt for
        Returns: str: The summary prompt for the given question and text
        """

        return (
            f"""{data}
请根据以下任务或查询概括上述文本：“{query}”。
如果无法使用该文本回答查询，你必须简要概括该文本。
如有数字、统计资料、引文等事实信息，请全部纳入。 """
        )

    @staticmethod
    def generate_quick_summary_prompt(query: str, context: str) -> str:
        """Generates the quick summary prompt for the given question and context.
        Args:
            query (str): The query to generate the summary for
            context (str): The search results to summarize
        Returns:
            str: The quick summary prompt
        """
        return f"""
仅根据所提供的搜索结果，综合回答以下问题。
问题："{query}"

搜索结果：
{context}

要求：
1. 使用简体中文给出一段连贯、完整的回答；专有名词、URL 和原文引用可保留原语言。
2. 使用 [1]、[2] 等编号引用来源，编号应与搜索结果对应。
3. 如果搜索结果不足以回答问题，请明确说明。
4. 重点保证准确性和相关性。
"""

    @staticmethod
    def pretty_print_docs(docs: list[Document], top_n: int | None = None) -> str:
        """Compress the list of documents into a context string"""
        return f"\n".join(f"""来源：{d.metadata.get('source')}
标题：{d.metadata.get('title')}
内容：{d.page_content}
"""
                          for i, d in enumerate(docs)
                          if top_n is None or i < top_n)

    @staticmethod
    def join_local_web_documents(docs_context: str, web_context: str) -> str:
        """Joins local web documents with context scraped from the internet"""
        return f"本地文档上下文：{docs_context}\n\n网络来源上下文：{web_context}"

    ################################################################################################

    # DETAILED REPORT PROMPTS

    @staticmethod
    def generate_subtopics_prompt() -> str:
        return """
给定以下主要主题：

{task}

以及研究资料：

{data}

- 构建一份子主题列表，这些子主题将作为该任务详细报告的章节标题。
- 可参考以下候选子主题：{subtopics}。
- 不得出现重复的子主题。
- 子主题数量最多为 {max_subtopics} 个。
- 最后根据各子主题的任务，以相关、有意义且适合详细报告呈现的顺序排列。
- 子主题名称和任务描述必须使用简体中文；专有名词可保留原语言。

“重要”：
- 每个子主题都必须仅与主要主题和所提供的研究资料相关。

{format_instructions}
"""

    @staticmethod
    def generate_subtopic_report_prompt(
        current_subtopic,
        existing_headers: list,
        relevant_written_contents: list,
        main_topic: str,
        context,
        report_format: str = "apa",
        max_subsections=5,
        total_words=800,
        tone: Tone = Tone.Objective,
        language: str = "Chinese (Simplified)",
    ) -> str:
        return f"""
上下文：
"{context}"

主要主题和子主题：
请使用最新可用信息，围绕主要主题“{main_topic}”下的子主题“{current_subtopic}”撰写详细报告。
子章节数量最多为 {max_subsections} 个。

内容重点：
- 报告应聚焦回答问题，结构清晰、信息充分、分析深入，并在资料允许时包含事实和数字。
- 使用 Markdown 语法并遵循 {report_format.upper()} 格式。
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

    {existing_headers}

- 之前子主题报告中已有的正文内容：

    {relevant_written_contents}

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
如有需要，假定当前日期为 {datetime.now(timezone.utc).strftime('%B %d, %Y')}。

“重要”：
- 你必须使用以下语言撰写报告：{language}。当该语言为 Chinese (Simplified) 时，标题和正文均须使用简体中文。
- 必须聚焦主要主题，排除任何无关信息。
- 不得包含引言、结论、摘要或参考文献章节。
- 必须使用 {report_format.upper()} 格式的文内引用，并将 Markdown 超链接放在引用该来源的句子或段落末尾，格式如下：([文内引用](url))。
- 如果添加相似或相同的子章节，必须在必要位置说明已有内容与新内容之间的区别。
- 报告篇幅至少为 {total_words} 个词。
- 全文采用{tone.value}的语气。

不要添加结论章节。
"""

    @staticmethod
    def generate_draft_titles_prompt(
        current_subtopic: str,
        main_topic: str,
        context: str,
        max_subsections: int = 5
    ) -> str:
        return f"""
“上下文”：
"{context}"

“主要主题和子主题”：
请使用最新可用信息，为主要主题“{main_topic}”下的子主题“{current_subtopic}”拟定详细报告的章节标题草案。

“任务”：
1. 为子主题报告创建一份章节标题草案列表。
2. 每个标题都应简洁且与子主题相关。
3. 标题不应过于宽泛，应足够具体以覆盖子主题的主要方面。
4. 标题使用 Markdown 语法和 H3（###），因为 H1 和 H2 将用于更大范围的报告标题。
5. 确保这些标题覆盖子主题的主要方面。
6. 所有标题必须使用简体中文；专有名词可保留原语言。

“结构和格式”：
使用 Markdown 语法以列表形式提供标题草案，例如：

### 标题 1
### 标题 2
### 标题 3

“重要”：
- 必须聚焦主要主题，排除任何无关信息。
- 不得包含引言、结论、摘要或参考文献章节。
- 只创建标题，不要撰写正文。
"""

    @staticmethod
    def generate_report_introduction(question: str, research_summary: str = "", language: str = "Chinese (Simplified)", report_format: str = "apa") -> str:
        return f"""{research_summary}\n
请使用上述最新资料，围绕主题“{question}”撰写一篇详细的报告引言。
- 引言应简洁、结构清晰、信息充分，并使用 Markdown 语法。
- 本引言将成为更大报告的一部分，因此不要包含报告中通常出现的其他章节。
- 引言前必须有一个适合作为整份报告标题的 H1 标题。
- 必须使用 {report_format.upper()} 格式的文内引用，并将 Markdown 超链接放在引用该来源的句子或段落末尾，格式如下：([文内引用](url))。
如有需要，假定当前日期为 {datetime.now(timezone.utc).strftime('%B %d, %Y')}。
- 输出必须使用 {language}；当该语言为 Chinese (Simplified) 时，标题和正文均须使用简体中文。
"""


    @staticmethod
    def generate_report_conclusion(query: str, report_content: str, language: str = "Chinese (Simplified)", report_format: str = "apa") -> str:
        """
        Generate a concise conclusion summarizing the main findings and implications of a research report.

        Args:
            query (str): The research task or question.
            report_content (str): The content of the research report.
            language (str): The language in which the conclusion should be written.

        Returns:
            str: A concise conclusion summarizing the report's main findings and implications.
        """
        prompt = f"""
    请根据以下研究报告和研究任务，撰写一篇简洁的结论，总结主要发现及其影响：

    研究任务：{query}

    研究报告：{report_content}

    结论应满足以下要求：
    1. 概括研究的主要内容
    2. 突出最重要的发现
    3. 讨论相关影响或后续步骤
    4. 篇幅约为 2 至 3 段

    如果报告末尾没有“## 结论”章节标题，请在结论开头添加该标题。
    必须使用 {report_format.upper()} 格式的文内引用，并将 Markdown 超链接放在引用该来源的句子或段落末尾，格式如下：([文内引用](url))。

    重要：整篇结论必须使用 {language}；当该语言为 Chinese (Simplified) 时，标题和正文均须使用简体中文。

    请撰写结论：
    """

        return prompt


class GranitePromptFamily(PromptFamily):
    """Prompts for IBM's granite models"""


    def _get_granite_class(self) -> type[PromptFamily]:
        """Get the right granite prompt family based on the version number"""
        if "3.3" in self.cfg.smart_llm:
            return Granite33PromptFamily
        if "3" in self.cfg.smart_llm:
            return Granite3PromptFamily
        # If not a known version, return the default
        return PromptFamily

    def pretty_print_docs(self, *args, **kwargs) -> str:
        return self._get_granite_class().pretty_print_docs(*args, **kwargs)

    def join_local_web_documents(self, *args, **kwargs) -> str:
        return self._get_granite_class().join_local_web_documents(*args, **kwargs)


class Granite3PromptFamily(PromptFamily):
    """Prompts for IBM's granite 3.X models (before 3.3)"""

    _DOCUMENTS_PREFIX = "<|start_of_role|>documents<|end_of_role|>\n"
    _DOCUMENTS_SUFFIX = "\n<|end_of_text|>"

    @classmethod
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

    @classmethod
    def join_local_web_documents(cls, docs_context: str | list, web_context: str | list) -> str:
        """Joins local web documents using Granite's preferred format"""
        if isinstance(docs_context, str) and docs_context.startswith(cls._DOCUMENTS_PREFIX):
            docs_context = docs_context[len(cls._DOCUMENTS_PREFIX):]
        if isinstance(web_context, str) and web_context.endswith(cls._DOCUMENTS_SUFFIX):
            web_context = web_context[:-len(cls._DOCUMENTS_SUFFIX)]
        all_documents = "\n\n".join([docs_context, web_context])
        return "".join([cls._DOCUMENTS_PREFIX, all_documents, cls._DOCUMENTS_SUFFIX])


class Granite33PromptFamily(PromptFamily):
    """Prompts for IBM's granite 3.3 models"""

    _DOCUMENT_TEMPLATE = """<|start_of_role|>document {{"document_id": "{document_id}"}}<|end_of_role|>
{document_content}<|end_of_text|>
"""

    @staticmethod
    def _get_content(doc: Document) -> str:
        doc_content = doc.page_content
        if title := doc.metadata.get("title"):
            doc_content = f"Title: {title}\n{doc_content}"
        return doc_content.strip()

    @classmethod
    def pretty_print_docs(cls, docs: list[Document], top_n: int | None = None) -> str:
        return "\n".join([
            cls._DOCUMENT_TEMPLATE.format(
                document_id=doc.metadata.get("source", i),
                document_content=cls._get_content(doc),
            )
            for i, doc in enumerate(docs)
            if top_n is None or i < top_n
        ])

    @classmethod
    def join_local_web_documents(cls, docs_context: str | list, web_context: str | list) -> str:
        """Joins local web documents using Granite's preferred format"""
        return "\n\n".join([docs_context, web_context])

## Factory ######################################################################

# This is the function signature for the various prompt generator functions
PROMPT_GENERATOR = Callable[
    [
        str,        # question
        str,        # context
        str,        # report_source
        str,        # report_format
        str | None, # tone
        int,        # total_words
        str,        # language
    ],
    str,
]

report_type_mapping = {
    ReportType.ResearchReport.value: "generate_report_prompt",
    ReportType.SubtopicReport.value: "generate_subtopic_report_prompt",
}


def get_prompt_by_report_type(
    report_type: str,
    prompt_family: type[PromptFamily] | PromptFamily,
):
    prompt_by_type = getattr(prompt_family, report_type_mapping.get(report_type, ""), None)
    default_report_type = ReportType.ResearchReport.value
    if not prompt_by_type:
        warnings.warn(
            f"Invalid report type: {report_type}.\n"
            f"Please use one of the following: {', '.join([enum_value for enum_value in report_type_mapping.keys()])}\n"
            f"Using default report type: {default_report_type} prompt.",
            UserWarning,
        )
        prompt_by_type = getattr(prompt_family, report_type_mapping.get(default_report_type))
    return prompt_by_type


prompt_family_mapping = {
    PromptFamilyEnum.Default.value: PromptFamily,
    PromptFamilyEnum.Granite.value: GranitePromptFamily,
    PromptFamilyEnum.Granite3.value: Granite3PromptFamily,
    PromptFamilyEnum.Granite31.value: Granite3PromptFamily,
    PromptFamilyEnum.Granite32.value: Granite3PromptFamily,
    PromptFamilyEnum.Granite33.value: Granite33PromptFamily,
}


def get_prompt_family(
    prompt_family_name: PromptFamilyEnum | str, config: Config,
) -> PromptFamily:
    """Get a prompt family by name or value."""
    if isinstance(prompt_family_name, PromptFamilyEnum):
        prompt_family_name = prompt_family_name.value
    if prompt_family := prompt_family_mapping.get(prompt_family_name):
        return prompt_family(config)
    warnings.warn(
        f"Invalid prompt family: {prompt_family_name}.\n"
        f"Please use one of the following: {', '.join([enum_value for enum_value in prompt_family_mapping.keys()])}\n"
        f"Using default prompt family: {PromptFamilyEnum.Default.value} prompt.",
        UserWarning,
    )
    return PromptFamily()
