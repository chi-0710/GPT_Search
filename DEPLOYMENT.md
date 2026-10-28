# Deployment Guide

This repository runs a FastAPI backend, a Next.js frontend and PostgreSQL with
pgvector. The backend performs research and exposes REST and WebSocket APIs;
the frontend connects to it through `NEXT_PUBLIC_GPTR_API_URL`.

The local Python package is now `search`: use
`from search import Research` in local integrations. The distribution name
`gpt-researcher`, Docker services and database identifiers are unchanged.
The core class and React component are named `Research` (props: `ResearchProps`).
After updating from the former package directory, rebuild the backend image with
`docker compose up --build -d gpt-researcher` after Docker Desktop is running.
The development override mounts `./search` at `/usr/src/app/search`.

The frontend's `/outputs/*` proxy selects `INTERNAL_API_URL`, then
`NEXT_PUBLIC_GPTR_API_URL`, then the legacy `NEXT_PUBLIC_BACKEND_URL`, and finally
`http://localhost:8000`. With separate Compose containers, use
`INTERNAL_API_URL=http://gpt-researcher:8000`; browser URLs and `PUBLIC_API_URL`
must remain browser-accessible addresses. Production rewrites are generated at
build time: supply the target address when building and rebuild when it changes.

WebSocket research can be stopped without reloading the page; displayed partial
results remain visible. Disconnecting cancels the server task and records the
interrupted run using the existing failure status. The independently hosted
LangGraph streaming option retains its existing page-reload behavior; stopping
that remote job is not handled by the WebSocket cancellation path.

Docker build contexts exclude local test caches, issue-triage artifacts, logs,
cleanup backups and prebuilt React component bundles. These files remain on the
host. Research documents, report files, tests and database migrations remain
available to the existing build and migration workflows.

## Prerequisites

On Windows, if Docker cannot bind frontend port 3002 because it is reserved or
occupied, set `FRONTEND_PORT=3800` (or another available port) in the root `.env`.
The default CORS origins follow this port; if `CORS_ALLOW_ORIGINS` is explicitly
set, update that list as well. Open `http://localhost:3800` after applying the
change with `docker compose up -d --no-build --pull never` when images already
exist. Backend URLs remain on port 8000. Do not remove database volumes to fix a
port binding error.

- Docker and Docker Compose
- At least 3 GB RAM and 1 CPU for light workloads
- An LLM API key and a search-provider API key

Copy `.env.example` to `.env` and fill in only the providers you use. Never
commit `.env` or production secrets.

At minimum, the default OpenAI and Tavily configuration requires:

```dotenv
OPENAI_API_KEY=
TAVILY_API_KEY=
DATABASE_URL=postgresql+asyncpg://gpt_researcher:gpt_researcher@localhost:5432/gpt_researcher
NEXT_PUBLIC_GPTR_API_URL=http://127.0.0.1:8000
```

Model selection can be changed with `FAST_LLM`, `SMART_LLM`, `STRATEGIC_LLM`
and `EMBEDDING`. See `.env.example` for the supported project settings.

## Docker Compose

From the repository root, run:

```bash
docker compose up --build -d
```

The current Compose configuration exposes:

- Frontend: `http://localhost:3002`
- Backend: `http://localhost:8000`
- Backend health check: `http://localhost:8000/`
- Database readiness check: `http://localhost:8000/health/ready`
- Generated OpenAPI UI: `http://localhost:8000/docs`

View and stop the services with:

```bash
docker compose logs -f
docker compose down
```

Schema changes run in the one-shot `migration` service before the backend is
started. To run them manually, use `docker compose run --rm migration`.

The `gpt-researcher-tests` service is optional and runs only through the test
profile:

```bash
docker compose --profile test run --rm gpt-researcher-tests
```

## Production Image

`Dockerfile.fullstack` builds the frontend and backend into one image managed by
Supervisor and Nginx:

```bash
docker build -f Dockerfile.fullstack -t gpt-researcher:local .
docker run -d --name gpt-researcher \
  --env-file .env \
  -p 3000:3000 \
  -p 8000:8000 \
  gpt-researcher:local
```

This image does not contain PostgreSQL. Point `DATABASE_URL` at a reachable
external pgvector database and run `alembic upgrade head` against that database
before starting the image.

For a public deployment, terminate TLS at a reverse proxy or load balancer.
Forward frontend traffic to port 3000, backend REST traffic to port 8000, and
preserve WebSocket upgrade headers for `/ws`.

## Persistent Data

All reports, runs, events, chats, uploads, document vectors and generated
artifacts are stored in PostgreSQL. Operational logs are written to stdout.
Create a logical backup and verify it in a separate database regularly:

