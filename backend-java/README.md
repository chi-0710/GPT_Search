# 知行研究助手 / ResearchRunner Java 后端

本目录是当前默认后端。技术栈：Java 21、Spring Boot 3.5.7、Spring AI 1.1.8、LangGraph4j 1.9.7、MyBatis、PostgreSQL/pgvector 和 Flyway。

旧 Python 实现已移除，根目录 Compose 通过本目录构建 `gpt-researcher` 服务，后端监听 8000。标准部署已显式启用写入和 Flyway 就绪校验；直接启动 JAR 时，应用仍默认只读，需要显式提供配置。

## 启动与配置

完整配置、容器启动、旧库基线、CLI 和前端说明见[根 README](../README.md)。根目录 `.env` 由 Compose 读取，JAR 不自动加载该文件。

## 核心代码

- `api/`：HTTP 与 WebSocket 接口。
- `research/`：研究编排、模型、搜索、证据、人工审核及取消处理。
- `document/`：文档解析、嵌入、向量检索和索引维护。
- `report/`：历史报告、聊天、附件与导出。
- `src/main/resources/db/migration/`：Java 数据库迁移。
- `src/test/java/`：单元、协议与数据库集成测试。

上述 Java 包位于 `src/main/java/io/researchrunner/` 下。

## 验证

在仓库根目录运行：

```powershell
./scripts/test_java_migration.ps1
```

脚本使用 Maven 与 pgvector 容器，仅初始化隔离数据库；不需要 Python 镜像，不调用真实模型。默认 CI 同时执行 Java 隔离回归与前端检查。

维护命令见 [OPERATIONS.md](OPERATIONS.md)，当前验收结果见 [CUTOVER_ACCEPTANCE.md](CUTOVER_ACCEPTANCE.md)。其他迁移清单包含历史阶段记录，不能据此判断当前入口仍为 Python，也不能将实现完成等同于真实供应商验收完成。
