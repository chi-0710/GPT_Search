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
- 可视化调用模型生成 Mermaid，传入真实证据，仅精确 None 表示无可视化数据；不执行模型生成的代码。此项还不是图表图片附件化或视觉验收完成。
- ResearchPublication 将报告终态、证据、导出附件、最终事件放入同一事务。失败回滚整批写入。
- 取消和发布共用运行锁；断线取消人工等待、排队任务和已登记的外部调用，章节协调器取消并等待兄弟任务。断线最多等待 15 秒清理，超时会记录警告；尚未证明所有供应商都支持立即中断网络请求。
- 取消的运行保存 cancelled；兼容旧库 reports 状态约束，报告保存 failed，具体原因保留在运行记录。

## 新增 Java 配置

| 配置 | 默认值 | 用途 |
|---|---|---|
| JAVA_SECTION_CONCURRENCY | 3 | 并行章节数 |
| JAVA_LOCAL_CONTEXT_BUDGET | 10000 | 文件证据上下文预算 |
| JAVA_WEB_CONTEXT_BUDGET | 10000 | 网络证据上下文预算 |
| JAVA_VISUALIZATION_ENABLED | true | 运行原有 Mermaid 可视化节点；false 产生明确跳过事件 |

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
- MCP、远程文档 URL、请求级模型覆盖、LLM_KWARGS、图片生成与全部供应商适配尚未完成。
- Mermaid 图表尚未渲染为数据库图片附件；PDF/DOCX 图表视觉呈现未验收。
- 中断任务恢复已实现为显式单写入者启动选项；资料批量重建与嵌入模型切换尚未完成。
- Java CLI 已实现参数解析及共享执行入口，完整真实研究未验收；Flyway 接管、完整协议矩阵和默认 Java Compose/CI 尚未完成。
- 数据库备份恢复、默认入口切换、Python 回滚演练和无 Python 验收尚未执行。

## 数据与提交约束

默认服务继续运行 Python，Java 写入仅用于隔离测试；不得把当前阶段描述为全面替代。
Python 源码及测试在所有验收门槛通过后才删除。历史报告、资料、附件、实验结果与 PostgreSQL 卷必须保留。
前端文件包含既有未提交改动，当前兼容修正保留在工作区，不能将整文件的其他改动混入本批 Java 提交。
真实 DeepSeek/DashScope/Tavily 研究调用额度本批使用 0 次，剩余总预算 6 次，每次最多 2 章，关闭付费图片生成。

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
