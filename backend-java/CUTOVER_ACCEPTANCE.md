# Java 切换验收记录（2026-10-29）

范围：现用 DeepSeek、DashScope/Qwen、Tavily 及三来源研究、人工审核、聊天、资料和导出；不要求全部历史供应商等价迁移。

## 自动化与运行切换已验证

- 根 Compose 的唯一后端为 Java，保留兼容服务名和镜像名 `gpt-researcher`、`gptresearcher/gpt-researcher`；前端内部地址为 `http://gpt-researcher:8000`。
- Java 后端与迁移容器入口均为 `java -jar /app/app.jar`；迁移容器执行普通 `migrate` 并以状态 0 退出，不在默认启动中自动请求旧库基线。
- `/health/ready` 返回 HTTP 200，数据库、pgvector、Alembic `0002_shared_library` 和 Flyway 版本 1 均就绪。
- 报告和文档读取接口及前端首页返回 HTTP 200；运行中的后端、迁移和前端容器均由当前仓库根 Compose 创建。
- `scripts/test_java_migration.ps1 -JavaOnly`：134 项测试通过，无失败、错误、跳过；Java 建库和隔离备份恢复通过。
- 前端 lint、TypeScript 检查、生产构建通过。
- Java 容器无凭据 TLS 探测：Tavily 200、DashScope 404、DeepSeek 401，证明握手成功，不代表真实模型通过。
- 当前应用库备份已恢复到隔离 PostgreSQL；报告、运行、事件、聊天、文档、分块、附件、证据、嵌入配置九表内容摘要一致。报告、文档、附件均为空，不能据此证明有历史数据时的业务兼容性。
- 默认 CI 使用 Java-only 隔离数据库回归及前端检查，不注入真实模型密钥。

## 尚未完成

真实研究结果以 `acceptance/live-runs.json` 为准。审核预算修复后尚无成功的付费供应商研究记录；`web`、`local`、`hybrid`、浏览器人工审核及导出视觉检查仍需分别验收。自动化与运行切换通过不能替代真实供应商验收。

业务库备份和本次日志位于忽略的 target/ 目录，不提交数据或凭据。

## 真实研究第 2 次结果

2026-10-29 web 研究失败：ResearchNodes.review 调用模型时返回 length，SpringResearchModel 拒绝截断输出。TLS 问题未在本次复现，失败不能归因为网络。原模型选择保持不变，未自动重试；累计预算 2/6。后续先核查审核阶段的输出预算和供应商推理输出行为，再继续真实验收。

## 审核预算修复（2026-10-29）

已增加章节审核与事实核查独立输出预算，默认 16000；阶段配置优先于显式角色配置，未配置阶段值时保留角色上限。原模型不变，截断仍失败且不重试。Java-only 全量测试及备份恢复重新通过；尚未执行修复后的真实研究，预算仍为 2/6。
