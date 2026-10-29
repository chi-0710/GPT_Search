# Java 部署

默认 Compose 使用 backend-java 构建后端，Java CLI migrate 执行数据库迁移；前端和 PostgreSQL 服务名称及卷保持不变。
启动、旧库基线和测试命令见 README.md，详细维护命令见 backend-java/OPERATIONS.md。
旧 Python 启动路径已移除。已有运行镜像不会因删除源码自动转换为 Java，需要 docker compose up -d --build。
真实验收状态见 backend-java/CUTOVER_ACCEPTANCE.md，不以源码删除替代运行验收。
