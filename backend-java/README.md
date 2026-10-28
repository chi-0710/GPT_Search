# 知行研究助手 / ResearchRunner Java 后端

这是默认只读的渐进迁移后端，**不是完整的 Python 后端替代品**。当前保留原前端、Python 服务、数据库结构和 Alembic 迁移所有权。不要将前端入口切换到本服务。

## 全 Java 迁移进展

新增 Spring AI 1.1.8、LangGraph4j Core 1.9.7，保留 Boot 3.5.7；使用本项目的模型边界对接，
不引入面向 Spring AI 2.x 的 LangGraph4j Spring AI 集成模块。
研究图拓扑和实际节点动作已实现并经过编译执行测试，HTTP/WebSocket 入口及最终部署尚未接通。
节点包括初始证据研究、规划、人工审核、并行章节研究/审核/修订、汇总写作、基于证据的事实核查与章节返修。
可视化与发布通过运行时接口注入，实际图片生成、导出和入口装配仍未完成。

新增报告/聊天写接口及运行记录事务服务，默认不启用。只有显式设置
`JAVA_WRITES_ENABLED=true` 与 `JAVA_DB_READ_ONLY=false` 才能使用写接口；目前仅供隔离验收。
数据库会话默认只读，读取服务仍使用只读事务。

在仓库根目录执行隔离回归：

```powershell
./scripts/test_java_migration.ps1
```

脚本需要 Docker、现有 `gptresearcher/gpt-researcher` 镜像，以及可用的 Maven/pgvector 镜像。
所有数据使用独立临时 PostgreSQL；不调用真实模型，不修改应用数据库。
供应商端点配置不等同于每个供应商已完成真实验收。

## 已实现

- Java 21、Spring Boot 3.5.7、MyBatis 3.0.4、PostgreSQL JDBC。
- `GET /health/ready`：检查数据库、pgvector 和 `0002_shared_library` 版本，不修改数据库。
- `GET /api/reports`：完成状态筛选、指定 ID、分页、游标与聊天记录。
- `GET /api/reports/{id}`、`GET /api/reports/{id}/chat`：兼容历史公开 ID。
- `GET /api/documents`：文档元数据列表，不加载文件二进制。
- `GET /api/research-runs/{id}`、`/events`、`/log`：任务状态、分页事件和完整日志下载；支持任务 UUID 与历史报告公开 ID。
- `GET /api/reports/{id}/evidence`、`/api/research-runs/{id}/evidence`：引用快照与原文件可用状态。
- `GET /api/research-runs/{id}/evidence/{number}`、`/view`：证据详情、网页跳转、转义后的中文证据页面及 PDF 原文链接。
- `GET /api/artifacts/{id}`、`/report/{id}`、`/outputs/{*path}`：数据库附件与旧下载路径。
- `GET /api/documents/{id}/content`：原文件下载；只有扩展名和文件头均为 PDF 时允许内嵌查看。
- 兼容现有字段名、JSON 类型、毫秒时间戳及中文错误消息。
- 公共模式归一化与 `ResearchResult` Java 契约，尚未接入研究入口。
- 默认连接池与读取事务为只读；写能力通过显式开关启用，没有自动建表或迁移。

文档上传/删除/重新索引、兼容上传入口、附件写入及 pgvector 检索已实现，写开关默认关闭。
文档解析使用 PDFBox、Apache POI 和 Jsoup，支持原有 11 种扩展名；不包含 OCR。
文档索引有有界后台队列、重复任务去重、失败状态记录、向量维度校验与事务替换。
Tavily 搜索、HTML/PDF 抓取和三来源证据收集器已实现，尚未接到研究入口。
写开关下的 `/report/` 与 `/api/multi_agents` 已接通 Java 研究图、资料向量检索和三格式导出持久化。
本入口目前支持兼容模型和 Tavily；不支持的 MCP、远程文档、请求级模型覆盖和图片生成选项明确报错。
已有数据库图片附件可以嵌入 PDF/DOCX。WebSocket、聊天生成、CLI、其他供应商和最终部署验收仍未完成。
导出生成已有自动化内容测试，完整页面视觉验收仍待完成。
参数校验返回 422，错误正文目前是简化的 `detail` 字符串，尚未完全复刻 FastAPI 的校验错误数组。时间游标要求包含时区。证据网页跳转要求 Java URI 能解析出合法主机名，国际化域名应使用 Punycode。

