# 知行研究助手（ResearchRunner）

知行研究助手基于网络资料、本地文档或混合来源生成带引用的中文研究报告，支持研究规划审核、历史记录、报告追问及文件导出。

当前后端采用 **Java 21 + Spring Boot**，前端采用 **Next.js + React + TypeScript**，业务数据与文档向量统一保存至 **PostgreSQL + pgvector**。旧 Python 实现已移除，构建和启动不再需要 Python。

## 主要功能

- `web`、`local`、`hybrid` 三种研究来源；唯一公开执行模式为 `multi_agents`。
- 初步研究 → 规划与人工审核 → 并行章节研究、审核和修订 → 写作 → 事实核查 → 可视化与发布。
- 共享证据编号、引用快照和资料范围约束。
- 文档上传、向量索引、检索、重新索引与原文件访问。
- 研究过程展示、历史报告、报告追问，以及 Markdown、PDF、DOCX 导出。
- HTTP、WebSocket 和 Java CLI 共用研究能力。

## 技术与目录

| 目录 | 用途 |
| --- | --- |
| `backend-java/` | Spring Boot API、Spring AI 模型调用、LangGraph4j 工作流、MyBatis 持久化与 Flyway 迁移 |
| `frontend/nextjs/` | 网页界面、研究过程展示及后端代理 |
| `scripts/` | Java 隔离回归与真实供应商验收脚本 |
| `tests/` | 保留的前端协议测试和测试夹具 |
| `deep_agents/` | 历史实验数据与来源记录 |

旧目录中可能仍保留文档、样式或历史资料，不再作为 Python 执行入口。

## 配置

推荐使用 Docker Desktop 的 Linux-container 模式。容器内使用 Java 21 和 Node.js 22，不要求本机安装 Java、Maven 或 Node.js。

首次安装可复制 `.env.example` 为根目录 `.env`；已有 `.env` 不要覆盖。示例文件仍含部分历史选项，当前生效的容器配置以 `docker-compose.yml` 为准。

在 `.env` 中明确设置以下内容：

| 配置 | 说明 |
| --- | --- |
| `FAST_LLM`、`SMART_LLM`、`STRATEGIC_LLM` | `供应商:模型`；本项目使用 DeepSeek，保留自己的模型名称 |
| `DEEPSEEK_API_KEY` | DeepSeek 密钥 |
| `EMBEDDING`、`EMBEDDING_DIMENSION` | DashScope/Qwen 嵌入模型和匹配的向量维度 |
| `DASHSCOPE_API_KEY` | DashScope 密钥 |
| `TAVILY_API_KEY` | 网络搜索密钥 |
| `POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD` | 数据库配置；已有库不要随意更换 |
| `FRONTEND_PORT` | 前端端口；Compose 默认 3002，本机配置为 3800 |
| `NEXT_PUBLIC_GPTR_API_URL` | 浏览器访问后端的地址，例如 `http://localhost:8000` |
| `PUBLIC_API_URL` | 导出文件与引用使用的公开后端地址，例如 `http://localhost:8000` |

Compose 使用服务名 `postgres` 生成 JDBC 数据库地址；前端服务端代理使用 `http://gpt-researcher:8000`。容器内部地址不能替代浏览器公开地址。不要提交 `.env` 或在日志中输出密钥。

## Docker 启动

在项目根目录运行：

```powershell
docker compose ps -a
docker compose config --quiet
docker compose up -d --build
docker compose ps -a
```

数据库健康后运行 Java 迁移服务，迁移成功后启动后端。`migration` 显示 `Exited (0)` 属于正常状态。

- 本机前端：[http://localhost:3800](http://localhost:3800)（其他环境按 `FRONTEND_PORT` 调整）。
- 后端就绪检查：[http://localhost:8000/health/ready](http://localhost:8000/health/ready)。

旧容器不会因源码删除而自动转换为 Java，需要重新构建并创建。后端源码修改后需重建镜像；前端开发容器使用目录挂载。

已有 Java 镜像且无需重建时：

```powershell
docker compose up -d --no-build --pull never
```

### 旧数据库首次接管

先备份数据库并验证恢复，停止业务写入，再构建 Java 镜像并显式执行旧库基线：

```powershell
docker compose stop gpt-researcher
docker compose build gpt-researcher
docker compose run --rm migration migrate --baseline-existing
docker compose up -d --no-build --pull never
```

空库和已由 Flyway 管理的库使用普通 `migrate`，默认启动流程会执行。旧库结构不匹配时应排查，不能绕过校验。

数据保存在 `postgres-data` 命名卷中；不要删除卷或执行 `docker compose down -v`。数据库卷本身不是备份。

## 常用命令

```powershell
# 查看日志
docker compose logs --tail 100 gpt-researcher
docker compose logs --tail 100 migration

# 停止服务，保留容器和数据
docker compose stop

# 运行 Java CLI 研究（使用 Compose 中的模型和数据库配置）
docker compose run --rm gpt-researcher research "研究主题" --report_source hybrid --no-pdf
```

CLI 无浏览器人工审核界面。单独执行 JAR 不自动读取根 `.env`，需要显式传入环境变量。维护命令和重建索引流程见 [Java 运维说明](backend-java/OPERATIONS.md)。

## 开发与测试

隔离回归需要 Docker 与 PowerShell，自动创建临时 PostgreSQL，运行 Java 测试并验证备份恢复，不调用真实付费模型：

```powershell
./scripts/test_java_migration.ps1
```

前端本地开发需要 Node.js 和 npm：

```powershell
cd frontend/nextjs
npm ci --legacy-peer-deps
npm run dev
```

本地开发时在 `frontend/nextjs/.env.local` 配置后端地址。前端检查：

```powershell
npm run lint
npx tsc --noEmit --incremental false
npm run build
```

真实供应商验收使用 `scripts/test_java_live.ps1`，会产生调用费用并累计记录预算，不自动重试。

## 当前边界

- 当前迁移范围是实际使用的 DeepSeek、DashScope/Qwen、Tavily 及研究产品闭环，不代表全部历史供应商均已迁移。
- 可视化支持受限流程图；不支持的供应商或 MCP 选项会明确报错。
- 审核预算分别由 `JAVA_SECTION_REVIEW_TOKEN_LIMIT` 和 `JAVA_FACT_CHECK_TOKEN_LIMIT` 控制；未设置时优先使用显式角色预算，否则默认 16000。截断输出会中止研究，不自动扩容重试。
- 自动化测试通过不代表真实研究全部通过。最新验证情况见 [切换验收记录](backend-java/CUTOVER_ACCEPTANCE.md)。

## 许可证与归属

见 [LICENSE](LICENSE) 和 [来源记录](provenance/README.md)。