```bash
docker compose exec -T postgres pg_dump -U gpt_researcher -d gpt_researcher -Fc > gpt-researcher.dump
docker compose exec -T postgres createdb -U gpt_researcher gpt_researcher_restore_check
docker compose exec -T postgres pg_restore -U gpt_researcher -d gpt_researcher_restore_check < gpt-researcher.dump
docker compose exec -T postgres psql -U gpt_researcher -d gpt_researcher_restore_check -c "SELECT count(*) FROM reports"
docker compose exec -T postgres dropdb -U gpt_researcher gpt_researcher_restore_check
```

For an existing file-backed installation, stop application writes and run:

```bash
python scripts/migrate_legacy_storage.py --dry-run
python scripts/migrate_legacy_storage.py
```

The importer is idempotent and does not delete `data/reports.json`, `outputs/`
or `my-docs/`. Keep those directories as read-only backups until a PostgreSQL
restore has been verified.

When changing `EMBEDDING` or `EMBEDDING_DIMENSION`, stop application writes,
preview and run the full re-embedding command, then restart the backend:

```bash
python scripts/reembed_documents.py --dry-run
python scripts/reembed_documents.py
```

## Upgrade and Rollback

The shared-library release adds migration `0002_shared_library`. Both fresh
installations and upgrades must run `alembic upgrade head` before backend
startup (the Compose migration service already does this). No reports or
uploads are removed. Reindex existing documents from the library UI to obtain
PDF page metadata; older indexes remain searchable with excerpt-only citations.
Set `PUBLIC_API_URL` to the browser-accessible backend URL for exported evidence
links. See [Shared library usage and acceptance checks](SHARED_LIBRARY.md).

1. Take and verify a PostgreSQL backup.
2. Pull or check out the intended release.
3. Rebuild with `docker compose build --pull`.
4. Start with `docker compose up -d`.
5. Verify `/health/ready`, the frontend, a WebSocket research run, Local/Hybrid retrieval and report export.

To roll back, check out the previous known-good release, rebuild the containers,
and restore the PostgreSQL backup if the schema or application data changed.

## Troubleshooting

研究流程中的固定进度、状态和错误文案使用简体中文。前端将研究状态字段和报告结构 JSON 显示为中文标签，原始事件标识和数据键保持不变。外部来源、工具名称及第三方错误详情可能仍为英文；历史研究日志不会回写。后端文案修改后需要重新构建镜像，普通重启不会加载宿主机上的新代码。

- Backend logs: `docker compose logs -f gpt-researcher`
- Frontend logs: `docker compose logs -f gptr-nextjs`
- Confirm configuration: `docker compose config`
- Confirm backend status: `curl http://127.0.0.1:8000/`
- If the frontend cannot connect, verify `NEXT_PUBLIC_GPTR_API_URL` is reachable
  from the user's browser, not only from inside the container network.

## 多智能体唯一执行模式

新研究统一保存为 `multi_agents`。HTTP、WebSocket 与 CLI 的旧模式请求在创建运行前自动转换，未知模式返回错误。历史数据无需迁移。
网络、文件、混合研究均通过同一多智能体工作流；文件范围在启动时冻结，证据快照与最终报告统一存入 PostgreSQL。
CLI 也需要 PostgreSQL 连接；保留现有数据库、卷名及模型供应商配置。
服务端不再回退 AG2，前端不再使用独立的 LangGraph 主机地址。删除的独立深度研究配置不再生效；MCP 的 `deep` 策略仍保留。
升级后按现有流程重建应用服务，验证文件研究、报告保存与导出；不需要删除数据库卷。

## Java 后端（可选）

`backend-java/` 是与 Python 后端共用同一数据库结构和 HTTP/WebSocket 契约的 Spring Boot 实现，包含完整研究图、读写接口、WebSocket 人工审核、聊天、CLI、MCP、文档索引与中文 PDF/DOCX 导出。
使用额外的 `docker-compose.java.yml` 和 `java` profile 显式启用，默认本机端口为 8001。
原前端 3800、Python API 8000、PostgreSQL 数据卷与 Alembic 迁移保持不变。
Java 默认只读运行；写入需显式设置 `JAVA_WRITES_ENABLED=true` 和 `JAVA_DB_READ_ONLY=false`，并且同一时间只允许一个后端写入应用库。
切换与回滚步骤见 [Java 运维说明](backend-java/OPERATIONS.md)，配置、构建和隔离数据库测试命令见 [Java 后端说明](backend-java/README.md)。
