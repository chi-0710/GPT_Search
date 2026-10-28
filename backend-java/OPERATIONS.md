# ResearchRunner Java 运维与验收

当前默认入口仍为 Python。以下 Java 写入命令仅用于隔离验收，或在停止旧业务写入者并完成切换前置验收后使用。不得让 Java 和 Python 同时写入同一个应用库。

## 数据库迁移

通过环境配置 `JAVA_DATABASE_URL`（JDBC URL）、`POSTGRES_USER`、`POSTGRES_PASSWORD`；不要将凭据写进命令或提交。Java 不自动加载根 `.env`，容器须显式传入环境。

```text
java -jar researchrunner-api.jar migrate
java -jar researchrunner-api.jar migrate --baseline-existing
```

第一条适用于空库和已经由 Flyway 管理的库。第二条仅用于经过备份、停止写入且确认是旧版数据库的首次接管：要求 Alembic `0002_shared_library`、pgvector 和完整已知结构签名。结构不符时拒绝基线，不删除旧表或迁移记录，不使用自动 baseline、repair 或 clean。存在额外定制索引的旧库也会拒绝，须核实后建立专门的兼容迁移，不能绕过检查。

后续迁移新增 `src/main/resources/db/migration/V<版本>__<说明>.sql`；已应用脚本不改写。Java 正式启动应设置 `JAVA_REQUIRE_FLYWAY=true` 和对应 `JAVA_EXPECTED_MIGRATION`（当前 1）。就绪检查失败时禁止接入流量。

## 批量重建与模型切换

停止接受资料修改和研究任务，等待原任务结束，配置目标 `EMBEDDING`、`EMBEDDING_DIMENSION`、`EMBEDDING_KWARGS` 和供应商凭据后运行：

```text
java -jar researchrunner-api.jar reindex --dry-run
java -jar researchrunner-api.jar reindex
```

dry-run 仅盘点目标配置和资料数量，不请求嵌入供应商。正式命令会产生嵌入费用：准备全部新向量后，检查资料快照未改变，再原子提交新的 profile、向量和验证就绪的 HNSW 索引。准备、索引构建、验证或事务失败都保留原数据。成功后应用必须使用目标嵌入配置重新启动。

当前实现将准备数据留在内存中；大资料库须先测容量，尚无持久化分批 staging。旧物理索引不会自动删除；本批不进行索引清理。HNSW 支持范围为 1–2000 维，超限明确拒绝。

## MCP 客户端

请求 `mcp_configs` 支持 stdio 或 `connection_url` 的 HTTP Streamable 传输，HTTP 可传 `connection_headers` 和 `connection_token`。工具名称限定在本次连接实际发现的集合内。采用官方 [MCP Java SDK 0.18.4](https://java.sdk.modelcontextprotocol.io/v0.18.4/client/)。

stdio 必须由运维配置 `JAVA_MCP_ALLOWED_COMMANDS` 精确允许程序；不允许 Python、uv/uvx、命令解释器或 Python 脚本参数。文件路径参数必须能解析到 `JAVA_MCP_ALLOWED_ROOTS` 内的真实路径，默认不允许任何路径。允许的服务程序本身仍须可信；参数检查不是对子进程操作系统权限的隔离。

当前仅支持 fast 策略；WebSocket 传输、文件限定研究及 URL/域名限制与 MCP 同时启用会在运行前报错。未实现的策略不能用 fast 静默代替。工具文本与 structuredContent 进入证据，非文本资源内容尚未迁移。没有新增生产 MCP 服务端。

## 可视化和导出

`JAVA_VISUALIZATION_ENABLED=true` 让模型提取有依据的流程，Java 渲染成 PNG 附件。仅支持受限的 Mermaid flowchart 节点与箭头，不支持任意图型、HTML、脚本或远程图片。`None` 明确表示缺少绘图依据。

图表、Markdown/PDF/DOCX、证据和报告终态在同一数据库事务中提交。`PUBLIC_API_URL` 必须是浏览器可访问的后端公开地址，不是容器内部地址。导出文件从已保存的附件字节嵌入图片；失败会回滚本次新附件。

## 可复现验收

```powershell
# 无 Python 的隔离 Java 建库、完整测试、备份恢复验证
./scripts/test_java_migration.ps1 -JavaOnly
# 旧 Alembic 库结构对照（保留历史验证用途）
./scripts/test_java_migration.ps1
# 实际供应商调用：先检查预算和网络，不自动重试
./scripts/test_java_live.ps1 -Source web
./scripts/test_java_live.ps1 -Source local
./scripts/test_java_live.ps1 -Source hybrid
```

测试脚本创建独立数据库和网络，不挂载业务数据卷。真实验收读取原 DeepSeek、DashScope/Qwen、Tavily 配置，使用 `acceptance/live-runs.json` 累计最多六次，每次最多两章。日志、下载文件和测试库备份保存在忽略的 `target/live-acceptance/`；不要将包含业务内容或凭据的日志提交到仓库。不要并发运行真实验收脚本或删除预算记录。

2026-10-28 第一次 web 实测在 Tavily TLS 握手失败，未发布报告；随后无凭据检查也发现 DashScope TLS 失败。DeepSeek 地址可达仅说明网络连通，不是模型、工具调用或研究通过。余下实测暂不运行，直到网络恢复。

## 默认部署切换门槛与回滚

尚未完成全部供应商、完整浏览器流程、真实三来源研究及生产库恢复演练，因此本批不修改默认 Compose 或删除 Python。

切换前须完成：完整供应商协议矩阵；真实三来源研究、聊天及导出；真实应用库备份及隔离恢复对照；Python 回滚演练；Java CLI 与维护命令；前端人工反馈和下载；无 Python 默认环境验证。测试库恢复成功不能替代真实应用库恢复演练。

通过后停止 Python 接收新任务并等待结束，备份后停掉 Python 写入服务，再执行显式 Java 基线/迁移并启动 Java。保留原服务名、端口、网络、数据库及卷。核对历史与新业务后才保留 Java 默认入口。失败时先停止 Java 写入，保存切换后的新增数据，再从保留镜像或 Git 历史恢复旧服务；不得用旧备份直接覆盖新增数据，不删除 PostgreSQL 卷。
