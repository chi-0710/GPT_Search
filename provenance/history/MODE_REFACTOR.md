# 多智能体唯一模式改造记录

应用只执行 LangGraph 多智能体工作流。普通、详细、独立深度研究及其他已知旧模式请求在创建任务前转换为 `multi_agents`；未知模式返回错误。历史报告和原有类型记录不改写。

## 删除清单

- `backend/report_type/`：普通、详细、深度报告实现、示例、README 与包导出。
- `multi_agents/ag2/`：AG2 备用工作流及其配置、依赖、文档；后端不再自动回退。
- `search/skills/deep_research.py`：独立递归深度研究引擎；同时删除专用配置、提示词和执行分支。
- `deep_agents/` 中的所有 Python 执行脚本（包含基准运行器和语料构建脚本）、`requirements.txt`、`task.json`、`drb_harness.patch`。
- `tests/test_deep_research_parsing.py`、`tests/skills/test_deep_research_empty_results.py`、`tests/documents-report-source.py`：已删除模式的专属测试/旧示例。
- `frontend/nextjs/components/Langgraph/Langgraph.js`、`frontend/nextjs/helpers/findDifferences.ts`：独立远程工作流启动代码及专用比较工具。
- 前端旧模式选择、后端模式分支、废弃枚举/提示词、AG2 可选依赖及前端 LangGraph SDK 依赖。

`deep_agents/benchmark_data/` 的数据、`benchmark_results/`、历史说明文档继续保留；说明文档已标记归档。应用数据库、已有报告、资料库和附件均不删除。

## 主要调整

- HTTP、WebSocket、CLI 使用同一个执行适配器，统一返回正文、来源、费用和证据快照。
- 初步研究与并行章节共享运行级证据注册器、冻结后的文件检索范围；运行时依赖放在独立上下文中。
- 网络、文件、混合来源均可用于多智能体；文件模式不调用网页检索，混合模式保留单侧故障降级。
- 计划、章节、事实审核采用已有修订上限策略；事实问题进入章节返修，超限明确失败。
- 初步研究无证据时失败；部分章节不足时标记，全部章节不足时失败。章节异常或取消会取消并等待其他章节退出。
- 章节正文不再作为最终报告流发送；最终正文在证据链接渲染后统一发布、存储和导出。
- 保留 `Research`、检索/Embedding/写作核心与内部草稿格式 `research_report`、`subtopic_report`。内部格式不等于对外报告模式。
- 前端统一多智能体默认设置，保留资料来源选择、资料库、历史展示、问答与附件下载。旧前端设置在发送请求时归一化。

## 验证

- 相关后端回归：119 项通过、5 项跳过（4 项需要数据库集成环境，1 项需要验证环境中的 pypdf）；模型及搜索调用使用模拟。
- 前端 `npm run lint`、`npm run build` 通过。
- Python 编译检查、CLI 帮助检查及 Compose 配置检查通过。
- 有效 Python 代码中不存在对已删除实现的导入或执行引用。
- Docker 初始检查发现 Linux 引擎未运行；后续服务状态检查未获许可，未完成容器重建与真实运行验收。

尚未完成的环境验收：启动 Docker Desktop 后运行 `docker compose up --build -d`，验证三个资料来源的真实研究、报告保存、引用访问与下载。不需要迁移或删除历史数据。
