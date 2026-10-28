# 知行研究助手 / ResearchRunner Java 后端

`backend-java/` 是知行研究助手的 Java 实现，与 Python 后端共用同一套公开 HTTP/WebSocket 契约、同一 PostgreSQL 数据库结构和同一前端。默认以只读方式运行；默认部署入口仍是 Python 后端，不要把前端入口切换到本服务，除非已完成写入切换并停止 Python 写入。

## 技术栈

- Java 21、Spring Boot 3.5.7
- Spring AI 1.1.8（OpenAI / Anthropic / Azure OpenAI 客户端）
- LangGraph4j Core 1.9.7（研究图，不使用 Spring AI 2.x 集成模块）
- MyBatis 3.0.4、PostgreSQL JDBC、Flyway
- MCP Java SDK 0.18.4
- openhtmltopdf、PDFBox、Apache POI、Jsoup（导出与文档解析）

## 已实现

- 研究入口：`POST /report/`、`POST /api/multi_agents`（受写开关控制）、CLI `research`
- 研究图：初始证据研究 → 规划 → 人工审核 → 并行章节研究/审核/修订 → 汇总写作 → 基于证据的事实核查与章节返修 → 可视化 → 发布；审核与返修超限显式失败
- WebSocket `/ws`：`start`、`human_feedback`、`chat`、`ping`；断线取消、兄弟章节任务取消与清理
- 只读接口：报告、聊天历史、文档、任务/事件/日志、证据快照与证据页、附件与旧下载路径、就绪检查
- 写接口：报告、聊天、文档上传/删除/重索引、附件；报告发布在同一事务内保存图片、Markdown/PDF/DOCX、证据、事件与运行终态
- 报告追问：`POST /api/chat`，支持报告历史与最多 4 次 `quick_search`
- 导出：中文 PDF（内嵌 Noto Sans SC，修复 ToUnicode 兼容码位）与 DOCX（样式表、A4 页面设置），支持数据库图片附件
- 资料库：PDFBox/POI/Jsoup 解析 11 种扩展名，pgvector 检索，分批 staging + 原子索引切换
- 供应商适配：OpenAI 兼容 11 家 + Anthropic/Azure/Ollama/Groq/Mistral/xAI/Google GenAI、Cohere/Vertex/Bedrock 原生；21 家检索、6 类抓取、Google/ModelsLab 图片生成（协议级实现）
- MCP：stdio 与 Streamable HTTP，`fast`/`deep` 策略；stdio 命令与文件参数受白名单限制
- Flyway：显式 `migrate`，旧 Alembic 库需 `--baseline-existing` 且结构签名匹配，禁止启动时自动迁移与 `clean`
- 旧文件存储导入：CLI `import-legacy --root <dir> [--dry-run]`，幂等
- CLI：`research`、`migrate`、`reindex`、`import-legacy`

## 写入开关与默认行为

默认 `JAVA_WRITES_ENABLED=false`、`JAVA_DB_READ_ONLY=true`：写接口不注册，数据库会话只读。需要写入时必须显式设置：

```dotenv
JAVA_WRITES_ENABLED=true
JAVA_DB_READ_ONLY=false
```

Python 与 Java 不得同时写入同一应用库。切换前先停止 Python 写入并备份数据库；切换与回滚步骤见 [OPERATIONS.md](OPERATIONS.md)。

## 构建与运行

本机有 JDK 21 和 Maven 时，在 `backend-java/` 目录执行：

```powershell
mvn -B -ntp -s maven-settings.xml verify
```

可执行 JAR：

```powershell
java -jar target/researchrunner-api-0.1.0-SNAPSHOT.jar
```

CLI 示例（需通过环境变量提供数据库、模型、嵌入与搜索配置；不读取根目录 `.env`）：

