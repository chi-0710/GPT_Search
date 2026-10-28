> 当前实现：仅保留 LangGraph 多智能体报告，支持网络、文件和混合资料来源。旧报告模式请求自动转换；内部草稿格式继续保留。

# LangGraph x ResearchRunner
[LangGraph](https://python.langchain.com/docs/langgraph) is a library for building stateful, multi-actor applications with LLMs. 
This example uses Langgraph to automate the process of an in depth research on any given topic.


## Use case
By using Langgraph, the research process can be significantly improved in depth and quality by leveraging multiple agents with specialized skills. 

Inspired by the recent [STORM](https://arxiv.org/abs/2402.14207) paper, this example showcases how a team of AI agents can work together to conduct research on a given topic, from planning to publication.

An average run generates a 5-6 page research report in multiple formats such as PDF, Docx and Markdown.

Please note: Multi-agents are utilizing the same configuration of models like ResearchRunner does. However, only the SMART_LLM is used for the time being. Please refer to the [LLM config pages](https://docs.gptr.dev/docs/gpt-researcher/llms).

## The Multi Agent Team
The research team is made up of 8 agents:
- **Human** - The human in the loop that oversees the process and provides feedback to the agents.
- **Chief Editor** - Oversees the research process and manages the team. This is the "master" agent that coordinates the other agents using Langgraph.
- **Researcher** (gpt-researcher) - A specialized autonomous agent that conducts in depth research on a given topic.
- **Editor** - Responsible for planning the research outline and structure.
- **Reviewer** - Validates the correctness of the research results given a set of criteria.
- **Revisor** - Revises the research results based on the feedback from the reviewer.
- **Writer** - Responsible for compiling and writing the final report.
- **Publisher** - Responsible for publishing the final report in various formats.

## How it works
Generally, the process is based on the following stages: 
1. Planning stage
2. Data collection and analysis
3. Review and revision
4. Writing and submission
5. Publication

### Architecture
<div align="center">
<img align="center" height="600" src="https://github.com/user-attachments/assets/ef561295-05f4-40a8-a57d-8178be687b18">
</div>
<br clear="all"/>

### Steps
More specifically (as seen in the architecture diagram) the process is as follows:
- Browser (gpt-researcher) - Browses the internet for initial research based on the given research task.
- Editor - Plans the report outline and structure based on the initial research.
- For each outline topic (in parallel):
  - Researcher (gpt-researcher) - Runs an in depth research on the subtopics and writes a draft.
  - Reviewer - Validates the correctness of the draft given a set of criteria and provides feedback.
  - Revisor - Revises the draft until it is satisfactory based on the reviewer feedback.
- Writer - Compiles and writes the final report including an introduction, conclusion and references section from the given research findings.
- Publisher - Publishes the final report to multi formats such as PDF, Docx, Markdown, etc.

## How to run
1. Install required packages found in this root folder including `langgraph`:
    ```bash
    pip install -r requirements.txt
    ```
3. Update env variables, see the [ResearchRunner docs](https://docs.gptr.dev/docs/gpt-researcher/llms) for more details.

2. Run the application:
    ```bash
    python main.py
    ```

## Usage
To change the research query and customize the report, edit the `task.json` file in the main directory.
#### Task.json contains the following fields:
- `query` - The research query or task.
- `model` - The OpenAI LLM to use for the agents.
- `max_sections` - The maximum number of sections in the report. Each section is a subtopic of the research query.
- `max_plan_revisions` - The maximum number of human-requested plan revisions before the workflow exits with a clear error. Set to `null` to rely on LangGraph's recursion limit instead.
- `include_human_feedback` - If true, the user can provide feedback to the agents. If false, the agents will work autonomously.
- `source` - Research sources: `web`, `local`, or `hybrid`. Local research uses the PostgreSQL document library and the document scope frozen when the run starts.
- `follow_guidelines` - If true, the research report will follow the guidelines below. It will take longer to complete. If false, the report will be generated faster but may not follow the guidelines.
- `guidelines` - A list of guidelines that the report must follow.
- `verbose` - If true, the application will print detailed logs to the console.

#### For example:
```json
{
  "query": "Is AI in a hype cycle?",
  "model": "gpt-4o",
  "max_sections": 3,
  "max_plan_revisions": 3,
  "include_human_feedback": false,
  "source": "web",
  "follow_guidelines": true,
  "guidelines": [
    "The report MUST fully answer the original question",
    "The report MUST be written in apa format",
    "The report MUST be written in english"
  ],
  "verbose": true
}
```

## To Deploy

The publisher only assembles Markdown. HTTP, WebSocket and CLI exports are created
once through `backend/server/artifact_service.py` and stored in PostgreSQL.
The old `publish_formats` task option is ignored; it no longer writes local files.
Use the CLI's `--no-pdf` and `--no-docx` options to disable individual exports.

```shell
pip install langgraph-cli
langgraph up
```

From there, see documentation [here](https://github.com/langchain-ai/langgraph-example) on how to use the streaming and async endpoints, as well as the playground.
