> 当前实现：仅保留 LangGraph 多智能体报告，支持网络、文件和混合资料来源。旧报告模式请求自动转换；内部草稿格式继续保留。

# 知行研究助手（ResearchRunner）前端

该目录包含 知行研究助手 的 Next.js 前端，同时可构建为 React 组件库。前端默认连接本地后端，并提供研究配置、进度展示、历史记录、报告查看和继续提问等功能。

## 环境要求

- Node.js 18 或更高版本
- npm
- 已启动的 知行研究助手 后端

## 配置

在当前目录创建 `.env.local`：

```dotenv
NEXT_PUBLIC_GPTR_API_URL=http://127.0.0.1:8000
```

修改环境变量后需要重新启动前端。

后端默认是 Python 服务（8000）。如改为连接 Java 后端（8001），把地址改为 `http://127.0.0.1:8001` 即可；Java 后端默认只读，写入需要显式开启写开关，见 `backend-java/README.md`。

## 安装与启动

所有 npm 命令都要在 `frontend/nextjs` 目录执行：

```powershell
cd frontend/nextjs
npm install --legacy-peer-deps
npm run dev
```

浏览器打开 <http://localhost:3000>。

`npm install` 只需在首次运行或依赖变化后执行；日常启动直接运行 `npm run dev`。

## 可用命令

```powershell
npm run dev        # 启动开发服务器
npm run build      # 构建生产版本
npm run start      # 启动已构建的生产版本
npm run lint       # 检查代码规范
npm run build:lib  # 构建 React 组件库
npm run test:e2e   # 运行 Playwright 只读 UI E2E
```

生产环境启动前，请先执行：

```powershell
npm run build
npm run start
```

## 只读 UI E2E 测试

`e2e/` 下是 Playwright 只读/UI 验收用例，覆盖：首页关键控件、来源切换（web/local/hybrid）、
规划阶段人工审核开关、资料库选择、研究运行中停止、人工反馈请求、报告证据弹窗、历史报告打开
与导出链接。所有 `/api/*` 请求都用 `page.route` 返回固定 fixture，**不会触发真实供应商调用或
长时间研究**。

- 复用本机系统 Chrome（`playwright.config.ts` 中 `channel: 'chrome'`），不需要下载 Playwright
  浏览器二进制。
- 默认访问 Docker 前端 `http://localhost:3800`；如需指向其它 dev server，用环境变量覆盖：

```powershell
npx playwright test
$env:PLAYWRIGHT_BASE_URL = "http://127.0.0.1:3900"; npx playwright test
```

- 单个用例文件：`e2e/researchrunner-ui.spec.ts`；共享 fixture：`e2e/fixtures.ts`。
- 测试前请确认目标前端已启动；测试本身不启动、不重启任何服务。

## 常见问题

如果出现 `Could not read package.json` 或 `ENOENT`，通常是因为在项目根目录执行了 npm 命令。请先进入 `frontend/nextjs` 再运行。

如果页面无法连接后端，请确认后端已经启动，并检查 `.env.local` 中的地址是否正确。
