# ResearchRunner repository guidelines

项目名称为知行研究助手 / ResearchRunner。后端在 backend-java/，使用 Java 21、Spring Boot、Spring AI、LangGraph4j、MyBatis 和 PostgreSQL/pgvector；前端在 frontend/nextjs/。不要恢复已删除的旧后端。

唯一公开研究模式为 multi_agents，来源为 web/local/hybrid。HTTP、WebSocket 和 CLI 共用研究服务。冻结所选文档范围；运行共享证据编号；无证据不发布报告；审核不通过应修订或明确失败。并行章节失败或取消时取消并等待兄弟任务。

默认部署使用根 Compose。保留现有服务名、镜像名、数据库和卷名及 NEXT_PUBLIC_GPTR_API_URL 等兼容标识。保留 DeepSeek、DashScope/Qwen、Tavily 配置，不打印或提交密钥。Java 修改需重建后端镜像。新运行设置必须显式映射到 Compose environment。

在启动服务前检查 docker compose ps -a，不重复启动本机服务。数据库只能有一套业务写入者；旧库基线必须显式执行，不自动放宽结构检查。禁止删除 postgres-data 或执行 down -v，除非用户明确要求删除数据。

验证：scripts/test_java_migration.ps1 运行隔离 Java 回归与备份恢复；前端运行 npm run lint、npx tsc --noEmit --incremental false 和 npm run build。测试不调用付费供应商。真实调用预算记录在 backend-java/acceptance/live-runs.json，不自动重试或重置。

保留历史研究、文档、附件和 deep_agents/ 实验数据。来源归属见 provenance/，保留 LICENSE 和第三方许可证。不要将上游地址用作本项目官网。

Java 使用四空格缩进，保持附近代码风格。TypeScript 使用严格模式与现有 ESLint/Prettier 规范。保持改动聚焦，不覆盖用户已有工作；文档必须区分代码实现、自动化验证和真实供应商验收。
