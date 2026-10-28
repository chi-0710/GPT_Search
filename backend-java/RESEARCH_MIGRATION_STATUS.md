# Java 研究流程迁移当前状态

本文件描述 2026-10-28 当前代码；MIGRATION_CHECKLIST.md 中按日期追加的记录是历史结果，不代表当前完成状态。

## 本批已接入

- HTTP 和 WebSocket 共用 ResearchExecutionService；入口校验、冻结资料范围、配置模型后创建运行。
- `/ws` 支持 `start {JSON}`、`ping`、JSON 格式人工反馈及 `human_feedback {JSON}`。同一连接只绑定一次运行；重复启动不会创建孤立数据库任务。
- 人工反馈必须携带服务端发出的 `run_id`、`round`、`request_id`，`content` 或 `feedback` 为审核意见，精确 `no` 表示接受。拒绝后重新规划；过期或跨连接反馈不生效。
- 前端共享 hook 保存并回传审核标识；没有标识的旧 Python 审核事件继续使用旧反馈格式。
- `task_options` 支持 `max_sections`（默认 3）、三个 `max_*_revisions`（默认 3，null 为不设业务修订上限）、`include_human_feedback`、`follow_guidelines`、`guidelines`。同名顶层字段覆盖 task_options；非法值及不支持的嵌套配置明确拒绝。
- HTTP 无人工审核通道，显式要求人工审核时返回错误；不再暗中接受。未启用时产生跳过事件。
- 指南传入模型和章节审阅；章节草稿仅作为日志进度发送，最终规范报告通过 report_complete 发布一次。
- 可视化调用模型生成受限 Mermaid 流程图，使用真实证据；Java 渲染 PNG 并作为数据库附件随报告原子提交。仅精确 None 表示无可视化数据。不执行模型代码，不支持任意 Mermaid 图型。
- ResearchPublication 将报告终态、证据、导出附件、最终事件放入同一事务。失败回滚整批写入。
- 取消和发布共用运行锁；断线取消人工等待、排队任务和已登记的外部调用，章节协调器取消并等待兄弟任务。断线最多等待 15 秒清理，超时会记录警告；尚未证明所有供应商都支持立即中断网络请求。
- 取消的运行保存 cancelled；兼容旧库 reports 状态约束，报告保存 failed，具体原因保留在运行记录。

## 新增 Java 配置

| 配置 | 默认值 | 用途 |
|---|---|---|
| JAVA_SECTION_CONCURRENCY | 3 | 并行章节数 |
| JAVA_LOCAL_CONTEXT_BUDGET | 10000 | 文件证据上下文预算 |
| JAVA_WEB_CONTEXT_BUDGET | 10000 | 网络证据上下文预算 |
| JAVA_VISUALIZATION_ENABLED | true | 将受限 Mermaid 流程图渲染为 PNG 附件；false 明确跳过 |
| JAVA_REQUIRE_FLYWAY | false | 正式 Java 部署应为 true，就绪检查要求 Java 迁移记录 |
| JAVA_EXPECTED_MIGRATION | 1 | 当前 Java 迁移版本 |
| JAVA_MCP_ALLOWED_COMMANDS | 空 | 可启动的非 Python MCP 程序完整命令名，逗号分隔 |
| JAVA_MCP_ALLOWED_ROOTS | 空 | MCP 文件参数可访问的既有目录，分号分隔 |

请求 `export_pdf`、`export_docx` 默认 true。以上配置尚未进入默认 Compose；正式切换时必须补齐环境映射，不能只写根目录 .env。

## 本批验证

- `scripts/test_java_migration.ps1`：Maven verify 107 项通过，0 失败、0 错误、0 跳过，包含 21 项真实 PostgreSQL 集成测试。日志位于忽略的 `target/workflow-migration-final.log`。
- 真实 Spring WebSocket 握手、ping、无效启动和未绑定反馈验证通过；完整研究图通过本地模型 HTTP 替身验证人工审核及非交互两种路径。
- 事务回滚验证确认完成保存失败时附件、运行事件、证据均未残留；已取消运行不能创建发布附件。
- 取消测试覆盖外部调用中断及清理等待、并行章节兄弟任务取消、取消后禁止发布、发布成功后断线不回写失败。
- 前端 `npm run lint` 与 `npx tsc --noEmit --incremental false` 通过；没有把类型检查当作浏览器端到端验收。

## 尚未通过的切换门槛

- 完整浏览器研究与人工审核、真实供应商六次预算内验收尚未执行。
- HTTP/WebSocket 报告聊天和搜索工具已接入；浏览器真实供应商聊天尚未验收。
- MCP stdio 与 Streamable HTTP 的连接、鉴权、发现、选择、调用、超时、取消及清理已接入并通过离线协议测试；WebSocket 传输及 fast 之外的策略未迁移，文件/域名限定研究与 MCP 组合明确拒绝。
- 远程文档 URL、请求级模型覆盖、LLM_KWARGS、图片供应商与全部供应商适配尚未完成。现有 OpenAI 兼容地址映射不计为完整供应商验收。
- PNG 流程图实际预览通过；真实 PostgreSQL 验证图片下载及 PDF/DOCX 嵌入。完整 PDF/DOCX 图表视觉验收未完成。
- 资料批量重建与嵌入模型切换已接入 Java CLI，真实 PostgreSQL 验证成功、准备失败、事务失败与并发资料变化；大资料库内存/性能验收未执行。
- Flyway 空库建表与严格旧库基线、重复执行已通过；真实应用库接管、完整协议矩阵和默认 Java Compose/CI 未完成。
- 隔离 Java 建库与备份恢复通过；实际业务库备份恢复、默认入口切换、Python 回滚演练和默认部署无 Python 验收仍未通过。

