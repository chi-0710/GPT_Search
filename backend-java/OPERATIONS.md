# ResearchRunner Java 运维与验收

当前默认入口为 Java。维护或迁移前停止业务写入，保留备份，避免多个进程同时修改业务数据库。

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
# Java 隔离回归（默认使用 Java 建库）
./scripts/test_java_migration.ps1
# 实际供应商调用：先检查预算和网络，不自动重试
./scripts/test_java_live.ps1 -Source web
./scripts/test_java_live.ps1 -Source local
./scripts/test_java_live.ps1 -Source hybrid
```

测试脚本创建独立数据库和网络，不挂载业务数据卷。真实验收读取原 DeepSeek、DashScope/Qwen、Tavily 配置，使用 `acceptance/live-runs.json` 累计最多六次，每次最多两章。日志、下载文件和测试库备份保存在忽略的 `target/live-acceptance/`；不要将包含业务内容或凭据的日志提交到仓库。不要并发运行真实验收脚本或删除预算记录。

2026-10-28 第一次 web 实测在 Tavily TLS 握手失败，未发布报告；随后无凭据检查也发现 DashScope TLS 失败。DeepSeek 地址可达仅说明网络连通，不是模型、工具调用或研究通过。余下实测暂不运行，直到网络恢复。

## 默认部署与恢复

使用根目录 Compose 构建 Java 服务。恢复前备份当前数据库，避免覆盖新增数据；保留现有服务名和数据卷。实际验证结果见 CUTOVER_ACCEPTANCE.md，历史迁移记录不代表当前部署状态。

### Maven 镜像构建

默认多阶段 `Dockerfile` 通过 `MAVEN_MIRROR_URL` 选择 Central 镜像，根 Compose 默认使用 `https://maven.aliyun.com/repository/public`。BuildKit 缓存 Maven 本地仓库，`mvn verify` 设置 20 分钟硬超时并运行完整测试。构建不再单独执行 `dependency:go-offline`，因为该目标会解析实际构建不需要的额外元数据，在部分网络环境中可能长时间停滞。

```powershell
docker compose build gpt-researcher migration
```

在能稳定访问 Maven Central 的环境中可以显式覆盖：

```powershell
$env:MAVEN_MIRROR_URL = "https://repo.maven.apache.org/maven2"
docker compose build gpt-researcher migration
```

网络不可用但已有经过 `mvn verify` 验证的 JAR 时，可使用独立的 runtime-only Dockerfile。脚本仅组装现有 JAR，不编译、不测试，也不修改默认 Compose；构建结束后会删除临时暂存副本。

```powershell
./scripts/build_java_runtime_image.ps1 `
    -JarPath ./backend-java/target/researchrunner-api-0.1.0-SNAPSHOT.jar
```

不要将 `target-image/app.jar` 提交到仓库。正常发布仍以默认多阶段 `Dockerfile` 为准。

### 审核输出预算

章节审核和事实核查分别使用 `JAVA_SECTION_REVIEW_TOKEN_LIMIT`、`JAVA_FACT_CHECK_TOKEN_LIMIT`。优先级为阶段专用值、显式 FAST/STRATEGIC_TOKEN_LIMIT、默认 16000。保留原模型选择，规划与写作预算不变；不自动扩容或重试。预算必须为正数，供应商的模型上限仍然适用。发生截断时继续失败，并记录阶段与模型；增加预算不代表真实研究已验收。