```powershell
java -jar target/researchrunner-api-0.1.0-SNAPSHOT.jar research "研究主题" --report_source hybrid --no-pdf
java -jar target/researchrunner-api-0.1.0-SNAPSHOT.jar migrate
java -jar target/researchrunner-api-0.1.0-SNAPSHOT.jar migrate --baseline-existing
java -jar target/researchrunner-api-0.1.0-SNAPSHOT.jar reindex --dry-run
java -jar target/researchrunner-api-0.1.0-SNAPSHOT.jar import-legacy --root . --dry-run
```

## Docker 运行

从仓库根目录执行，先确保 Docker Desktop 引擎正常运行：

```powershell
docker compose ps -a
docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.java.yml --profile java config --quiet
docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.java.yml --profile java build java-api
docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.java.yml --profile java up -d --no-build --pull never java-api
```

Java 服务默认监听 `http://127.0.0.1:8001`；原前端仍使用 `http://localhost:3800`，Python API 仍在 8000。原数据库卷不变。该 Compose 文件是独立增量文件，普通 `docker compose up` 不会启用它。

| 变量 | 说明 |
| --- | --- |
| `JAVA_BACKEND_PORT` | Java 验证端口，默认 8001，仅绑定本机 |
| `JAVA_DATABASE_URL` | Java JDBC 连接串；Compose 根据现有 PostgreSQL 配置生成 |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | 沿用现有 `.env` 中的数据库配置 |
| `CORS_ALLOW_ORIGINS` | 沿用已配置的前端来源 |
| `DB_POOL_SIZE` | 本地 Java 启动时的连接池大小，默认 5 |
| `JAVA_WRITES_ENABLED` / `JAVA_DB_READ_ONLY` | 写入开关，默认关闭/只读 |
| `JAVA_REQUIRE_FLYWAY` / `JAVA_EXPECTED_MIGRATION` | 正式部署时要求 Java 迁移记录及版本 |
| `JAVA_SECTION_CONCURRENCY` | 并行章节数，默认 3 |
| `JAVA_VISUALIZATION_ENABLED` | 是否将受限 Mermaid 流程图渲染为 PNG 附件，默认 true |
| `JAVA_MCP_ALLOWED_COMMANDS` / `JAVA_MCP_ALLOWED_ROOTS` | MCP stdio 命令白名单与文件参数可访问目录 |

不要把 Python 的 `postgresql+asyncpg://` 地址传给 JDBC。不要将本地 `.env` 或临时工具链提交到版本库。

## 测试

默认测试（HTTP、错误处理、公共模式、研究图、导出等）：

```powershell
mvn -B -ntp -s maven-settings.xml verify
```

真实 PostgreSQL 集成测试需要显式配置测试数据库；宿主默认跳过。隔离回归脚本（需要 Docker）：

```powershell
./scripts/test_java_migration.ps1
./scripts/test_java_migration.ps1 -JavaOnly
```

基于本地 stub 供应商的全流程 E2E（不调用付费供应商）：

```powershell
./scripts/test_java_e2e_stub.ps1
```

真实供应商验收需人工提供凭据与预算，并使用 `scripts/test_java_live.ps1`；脚本会把每次尝试记录在 `backend-java/target/live-acceptance/live-runs.json`。

## 已知限制与差异

- 参数校验返回 422，但错误正文是简化的 `detail` 字符串，尚未完全复刻 FastAPI 的校验错误数组。
- 取消为协作式：无法保证所有供应商的 HTTP 请求立即中断；断线清理有超时告警。
- MCP 仅支持 stdio 与 Streamable HTTP；WebSocket 传输与 `fast` 之外的策略尚未迁移，文件/域名限定研究与 MCP 组合会明确报错。
- `browser` / `nodriver` 抓取、`gigachat`、部分原生 embedding 适配尚未迁移，入口返回显式 501，不会静默降级。
- 图片生成与长尾供应商目前为协议级实现，真实调用需在目标环境用凭据验证。
- 未内置用户认证：本服务按“运维者自托管”使用，不要直接暴露到公网。

版本状态、运行命令与切换约束以本文件及 [OPERATIONS.md](OPERATIONS.md) 为准；`fonts/README.md` 记录了报告字体的来源与码位处理方式。