## Docker 运行

从仓库根目录执行，先确保 Docker Desktop 引擎正常运行：

```powershell
docker compose ps -a
docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.java.yml --profile java config --quiet
docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.java.yml --profile java build java-api
docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.java.yml --profile java up -d --no-build --pull never java-api
```

Java 只读接口默认监听 `http://127.0.0.1:8001`，原前端仍使用 `http://localhost:3800`，Python API 仍在 8000。原数据库卷不变。配置文件为独立增量文件，普通 `docker compose up` 不会启用它。

| 变量 | 说明 |
| --- | --- |
| `JAVA_BACKEND_PORT` | 新增 Java 验证端口，默认 8001，仅绑定本机 |
| `JAVA_DATABASE_URL` | Java JDBC 连接串；Compose 根据现有 PostgreSQL 配置生成 |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | 沿用现有 `.env` 中的数据库配置 |
| `CORS_ALLOW_ORIGINS` | 沿用已配置的前端来源 |
| `DB_POOL_SIZE` | 本地 Java 启动时连接池大小，默认 5 |

不要把 Python 的 `postgresql+asyncpg://` 地址传给 JDBC。不要将本地 `.env` 或临时工具链提交到版本库。

## 测试

本机有 JDK 21 和 Maven 时：

```powershell
cd backend-java
mvn -B -ntp -s maven-settings.xml verify
```

默认运行 HTTP/错误处理/公共模式测试；真实 PostgreSQL 测试需要显式环境变量，默认跳过。Docker 镜像构建会执行默认测试。

完整数据库测试使用独立临时数据库，不挂载应用数据卷：

```powershell
docker compose -f docker-compose.yml -f docker-compose.java.yml --profile java-test build java-tests
docker compose -f docker-compose.yml -f docker-compose.java.yml --profile java-test up -d --no-build --pull never postgres-java-test
docker compose -f docker-compose.yml -f docker-compose.java.yml --profile java-test run --rm --no-deps java-tests
docker compose -f docker-compose.yml -f docker-compose.java.yml --profile java-test stop postgres-java-test
```

测试数据库使用 tmpfs，停止后测试数据可丢弃；测试脚本只接受以 `/researchrunner_java_test` 结尾的连接串和测试专用账号。测试夹具只是相关表的最小模型，不能替代后续完整 Alembic/Flyway 结构兼容测试。

2026-10-27 已在 Docker 中通过全部 37 项测试，包含 4 项真实 PostgreSQL 测试，无跳过项。另在独立 `researchrunner_parity_test` 数据库中运行原 Alembic 迁移并使用 Python 存储服务生成合成数据，18 次 Java/Python 读接口对照通过，覆盖 1 份中文报告、1 个文档、3 条证据、聊天、附件字节及任务日志。合成 PDF/DOCX 字节用于下载一致性验证，不用于渲染验证。

只读接口对照脚本：

```powershell
python scripts/verify_java_read_api.py --python-url http://127.0.0.1:8000 --java-url http://127.0.0.1:8001
```

上述命令在仓库根目录运行，仅查询数据，输出数量与差异字段，不输出报告正文。空库时只覆盖空结果和错误路径；不能宣称完成真实研究验证。`scripts/seed_java_parity.py` 仅允许写入专用隔离库，需要事先执行原 Alembic 建表；不要在应用库中建立测试样本。

## 下一步

下一阶段完成研究运行装配与接口，将节点动作连接到实际配置、模型、可视化、导出和持久化，并完成文档维护/批量重建工具。
继续逐项迁移供应商、WebSocket、聊天生成、导出及 CLI/MCP。
完整验收前保持应用数据库单一写入方，并保留默认 Python 入口。
