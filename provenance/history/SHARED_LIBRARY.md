> 当前实现：仅保留 LangGraph 多智能体报告，支持网络、文件和混合资料来源。旧报告模式请求自动转换；内部草稿格式继续保留。

# 共享资料库与网络联合研究

## 使用步骤

1. 在桌面或移动端研究入口批量上传资料，等待状态变为 `ready`。文本型 PDF 保留页码，Word、TXT、Markdown 等已有格式保留片段定位。扫描 PDF 无可提取文本时失败，本轮不提供 OCR 或 EPUB。
2. 默认模式为“文件＋网络”，也可以选择“仅文件”“仅网络”。选择全部资料，或勾选已完成索引的指定文件，再输入研究问题。普通和详细报告均支持；多智能体只支持仅网络。
3. 全部资料在任务开始时固定为当时可检索的文档 ID。任务中途上传的资料不会加入；指定文件无效或模型不匹配会报错，不会扩大到整个资料库。
4. 点击网络来源引用直接打开原始网站；历史报告的网络证据链接会自动跳转原网站。上传文档和 MCP 来源仍打开证据快照页。PDF 可在原文件对应页查看，其他文件格式提供片段和下载入口。文件删除后历史证据仍保留，但原文件入口不可用。网络证据快照仍保存，可通过证据读取接口查询；原网站内容可能随时间变化。

同名且内容相同的上传复用原记录；同名内容不同则保留为不同文档。选择、下载、删除和重新索引均按文档 ID 操作。上传或索引失败的原因显示在资料管理区域；失败文件可重试，已有文件也可重新索引。

仅文件模式资料库为空时不能开始；混合模式选择全部资料且库为空时继续网络研究并显示提示。混合检索一侧失败或无结果时使用另一侧；两侧均无证据则停止。模型仍可能在相关资料中找不到问题的答案，应说明资料不足，系统不以检索命中冒充答案正确。

引用校验只确认编号和来源存在，不自动证明结论得到证据支持。未知编号和不属于本次证据的链接显示“引用不可核验”。详细报告各章节共享证据编号，历史读取已保存快照，不重新搜索。

## 请求与配置

REST `/report/` 和 WebSocket `start` 请求使用相同字段（沿用各自已有必填项）：

```json
{
  "task": "研究问题",
  "report_type": "multi_agents",
  "report_source": "hybrid",
  "document_scope": "selected",
  "document_ids": ["文档 UUID"],
  "query_domains": ["example.org"]
}
```

`document_scope` 为 `all` 或 `selected`。缺省按全部资料；旧请求保留原 `report_source`。数据库任务参数中的 `resolved_document_ids` 是实际固定范围。域名限制继续只作用于网络来源。

- `DOCUMENT_CHUNK_SIZE=1000`、`DOCUMENT_CHUNK_OVERLAP=100`。
- `DOCUMENT_SIMILARITY_THRESHOLD=0.35`、`DOCUMENT_RETRIEVAL_LIMIT=8`。
- `DOCUMENT_CONTEXT_CHAR_BUDGET=12000`、`WEB_CONTEXT_CHAR_BUDGET=12000`：分别限制进入生成上下文的证据正文字符数；缺少一类时另一类可使用其预算。标题和引用标签不计入正文预算。
- `PUBLIC_API_URL`：用户浏览器可访问的后端根地址，默认 `http://localhost:8000`；用于导出文件中的完整引用链接，线上部署必须正确配置。

新增读取接口：

- `GET /api/documents/{id}/content?download=false`：PDF 原文件浏览；其他格式仍下载。
- `GET /api/reports/{id}/evidence`、`GET /api/research-runs/{id}/evidence`：证据清单。
- `GET /api/research-runs/{id}/evidence/{number}`：读取单条证据快照；追加 `/view` 时，网络来源以 HTTP 302 跳转原网站（原网址无效时返回 422），文档和 MCP 来源显示证据页面。

报告兼容原有 Markdown 字段，历史报告对象增加 `evidenceUrl`。旧报告没有证据快照时返回空清单，不伪造历史页码。按文件名删除接口返回 410，请改用按 ID 删除接口。

## 升级与重新索引

先备份数据库，再在已经确认的目标环境执行 `alembic upgrade head`。空库按冻结的 `0001_postgres_persistence` 建表，再执行 `0002_shared_library`；已有 v1 数据库只执行新增迁移。就绪检查读取当前迁移 head，不再写死 v1。迁移不删除报告或文件。

升级后在文件列表对旧资料执行重新索引，使用数据库原文件，无需重新上传。未重索引的旧资料仍可检索，但没有可靠页码时只显示片段引用。更换 embedding 模型时遵循 [DEPLOYMENT.md](DEPLOYMENT.md) 的全量重新编码流程。

若存在同名不同内容文件，v2 降级会拒绝执行，避免丢失资料。回滚前应使用备份恢复策略，不自行删除记录。

## 定向验证

不启动服务，也不调用真实搜索、embedding 或大模型：

```bash
python -m pytest tests/test_shared_library.py tests/test_shared_research_flow.py tests/test_research_domain_filter.py tests/test_retriever_domain_requests.py -q
node tests/test_research_settings.cjs
node frontend/nextjs/node_modules/typescript/bin/tsc --noEmit --incremental false -p frontend/nextjs/tsconfig.json
```

Python 测试需要 pytest、SQLAlchemy；PDF 样本读取需要 pypdf（未安装会跳过样本检查）。HTTP 处理函数测试替换框架边界，数据存储测试检查 SQL 和事务调用，不等同于真实 HTTP/数据库集成。

`tests/fixtures/shared_library/` 包含两份双页文本型 PDF 和模拟网页。A 的第 2 页包含 ALPHA 的 17 天观察期，B 的第 2 页包含 BETA 的 83 单位阈值，网页提供 ALPHA 的 21 天后续观察信息。模拟测试覆盖仅文件命中、仅网页命中、两者合并、未选资料不能进入证据以及无可用证据。它们不评价真实模型回答质量。

以下集成验收仍须单独确认环境后执行，本轮没有实测：

1. 空 PostgreSQL 安装及旧库升级，确认数据保留、并发重复上传的真实唯一约束行为。
2. pgvector 真实过滤和相似度结果：选择 A 时 B 不出现在候选、提示词或引用中。
3. 使用生产解析依赖解析样本 PDF、在浏览器点击第 2 页、删除后查看历史快照，以及下载导出文件的引用链接。
4. REST/WebSocket 实际服务请求和桌面/移动端完整交互。
