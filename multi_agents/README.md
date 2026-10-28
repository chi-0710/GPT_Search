> 当前实现：仅保留 LangGraph 多智能体报告，支持网络、文件和混合资料来源。旧报告模式请求自动转换；内部草稿格式继续保留。

# LangGraph 多智能体研究（ResearchRunner）

本目录是知行研究助手默认报告模式的 LangGraph 实现：由多个具备不同职责的智能体协作，对给定主题完成从规划到发布的研究流程。设计参考 [STORM](https://arxiv.org/abs/2402.14207) 的思路。

## 智能体

- **Human**：人工审核环节，可接受或提出修改意见
- **Chief Editor**（`orchestrator.py`）：协调团队、编译并驱动 LangGraph 工作流
- **Browser**（`agent.py` / `search` 初始研究）：针对主题进行初始资料收集
- **Editor**（`editor.py`）：基于初始研究规划报告结构与章节
- **Researcher**：并行开展各章节研究并产出草稿
- **Reviewer**：按固定标准审阅章节草稿并给出意见
- **Revisor**（`reviser.py`）：根据审阅意见修订草稿
- **Writer**（`writer.py`）：汇总各章节并撰写最终报告
- **Fact Checker**（`fact_checker.py`）：基于证据核查事实，失败时返回章节返修
- **Visualizer**（`visualizer.py`）：生成受限的 Mermaid 流程图
- **Publisher**（`publisher.py`）：组装最终 Markdown，导出由后端 artifact 服务统一完成

## 工作流程

初始研究 → 规划 → 人工审核（可选）→ 章节并行研究/审核/修订 → 汇总写作 → 事实核查与章节返修 → 可视化 → 发布。

- 每个章节是独立的子图：researcher → reviewer ⇄ reviser。
- 规划、章节与事实核查的返修均有上限，超限显式失败，不会强制接受未解决的审阅意见。
- 一次运行会冻结资料范围，所有阶段共享同一套证据编号与引用链接。
- 运行期依赖（模型、搜索、证据注册表、WebSocket）通过 `runtime.py` 传入，不进入可序列化的图状态。

## 如何运行

生产入口是后端与 CLI，而不是本目录的独立进程：

```powershell
# 启动后端后由 HTTP/WebSocket 触发
python -m uvicorn main:app --reload

# 或直接用 CLI 执行一次研究
python cli.py "研究主题" --report_source hybrid
```

如需使用本目录的 `task.json` 调试，可执行：

```powershell
python multi_agents/main.py
```

该入口复用与 CLI 相同的持久化执行路径（读取 `task.json` 的查询与来源）。

## task.json 字段

- `query`：研究问题
- `max_sections`：报告章节数上限
- `max_plan_revisions`：规划阶段人工要求修改的最大轮数；`null` 表示不设业务上限
- `include_human_feedback`：是否开启规划阶段人工审核
- `source`：`web`、`local` 或 `hybrid`（本地研究使用 PostgreSQL 资料库与冻结范围）
- `follow_guidelines` / `guidelines`：是否要求报告遵循指定写作规范及规范内容
- `model`：模型名（由环境变量 `STRATEGIC_LLM`/`SMART_LLM` 覆盖）
- `verbose`：是否输出详细日志

示例：

```json
{
  "query": "Is AI in a hype cycle?",
  "max_sections": 3,
  "max_plan_revisions": 3,
  "include_human_feedback": false,
  "source": "web",
  "follow_guidelines": true,
  "guidelines": [
    "报告必须使用 APA 格式",
    "每个章节必须通过超链接提供支持性来源"
  ],
  "verbose": true
}
```

## 导出与部署

Publisher 只负责组装 Markdown。HTTP、WebSocket 与 CLI 的 Markdown/PDF/DOCX 导出统一由 `backend/server/artifact_service.py` 生成并保存到 PostgreSQL；旧的 `publish_formats` 本地文件导出已移除，`--no-pdf` / `--no-docx` 可关闭对应格式。

后端默认通过 Uvicorn 运行；前端使用 `frontend/nextjs`。远程 LangGraph 托管部署只保留原有流式页面行为，不属于默认执行路径。
