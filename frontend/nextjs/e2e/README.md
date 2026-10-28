# ResearchRunner Java 全流程 E2E（本地 stub，不产生真实供应商费用）

本目录包含两套 Playwright 用例：

| 文件 | 用途 | 是否需要后端 |
| --- | --- | --- |
| `researchrunner-ui.spec.ts` | 只读 UI 验收，所有 `/api/**` 用 `page.route` 模拟 | 否 |
| `full-flow.spec.ts` | **真实**全流程联调：真实 Next.js 前端 + 真实 Java 后端 + 真实 PostgreSQL/pgvector，仅模型/搜索/抓取由本地确定性 stub 提供 | 是 |

`full-flow.spec.ts` 不使用 `page.route` 伪造任何研究接口；WebSocket 也是真实连接
（只旁路监听帧用于断言，不替换实现）。

## 一键回归（推荐）

```powershell
# 在仓库根目录执行
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test_java_e2e_stub.ps1
```

脚本会自动完成：

1. 通过 `scripts/java_build.ps1 -DskipTests package` 构建 JAR（复用全局 Maven 串行锁）；
2. 启动 `scripts/stub_provider_server.py`：
   - `POST /v1/chat/completions`（OpenAI 兼容，含 `usage`）——按 system 指令内容返回
     planner/reviewer/fact_checker/reviser 的 JSON、writer/section/initial-research 的
     Markdown、visualizer 的 Mermaid、追问聊天的文本；
   - `POST /v1/embeddings`（按输入数量返回 1024 维确定性向量）；
   - `GET /search`（custom retriever，返回 `[{url,title,raw_content}]`）；
   - `POST /firecrawl/scrape`（返回 `{"success":true,"data":{"markdown":...}}`）；
3. 起一个**临时** pgvector 容器（自建网络、`tmpfs` 数据目录、独立库、host 端口 5434），
   不接触 docker compose 的应用库/卷/`.env`；
4. 对临时库执行 Java 迁移（`java -jar ... migrate`）；
5. 在 18003 启动 Java API（`JAVA_WRITES_ENABLED=true` + stub 供应商配置）；
6. 把 `frontend/nextjs` 复制到日志目录（独立的 `.next` 缓存，`node_modules` 用目录联接），
   在 3901 启动 `next dev`，`NEXT_PUBLIC_GPTR_API_URL`/`INTERNAL_API_URL` 指向 18003；
7. 运行 `npx playwright test full-flow`；
8. 收集日志与 stub 阶段计数，然后删除容器/网络/进程并检查端口、容器、网络无残留。

退出码即 Playwright 结果：`0` = 全绿。

### 常用参数

```powershell
# 复用已构建 JAR（改前端/用例时快速回归）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test_java_e2e_stub.ps1 -SkipBuild

# 保留环境排障（脚本会打印前端/Java/stub 端点与日志位置，需手动清理）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test_java_e2e_stub.ps1 -KeepEnvironment

# 只起环境、不跑 Playwright（配合下面的单用例调试）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test_java_e2e_stub.ps1 -SkipPlaywright -KeepEnvironment

# 只跑匹配的用例（Playwright -g），便于快速定位
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test_java_e2e_stub.ps1 -PlaywrightGrep '取消'

# 自定义端口（均需避开 Windows 保留端口段）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test_java_e2e_stub.ps1 `
  -StubPort 18090 -JavaPort 18003 -FrontendPort 3901 -DbPort 5434
```

## 日志与产物

每次运行写入 `backend-java/target/e2e-stub/<yyyyMMdd-HHmmss>/`：

| 路径 | 内容 |
| --- | --- |
| `summary.json` | 退出码、通过/失败数、各日志路径、stub 阶段计数、清理结果 |
| `logs/playwright.log` | Playwright 输出 |
| `logs/java.out.log` / `java.err.log` | Java 后端日志 |
| `logs/frontend.out.log` / `frontend.err.log` | Next.js dev 日志 |
| `logs/stub.jsonl` / `stub.out.log` | stub 每次请求的阶段/查询/耗时（JSONL） |
| `logs/java-build.log`, `logs/migrate.*.log` | 构建与迁移日志 |
| `playwright-results/` | 失败时的 trace/截图（`PLAYWRIGHT_OUTPUT_DIR`） |
| `frontend-copy/` | 隔离的前端源码副本（默认在清理时删除） |

## 用例覆盖

1. **web**：提交研究 → 报告标题/结论可见 → 证据弹窗（`/api/reports/{id}/evidence`）→
   引用链接（`网页 N：…`）→ 追问聊天有回复 → 从 WebSocket `path` 帧取到的
   MD/PDF/DOCX 导出全部返回 200（并校验 content-type）；
2. **取消**：启动后点击“停止研究” → 无错误帧/错误文案、UI 显示“研究已停止”、
   后端 `GET /api/research-runs/{run_id}` 状态为 `cancelled`；
3. **local**：通过 API 上传中文 fixture → 轮询 `ready` → UI 选择“仅文件 + 指定文件” →
   报告含 `资料 N：<文件名>` 引用，证据类型为 `document`，证据查看页 200；
4. **hybrid 冒烟**：web stub + 本地资料，证据同时包含 `web` 与 `document`；
5. **后端契约**：`POST /report/` + `follow_guidelines=true`，断言 planner/writer/fact_checker
   以及 `section_reviewer → section_reviser {"draft","revision_notes"}` JSON 协议被真实执行
   （stub 默认 `STUB_REJECT_ONCE=1`，每个研究问题首轮审核会拒绝一次以触发修订）。

## stub 协议说明

- `section_reviser` 当前 Java 协议已是 JSON：`{"draft": "...", "revision_notes": "..."}`；
  stub 同时保留旧协议兼容分支（指令中出现“不要 JSON 包装”时返回纯 Markdown），
  以适配 task-7 收口前后的中间状态。
- `fact_reviser` 仍为纯 Markdown 协议。
- 审核/事实核查返回 `{"accepted": bool, "feedback": "..."}`；planner 返回
  `{"title","date","sections"}`；writer 返回完整 Markdown 报告。
- 环境变量：`STUB_REJECT_ONCE`、`STUB_REJECT_FACT`（首轮拒绝一次以覆盖修订路径）、
  `STUB_SLOW_MS`（含 `E2E-SLOW` 的研究问题每次模型调用增加延迟，用于取消用例）。

## 只跑其中一部分

```powershell
# 需要自己先准备好 stub + Java + 前端，并设置三个地址
$env:PLAYWRIGHT_BASE_URL = 'http://127.0.0.1:3901'
$env:E2E_API_BASE_URL = 'http://127.0.0.1:18003'
$env:E2E_STUB_BASE_URL = 'http://127.0.0.1:18090'
$env:PLAYWRIGHT_OUTPUT_DIR = 'F:\tmp\e2e-results'
cd frontend/nextjs
npx playwright test full-flow --workers=1
```

只跑单个用例：`npx playwright test full-flow -g "取消"`。

## 已知限制

- 依赖 Docker Desktop（Linux 容器模式）与本机 Chrome（`channel: 'chrome'`，不下载浏览器）。
- 依赖 `frontend/nextjs/node_modules` 已安装；脚本不会执行 `npm install`。
- 迁移/构建失败时脚本直接退出，不会静默回退到真实供应商。
- stub 的输出是确定性文本，不是对真实模型质量的评估；它验证的是前后端全链路、
  WebSocket 事件、证据/引用、导出与取消语义。
