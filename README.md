# 知行研究助手（ResearchRunner）

知行研究助手（ResearchRunner）是一个开源的深度研究智能体：基于网络资料、本地文档或两者结合，通过多智能体工作流生成带引用的研究报告。仓库包含 Python 默认后端、可选的 Java 后端和 Next.js 前端，适合在本地或自己的服务器上部署。项目源自开源项目 [gpt-researcher](https://github.com/assafelovic/gpt-researcher)（MIT），在此基础上完成了中文化、单模式重构与 Java 后端实现。

## 主要功能

- 从网络、本地文档或混合数据源开展研究
- 统一通过 LangGraph 多智能体工作流生成研究报告
- 展示研究过程、来源、图片和最终报告
- 报告内证据编号与引用链接可追溯到证据快照
- 支持针对已有报告继续提问
- 支持 Markdown、PDF、Word 等报告导出方式
- 共享资料库：上传、选择本地文档，与网络资料联合研究（见 [SHARED_LIBRARY.md](SHARED_LIBRARY.md)）
- 前端界面和默认输出语言为简体中文

## 项目结构

```text
main.py                  FastAPI 应用入口
cli.py                   命令行研究入口
search/                  核心研究流程、检索器、抓取器、提示词与工具
backend/                 FastAPI 服务、聊天、PostgreSQL 持久化与报告导出
multi_agents/            LangGraph 多智能体研究实现
backend-java/            Spring Boot Java 后端（可选实现，默认只读）
frontend/nextjs/         Next.js 前端
alembic/                 PostgreSQL 数据库迁移
tests/                   Python 测试
deep_agents/             已归档的历史实验数据与结果
scripts/                 构建、迁移、验收与工具脚本
```

## 研究模式与来源

唯一公开报告模式是 `multi_agents`：初始研究 → 规划与人工审核 → 章节并行研究/审核/修订 → 写作 → 事实核查与章节返修 → 可视化 → 发布。旧的普通、详细、深度模式请求会自动按 `multi_agents` 执行；内部 `research_report`、`subtopic_report` 只是研究草稿格式，不是可选模式。

研究来源支持 `web`（仅网络）、`local`（仅资料库）和 `hybrid`（文件＋网络）。文件研究不联网；混合研究在一侧失败时给出可见告警并继续。一次运行会冻结资料范围，并在所有阶段共享同一套证据编号与引用链接。

### 研究来源的域名限制

`ResearchConductor._search_relevant_source_urls()` 会统一校验搜索器返回的 URL，仅接收 `query_domains` 指定的域名及其子域名。例如 `example.com` 允许 `news.example.com`，不允许 `fakeexample.com` 或 `example.com.evil.org`。此校验同时覆盖待抓取链接和搜索器直接返回正文的结果；未设置限制时保持原有行为。指定范围内没有可用来源时会记录警告并展示提示，不自动放宽范围。

Brave 和 Bing 的请求使用分组的 `site:` 条件，多域名之间使用 `OR`；DuckDuckGo 和 SearxNG 按去重后的域名分别发送 `site:` 查询，再交替合并结果、按 URL 去重并限制返回总数。无效的非空域名列表不会退回无范围限制的请求。

```powershell
python -m pytest tests/test_research_domain_filter.py tests/test_retriever_domain_requests.py -q
```

## 环境要求

- Python 3.11 或更高版本
- Node.js 18 或更高版本
- npm
- 可选：Conda、Docker Desktop
- 可选：JDK 21 + Maven（运行 Java 后端时）

## 本地启动

### 1. 创建 Python 环境

推荐使用 Conda 隔离项目依赖：

```powershell
conda create -n ai-search python=3.11 -y
conda activate ai-search
```

### 2. 安装后端依赖

在项目根目录执行：

```powershell
python -m pip install -r requirements.txt
```

依赖只需在首次创建环境或 `requirements.txt` 更新后重新安装，不需要每次启动都执行。

### 3. 配置环境变量

复制示例配置：

```powershell
Copy-Item .env.example .env
```

打开 `.env`，至少配置一个大语言模型密钥和一个搜索服务密钥。例如：

```dotenv
DEEPSEEK_API_KEY=你的密钥
TAVILY_API_KEY=你的密钥
DATABASE_URL=postgresql+asyncpg://gpt_researcher:gpt_researcher@localhost:5432/gpt_researcher
LANGUAGE=Chinese (Simplified)
```

请根据实际使用的模型和搜索服务填写对应配置，不要把包含真实密钥的 `.env` 提交到版本库。

服务端业务数据只保存在 PostgreSQL 中，并要求安装 `pgvector` 扩展。本地启动后端前先准备数据库并执行：

```powershell
python -m alembic upgrade head
```

数据库不可用、迁移版本落后或缺少 `pgvector` 时，`/health/ready` 返回 503，写接口不会回退到 `reports.json`、`outputs/`、`my-docs/` 或浏览器本地存储。

### 4. 启动后端

在项目根目录执行：

```powershell
python -m uvicorn main:app --reload
```

- 后端地址：<http://127.0.0.1:8000>
- 接口文档：<http://127.0.0.1:8000/docs>

根目录的 `main.py` 会加载 `.env`、初始化日志，并导出 `backend.server.app` 中的 FastAPI 应用。`--reload` 适合本地开发，代码变化后会自动重启服务。

也可以直接使用 CLI 执行一次研究（需要可访问的 PostgreSQL 配置）：

```powershell
python cli.py "研究主题" --report_source hybrid
```

### 5. 配置并启动前端

新开一个终端，进入前端目录：

```powershell
cd frontend/nextjs
```

创建 `frontend/nextjs/.env.local`：

```dotenv
NEXT_PUBLIC_GPTR_API_URL=http://127.0.0.1:8000
```

首次运行或前端依赖变化后安装依赖：

```powershell
npm install --legacy-peer-deps
```

启动前端：

```powershell
npm run dev
```

浏览器打开 <http://localhost:3000>。以后日常启动只需进入 `frontend/nextjs` 执行 `npm run dev`，不必重复安装依赖。

## Java 后端（可选）

`backend-java/` 是同一套公开 HTTP/WebSocket 契约、同一 PostgreSQL 结构的 Spring Boot 实现（Java 21 + Spring Boot 3.5.7 + Spring AI + LangGraph4j + MyBatis + Flyway），包含完整研究图、报告/聊天/证据/资料库接口、WebSocket 人工审核、CLI、MCP、文档索引与中文 PDF/DOCX 导出。

- 默认以只读方式运行；写入需显式设置 `JAVA_WRITES_ENABLED=true` 和 `JAVA_DB_READ_ONLY=false`。
- 默认部署入口仍是 Python 后端；两者不得同时写入同一应用库。
- 构建与测试：`cd backend-java; mvn -B -ntp -s maven-settings.xml verify`。
- 研究 CLI：`java -jar backend-java/target/researchrunner-api-0.1.0-SNAPSHOT.jar research "研究主题" --report_source hybrid`。
- Docker 验证 profile、维护命令（`migrate`、`reindex`、`import-legacy`）、配置项与已知限制见 [backend-java/README.md](backend-java/README.md)。

## Docker 启动

已安装 Docker Desktop 时，可在项目根目录执行：

```powershell
docker compose up --build
```

首次启动或镜像、依赖发生变化时需要构建；后续可使用 `docker compose up`。

Compose 会启动带 `pgvector` 的 PostgreSQL，并在后端启动前自动执行 Alembic 迁移。历史文件迁移、备份和嵌入模型切换步骤见 [DEPLOYMENT.md](DEPLOYMENT.md)。

## 测试与检查

后端及核心功能测试：

```powershell
python -m pytest tests/
```

聚焦多智能体、来源、持久化与审核回环的回归套件：

```powershell
python -m pytest tests/test_multi_agent_execution.py tests/test_shared_library.py tests/test_shared_research_flow.py tests/test_websocket_manager.py tests/backend/ tests/test_multi_agents_plan_revisions.py tests/test_multi_agents_draft_revisions.py tests/test_multi_agents_fact_revisions.py tests/test_none_accept_sentinels.py
```

真实 PostgreSQL + pgvector 集成测试可通过隔离的测试容器运行：

```powershell
docker compose --profile test run --rm gpt-researcher-tests
```

Java 后端测试：

```powershell
cd backend-java
mvn -B -ntp -s maven-settings.xml verify
```

前端检查与端到端测试：

```powershell
cd frontend/nextjs
npm run lint
npm run build
npm run test:e2e
```

## 常见问题

### 执行 `npm run dev` 时提示找不到 `package.json`

说明当前目录不正确。请先进入前端目录：

```powershell
cd frontend/nextjs
npm run dev
```

### 前端打开后无法连接后端

确认以下三项：

1. 后端已在 `http://127.0.0.1:8000` 运行。
2. `frontend/nextjs/.env.local` 中的 `NEXT_PUBLIC_GPTR_API_URL` 指向该地址（或指向 Java 后端地址）。
3. 修改 `.env.local` 后已经重新启动前端。

### 所有回答仍然是英文

确认根目录 `.env` 中包含：

```dotenv
LANGUAGE=Chinese (Simplified)
```

修改后重启后端。该配置控制模型生成内容的语言，前端中文界面本身不负责翻译模型回答。

## 许可证

本项目采用 MIT 许可证，详情见 [LICENSE](LICENSE)。
