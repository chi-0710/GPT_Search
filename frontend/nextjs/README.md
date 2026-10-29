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
```

生产环境启动前，请先执行：

```powershell
npm run build
npm run start
```

## 常见问题

如果出现 `Could not read package.json` 或 `ENOENT`，通常是因为在项目根目录执行了 npm 命令。请先进入 `frontend/nextjs` 再运行。

如果页面无法连接后端，请确认后端已经启动，并检查 `.env.local` 中的地址是否正确。
