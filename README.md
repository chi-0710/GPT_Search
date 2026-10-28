# 知行研究助手（ResearchRunner）

知行研究助手 是一个开源的深度研究智能体，可基于网络资料、本地文档或两者结合生成带引用的研究报告。本仓库包含 Python 后端和 Next.js 前端，适合在本地或自己的服务器上部署。

## 主要功能

- 从网络、本地文档或混合数据源开展研究
- 统一通过多智能体工作流生成研究报告
- 展示研究过程、来源、图片和最终报告
- 支持针对已有报告继续提问
- 支持 PDF、Word 等报告导出方式
- 前端界面和默认输出语言已配置为简体中文

### 研究来源的域名限制

`ResearchConductor._search_relevant_source_urls()` 会统一校验搜索器返回的 URL，
仅接收 `query_domains` 指定的域名及其子域名。例如 `example.com` 允许
`news.example.com`，不允许 `fakeexample.com` 或 `example.com.evil.org`。
此校验同时覆盖待抓取链接和搜索器直接返回正文的结果；未设置限制时保持原有行为。
指定范围内没有可用来源时会记录警告，并在启用研究过程输出时展示提示，不自动放宽范围。
这是搜索结果接收阶段的校验，不负责抓取后的重定向检查，也不替代其他研究入口的过滤。

Brave 和 Bing 的请求使用分组的 `site:` 条件，多域名之间使用 `OR`；
DuckDuckGo 和 SearxNG 按去重后的域名分别发送 `site:` 查询，再交替合并各域名结果、
按 URL 去重并限制返回总数。因此后两者设置 N 个不同域名时会发出 N 次请求。
无效的非空域名列表不会退回无范围限制的请求。域名大小写、URL 形式和国际化域名
使用与结果校验相同的规范化规则。

查询语法参考：[Brave API](https://api-dashboard.search.brave.com/documentation/resources/search-operators)、
[Bing](https://support.microsoft.com/en-us/bing/advanced-search-options)、
[DuckDuckGo](https://duckduckgo.com/duckduckgo-help-pages/results/syntax)。
[SearxNG](https://docs.searxng.org/user/search-syntax.html) 将普通查询内容交给底层引擎，
实际语法支持取决于所选引擎，因此仍保留接收阶段的统一校验。

只验证该函数，无需启动服务或调用外部搜索 API：

```powershell
python -m pytest tests/test_research_domain_filter.py -q
```

同时验证上述四个搜索器的请求构造和域名过滤：

```powershell
python -m pytest tests/test_retriever_domain_requests.py tests/test_research_domain_filter.py -q
```

## 项目结构

```text
search/       核心研究流程、检索器、抓取器和提示词
backend/              FastAPI 服务及报告、聊天相关功能
frontend/nextjs/      Next.js 前端
multi_agents/         多智能体研究实现
deep_agents/          已归档的历史实验数据与结果
tests/                后端、核心模块和技能测试
main.py               后端启动入口
```

## 环境要求

- Python 3.11 或更高版本
- Node.js 18 或更高版本
- npm
- 可选：Conda、Docker Desktop

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
OPENAI_API_KEY=你的密钥
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

真实 PostgreSQL + pgvector 集成测试可通过隔离的测试容器运行：

```powershell
docker compose --profile test run --rm gpt-researcher-tests
```

前端检查：

```powershell
cd frontend/nextjs
npm run lint
npm run build
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
2. `frontend/nextjs/.env.local` 中的 `NEXT_PUBLIC_GPTR_API_URL` 指向该地址。
3. 修改 `.env.local` 后已经重新启动前端。

### 所有回答仍然是英文

确认根目录 `.env` 中包含：

```dotenv
LANGUAGE=Chinese (Simplified)
```

修改后重启后端。该配置控制模型生成内容的语言，前端中文界面本身不负责翻译模型回答。

## 许可证

本项目采用 MIT 许可证，详情见 [LICENSE](LICENSE)。
# 共享资料库与网络联合研究

支持从共享资料库选择文件，与网络资料联合生成多智能体报告。桌面和移动端均可上传、选择资料，并通过报告引用查看证据快照及 PDF 页码。使用步骤、升级与重新索引、配置和定向测试见 [共享资料库说明](SHARED_LIBRARY.md)。

### 唯一报告模式

知行研究助手（ResearchRunner）统一使用 LangGraph 多智能体流程：初步研究 → 规划与人工审核 → 章节并行研究与审核 → 写作 → 事实核查 → 可视化 → 发布。
支持仅网络、仅文件、文件＋网络；各阶段共用同一运行的文件范围和证据编号。
旧的普通、详细、深度模式请求会自动按 `multi_agents` 执行，耗时和费用可能与过去不同。历史报告不改写。
内部 `research_report`、`subtopic_report` 是研究草稿格式，不是可选报告模式。
CLI 同样通过 PostgreSQL 资料库与导出服务运行：`python cli.py "研究主题" --report_source hybrid`；需要可访问的数据库配置。
AG2 和独立深度智能体执行实现已移除，历史实验资料保留。
