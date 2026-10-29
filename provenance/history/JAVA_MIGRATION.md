# ResearchRunner Java 迁移跟踪

## 迁移原则

- 保留知行研究助手 / ResearchRunner 身份和兼容用的 Compose、数据库、环境变量名称。
- 逐步迁移，公共研究模式始终为 `multi_agents`，不得在执行失败时自动切换实现。
- 不覆盖现有工作区改动；不删除 Python 实现、旧报告、附件或历史实验数据。
- 数据库结构仍由 Alembic 管理。Flyway 只有在基线、空库初始化和回滚兼容验证完成后才能接管。
- 在 Java 全部公开契约验收前，前端继续连接 Python 8000 端口。

## 2026-10-27 第一批实现

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| 接口与模型盘点 | 部分完成 | 已对照报告、聊天、文档和就绪检查；全量契约清单待补齐 |
| Java 工程 | 编译及应用启动测试通过 | Java 21 / Spring Boot / MyBatis，独立 Docker 构建配置 |
| 只读数据访问 | 真实 PostgreSQL 验证通过 | 不修改应用库结构或数据 |
| API | 只读接口阶段已实现 | 报告、聊天历史、文档、任务/事件/日志、证据、已有附件下载、就绪检查 |
| 模式与结果契约 | 已实现 | 保持旧模式别名规则；执行入口尚未迁移 |
| 引用、文件下载 | 已实现并验证 | 证据快照、中文页面、附件与原文件下载；不含生成新导出文件 |
| 数据写入 | 待实现 | 明确单一写入方后逐步接管 |
| 文档索引、检索与证据 | 待实现 | 冻结范围、共享注册、来源隔离 |
| 编译后的研究图 | 待实现 | 先验证章节循环、人工审核与取消 |
| 聊天生成、导出、MCP、CLI | 待实现 | 未完成前不能撤销 Python |
| Flyway 接管与最终切换 | 待实现 | 必须先备份并恢复验证 |

## 第一批历史验证记录

- 原工作区已有大量修改，本批主要新增 `backend-java/` 和 `docker-compose.java.yml`。
- 2026-10-27 检查时 Docker 引擎未运行；启动 Docker Desktop 后，其内部 Inference manager 报本地 socket 文件访问错误。未执行重置或删除卷。
- 增量 Compose 配置语法检查通过。
- 未切换现有应用入口，未对应用数据库执行写入或迁移。
- Java 主代码和测试代码编译通过。JUnit 共 28 项：25 项通过，3 项真实 PostgreSQL 测试因未配置测试数据库而跳过，无失败。
- Maven `verify` 最终返回 `BUILD SUCCESS`，可执行 Spring Boot JAR 打包成功。
- 已实际启动 Spring Boot 内嵌 HTTP 服务进行接口测试，数据库 mapper 和事务管理器使用模拟实现；不能据此认定真实数据库可用。
- MyBatis 动态 SQL 解析、参数绑定、HTTP 查询/错误处理/参数校验、模式别名规则通过。
- 真实数据库、Docker 镜像构建与真实模型研究验证尚未完成。

## 2026-10-27 第二批验收

- Docker 已恢复，原网站 3800、Python 后端 8000、应用 PostgreSQL 正常运行。上面的 Docker 故障是第一批历史情况。
- Java 服务及测试镜像构建成功；Java 在 8001 端口提供只读接口，前端仍连接 Python。
- Docker 内全部 37 项测试通过，0 失败、0 错误、0 跳过，包括 4 项真实 PostgreSQL 测试。
- 修复真实验证发现的 UUID 参数绑定、中文 HTML 编码、SQL NULL/JSON null 聊天元数据兼容问题；已有回归覆盖。
- 独立临时数据库使用原 Alembic 脚本从空库升级到 `0002_shared_library`，通过 Python 存储层写入合成中文报告、聊天、文档、证据和附件。
- 18 次 Python/Java 读接口对照全部一致（规范化等价 ISO 时间格式后），覆盖 1 份报告、1 个文档、3 条证据及任务日志、附件字节。现有应用库为空，另做空结果/错误路径检查。
- 合成测试库位于临时 PostgreSQL 容器，未写入应用数据库，没有调用收费模型或搜索。
- 全流程研究、模型调用、文档索引、新 PDF/DOCX 生成及真实渲染仍未迁移或验证。不能称为完整 Java 迁移或研究端到端验收。

运行方法、已迁移端点、已知兼容差异和测试说明见 [backend-java/README.md](backend-java/README.md)。

## 2026-10-27 Python 冗余导出清理

删除前检查了运行代码、测试、启动配置和文档中的引用关系：

| 删除项 | 原依赖关系 | 处理方式 |
| --- | --- | --- |
| `backend/utils.py` | 只有旧文件名测试调用；HTTP/CLI 已使用 artifact 服务 | 删除旧本地 MD/PDF/DOCX 导出实现，将测试迁到实际持久化导出路径 |
| `multi_agents/agents/utils/file_formats.py` | 只有 Publisher 的旧本地导出分支调用 | 删除该分支与重复导出模块，Publisher 仅组装 Markdown |
| `tests/backend/test_write_md_to_pdf_filename.py` | 测试已删除的 helper | 替换为 `test_artifact_exports.py`，验证文件名、路径、临时文件清理、失败后保留 Markdown 和 Publisher 不重复导出 |

`task.json` 及工作流文档已删除本地 `publish_formats` 配置；入口仍忽略历史传入的该字段。
实际导出统一由 `backend/server/artifact_service.py` 负责，并补齐空文件名回退。

保留依赖：HTTP/WebSocket/CLI → `multi_agent_runner` → `multi_agents` / `search`；
报告保存与导出 → `postgres_store` / `artifact_service`。前端仍连接 Python，Java 只读接口存在并不表示这些 Python 路由已无调用。
`backend/styles/pdf_styles.css`、文档解析和导出依赖、数据库迁移及历史实验数据均保留。

清理后在一次性 Python 容器中运行聚焦回归：124 passed、4 skipped。
跳过项为未启用的 PostgreSQL 集成测试；模型/搜索使用模拟边界，外部 tracing 已关闭。
本次导出测试使用模拟 PDF 渲染器，不代表真实 PDF 排版验收。