## 数据与提交约束

默认服务继续运行 Python，Java 写入仅用于隔离测试；不得把当前阶段描述为全面替代。
Python 源码及测试在所有验收门槛通过后才删除。历史报告、资料、附件、实验结果与 PostgreSQL 卷必须保留。
前端文件包含既有未提交改动，当前兼容修正保留在工作区，不能将整文件的其他改动混入本批 Java 提交。
真实研究预算累计使用 1/6 次：web 初始 Tavily 搜索发生 TLS 握手失败，未生成报告。无凭据连通性检查确认 DashScope 也存在 TLS 问题；DeepSeek 根地址可达不等于真实模型通过。暂停其余实测，不扩大预算。记录见 `acceptance/live-runs.json`。

## 后续批次：聊天、CLI 和中断恢复

最新隔离 Maven verify：116 项全部通过，含 24 项 PostgreSQL 集成测试，0 失败、0 错误、0 跳过；日志 `target/chat-cli-recovery-final.log`。打包后 `java -jar ... research --help` 实际执行成功。聊天协议测试使用本地模型 HTTP 服务和搜索替身，没有调用真实付费供应商。

- `/api/chat` 保留 `{report,messages}` → `{response:{role,content,timestamp,metadata}}` 协议；不指定 report_id 时继续由现有前端保存历史。
- 可选 `report_id` 使用数据库中的报告与历史，输入 messages 的最后一条为本轮用户问题；本轮用户消息和助手回复原子保存。回复包含 UUID，后续保存同一 UUID 不重复插入。
- 使用实际模型 tool_calls 协议，最多执行四次 quick_search，逐次记录来源和模型用量；达到上限失败，不无限自动重试。不支持的工具不会执行。未配置 Tavily 时不提供搜索工具。
- WebSocket `chat` 接受原有 message 或 messages 格式，异步返回 type=chat 事件，等待模型期间仍可 ping；断线和关闭会中断聊天任务。
- Java CLI：`java -jar backend-java/target/researchrunner-api-0.1.0-SNAPSHOT.jar research "研究问题" --report_source hybrid --no-pdf`。使用既有环境变量配置数据库、模型、嵌入和搜索，CLI 不读取根目录 .env 文件，需通过容器环境或显式导出传入。不要与另一套后端同时执行写入任务。
- CLI 兼容 query、report_type、tone、encoding、query_domains、report_source、no-pdf、no-docx；encoding 与旧 CLI 的持久化路径一致，附件 Markdown 始终 UTF-8。CLI 无 HTTP 监听端口。
- `JAVA_RECOVER_INTERRUPTED_TASKS=true` 仅在停止旧写入者后启用，默认关闭。启动前将带 execution_backend=java 的未结束运行标为失败，将遗留 processing 文档标为失败，保留文件和已有向量，不重跑收费研究。早期未带标记的 Java 运行须人工核对归属，不自动误判 Python 运行。
- 修复写入模式下 CORS 只允许 GET 的问题，浏览器可发起 POST/PUT/DELETE 预检；只读模式保留 GET/OPTIONS。

## 当前批次：Flyway、MCP、索引切换和图表附件

2026-10-28：本批 `scripts/test_java_migration.ps1 -JavaOnly` 已有 132 项通过及隔离备份恢复通过记录；测试以打包后的 Java CLI 从空 PostgreSQL 建库，对恢复后的报告、聊天、文档、向量、附件、证据和嵌入配置逐表比对内容摘要。后续复验暴露 MCP 测试进程启动期限过短，修正并增强在途取消检查后，最新完整旧 Alembic 库对照 `scripts/test_java_migration.ps1` 再次 132 项通过，0 失败、0 错误、0 跳过（`target/legacy-release.log`）。此结果不代表默认业务部署已替换。

- Flyway 采用显式 `migrate` 命令，禁用启动时自动迁移和自动基线；已有数据库必须携带 `--baseline-existing`，并匹配 Alembic 版本及冻结的列、约束、索引结构。保留 Alembic 记录；不执行 clean/repair。
- MCP 使用官方 Java SDK 0.18.4；仅工具返回内容进入证据注册表，模型选工具的文本不作为证据。连接配置持久化时只保存名称和传输类型，移除凭据。
- MCP 测试实际启动 Java stdio 夹具进程，并通过真实本地 HTTP 服务检查鉴权与协议；超时、取消后检查进程退出。这些不是外部 MCP 服务实测。
- 索引准备在事务外执行；所有向量准备后锁定资料、重新检查快照，在同一事务内替换向量、构建并验证新 HNSW 索引、切换 active profile。任何失败保留旧索引与资料。过期索引任务不能写入已停用的 profile。
- 流程图 PNG、Markdown、中文 PDF、DOCX 与最终证据和报告终态共用发布事务。导出渲染只从附件服务读图，不下载任意远程图片。公开图片 URL 使用 PUBLIC_API_URL，避免浏览器端口与内部服务地址混淆。
- 可复用真实验收脚本 `scripts/test_java_live.ps1` 使用独立 tmpfs 数据库、记录六次预算、保留结果与测试库备份；每次最多两章，禁用付费图片。当前只执行一次，失败后没有自动重试。

操作命令与切换约束见 `OPERATIONS.md`。`acceptance/provider-status.json` 逐项记录全部 78 类供应商的实现、协议与真实调用状态（27 聊天、21 嵌入、21 检索、7 抓取、2 图片）。尚未迁移的供应商范围没有因本批测试通过而减少；禁止将本批称为“全部供应商完成”或“默认部署验收完成”。
