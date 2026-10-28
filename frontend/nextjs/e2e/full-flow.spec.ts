import { test, expect, type APIRequestContext, type Page } from '@playwright/test';

/**
 * Real, end-to-end full-flow tests for the ResearchRunner Java backend + Next.js frontend.
 *
 * These tests deliberately do NOT mock `/api/*` or the WebSocket: they drive a real
 * `next dev` frontend, a real Java backend and a local deterministic provider stub
 * (scripts/stub_provider_server.py) plus a throw-away PostgreSQL/pgvector container.
 * No paid provider is contacted; see scripts/test_java_e2e_stub.ps1.
 *
 * Covered flows:
 *   1. web research -> report body/citations/evidence modal -> follow-up chat -> MD/PDF/DOCX
 *   2. cancellation (stop button) -> no error state, backend run becomes `cancelled`
 *   3. local research over an uploaded Chinese fixture document -> citations
 *   4. hybrid smoke (web stub + uploaded document, evidence from both sources)
 *   5. backend HTTP contract run with follow_guidelines=true, exercising the
 *      section_reviewer -> section_reviser {"draft","revision_notes"} JSON protocol.
 */

const API_BASE = (process.env.E2E_API_BASE_URL ?? 'http://127.0.0.1:18003').replace(/\/+$/, '');
const STUB_BASE = (process.env.E2E_STUB_BASE_URL ?? 'http://127.0.0.1:18090').replace(/\/+$/, '');

const REPORT_TITLE = 'E2E-STUB-研究标题';
const REPORT_CONCLUSION = 'E2E-STUB-报告结论';
const CHAT_MARKER = 'E2E-STUB-聊天回复';

const FLOW_TIMEOUT = 240_000;

interface CapturedFrames {
  researchStarted?: Record<string, any>;
  reportComplete?: Record<string, any>;
  path?: Record<string, any>;
  errorFrames: Record<string, any>[];
}

/** Capture backend WebSocket frames without replacing the socket. */
function captureFrames(page: Page): CapturedFrames {
  const captured: CapturedFrames = { errorFrames: [] };
  page.on('websocket', (socket) => {
    socket.on('framereceived', (frame) => {
      const payload = String(frame.payload);
      if (!payload.startsWith('{')) return;
      let data: Record<string, any>;
      try {
        data = JSON.parse(payload);
      } catch {
        return;
      }
      if (data.type === 'research_started') captured.researchStarted = data;
      else if (data.type === 'report_complete') captured.reportComplete = data;
      else if (data.type === 'path') captured.path = data;
      else if (data.type === 'error') captured.errorFrames.push(data);
    });
  });
  return captured;
}

async function openHome(page: Page): Promise<void> {
  // `GPTR_API_URL` is the documented runtime override read by helpers/getHost.ts.  It keeps
  // the real backend address authoritative even when a stale `.next` dev cache was compiled
  // with a different NEXT_PUBLIC_GPTR_API_URL.
  await page.goto(`/?GPTR_API_URL=${encodeURIComponent(API_BASE)}`);
  await expect(page.getByRole('heading', { name: '你接下来想研究什么？' })).toBeVisible();
}

async function configureSource(page: Page, source: 'web' | 'local' | 'hybrid'): Promise<void> {
  await page.getByRole('button', { name: '偏好设置' }).click();
  await expect(page.getByText('多智能体研究')).toBeVisible();
  const select = page.locator('select[name="report_source"]');
  await select.selectOption(source);
  await expect(select).toHaveValue(source);
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await expect(page.getByText('多智能体研究')).toBeHidden();
}

function librarySection(page: Page) {
  return page.locator('section').filter({ hasText: '我的资料库' }).first();
}

async function openLibrary(page: Page): Promise<void> {
  const toggle = page.getByRole('button', { name: /我的资料库/ });
  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
}

async function submitResearch(page: Page, question: string): Promise<void> {
  await page.locator('textarea').first().fill(question);
  await page.getByRole('button', { name: '开始研究' }).click();
  await expect(page.getByRole('button', { name: '停止研究' })).toBeVisible();
}

async function waitForCompletedReport(page: Page, captured: CapturedFrames): Promise<void> {
  await expect(page.getByRole('heading', { name: REPORT_TITLE })).toBeVisible({ timeout: FLOW_TIMEOUT });
  await expect(page.getByText(REPORT_CONCLUSION).first()).toBeVisible({ timeout: 30_000 });
  await expect.poll(() => captured.path, { timeout: 60_000 }).toBeTruthy();
  await expect(page.getByRole('button', { name: '停止研究' })).toBeHidden();
}

async function expectNoResearchError(page: Page, captured: CapturedFrames): Promise<void> {
  expect(captured.errorFrames, `unexpected error frames: ${JSON.stringify(captured.errorFrames)}`).toHaveLength(0);
  await expect(page.getByText(/Java 研究失败|请求无效或当前功能尚未接入/)).toHaveCount(0);
}

async function expectRunStatus(request: APIRequestContext, runId: string, status: string): Promise<void> {
  const response = await request.get(`${API_BASE}/api/research-runs/${runId}`);
  expect(response.ok(), `run ${runId} status HTTP ${response.status()}`).toBeTruthy();
  const body = await response.json();
  expect(body.run?.status, JSON.stringify(body.run ?? {})).toBe(status);
}

async function exportPathsAreReachable(
  request: APIRequestContext,
  paths: Record<string, string>,
): Promise<void> {
  // Non-empty assertions matter for the PDF/DOCX renderer: a 200 with an empty body would
  // otherwise look like a successful export.
  const md = await request.get(`${API_BASE}${paths.md}`);
  expect(md.status(), `markdown export ${paths.md}`).toBe(200);
  expect(md.headers()['content-type'] ?? '').toContain('text/markdown');
  expect((await md.body()).length, 'markdown export must not be empty').toBeGreaterThan(200);
  expect((await md.text())).toContain(REPORT_TITLE);

  if (paths.pdf) {
    const pdf = await request.get(`${API_BASE}${paths.pdf}`);
    expect(pdf.status(), `pdf export ${paths.pdf}`).toBe(200);
    expect(pdf.headers()['content-type'] ?? '').toContain('application/pdf');
    const bytes = await pdf.body();
    expect(bytes.length, 'pdf export must not be empty').toBeGreaterThan(5_000);
    // Every PDF starts with the %PDF- magic header.
    expect(bytes.subarray(0, 5).toString('latin1')).toBe('%PDF-');
  }
  if (paths.docx) {
    const docx = await request.get(`${API_BASE}${paths.docx}`);
    expect(docx.status(), `docx export ${paths.docx}`).toBe(200);
    expect(docx.headers()['content-type'] ?? '').toContain('openxmlformats');
    const bytes = await docx.body();
    expect(bytes.length, 'docx export must not be empty').toBeGreaterThan(5_000);
    // DOCX files are ZIP archives: first two bytes are 'PK'.
    expect(bytes.subarray(0, 2).toString('latin1')).toBe('PK');
  }
}

async function openEvidenceModal(page: Page): Promise<void> {
  await page.getByRole('button', { name: '查看证据' }).click();
  await expect(page.getByText(/证据清单（\d+）/)).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('link', { name: /\[E\d+\]/ }).first()).toBeVisible();
}

async function closeEvidenceModal(page: Page): Promise<void> {
  await page.getByRole('button', { name: '关闭' }).click();
  await expect(page.getByText(/证据清单（\d+）/)).toBeHidden();
}

async function askFollowUp(page: Page, question: string): Promise<void> {
  const chatInput = page.getByPlaceholder('对这份报告还有什么问题？');
  await expect(chatInput).toBeVisible({ timeout: 30_000 });
  await chatInput.fill(question);
  await page.getByRole('button', { name: '发送' }).click();
  await expect(page.getByText(new RegExp(CHAT_MARKER)).first()).toBeVisible({ timeout: 120_000 });
}

test.describe('ResearchRunner full flow (real Java backend + stub providers)', () => {
  test('web：研究 → 报告/证据 → 追问 → MD/PDF/DOCX 导出', async ({ page, request }) => {
    test.setTimeout(FLOW_TIMEOUT + 60_000);
    const captured = captureFrames(page);
    await openHome(page);
    await configureSource(page, 'web');
    const question = 'E2E web 全流程：数据库迁移的备份、验证和回滚步骤';
    await submitResearch(page, question);

    await waitForCompletedReport(page, captured);
    await expectNoResearchError(page, captured);

    const started = captured.researchStarted!;
    await expectRunStatus(request, started.run_id, 'completed');

    // Report body is rendered from real Markdown and cites evidence with resolved labels.
    await expect(page.getByRole('link', { name: /网页 \d+：E2E 网页正文/ }).first()).toBeVisible();

    // Evidence API + UI modal.
    const evidenceResponse = await request.get(`${API_BASE}/api/reports/${started.research_id}/evidence`);
    expect(evidenceResponse.status()).toBe(200);
    const evidenceBody = await evidenceResponse.json();
    expect(Array.isArray(evidenceBody.evidence)).toBeTruthy();
    expect(evidenceBody.evidence.length).toBeGreaterThan(0);
    expect(evidenceBody.evidence[0].content).toBeTruthy();
    await openEvidenceModal(page);
    await closeEvidenceModal(page);

    // Export links rendered from the `path` WebSocket event are really downloadable.
    const pdfLink = page.getByRole('link', { name: /查看 PDF/i });
    const docxLink = page.getByRole('link', { name: /下载 Word 文档/i });
    await expect(pdfLink).toBeVisible();
    await expect(docxLink).toBeVisible();
    const pdfHref = await pdfLink.getAttribute('href');
    const docxHref = await docxLink.getAttribute('href');
    expect(pdfHref).toContain('/api/artifacts/');
    expect(docxHref).toContain('/api/artifacts/');
    const pdf = await request.get(pdfHref!);
    expect(pdf.status()).toBe(200);
    expect(pdf.headers()['content-type'] ?? '').toContain('application/pdf');
    const docx = await request.get(docxHref!);
    expect(docx.status()).toBe(200);
    expect(docx.headers()['content-type'] ?? '').toContain('openxmlformats');
    await exportPathsAreReachable(request, captured.path!.output as Record<string, string>);

    // Follow-up chat against the completed report (real /api/chat -> Java ReportChat).
    await askFollowUp(page, '请用一句话总结这份报告的结论。');
  });

  test('取消：停止按钮 → UI 无错误、后端 run 为 cancelled', async ({ page, request }) => {
    test.setTimeout(FLOW_TIMEOUT);
    const captured = captureFrames(page);
    await openHome(page);
    await configureSource(page, 'web');
    // The stub adds latency to every model call for queries containing E2E-SLOW.
    await submitResearch(page, 'E2E-SLOW 取消流程验证：本地 stub 慢响应研究');

    await expect.poll(() => captured.researchStarted?.run_id, { timeout: 60_000 }).toBeTruthy();
    const runId = captured.researchStarted!.run_id as string;

    await page.getByRole('button', { name: '停止研究' }).click();
    await expect(page.getByRole('button', { name: '停止研究' })).toBeHidden();
    await expect(page.getByRole('button', { name: '新建研究' })).toBeVisible();

    // Open the (initially hidden) assistant panel to see the stopped state.
    await page.getByRole('button', { name: '对话' }).click();
    await expect(page.getByText('研究已停止。请新建研究后继续对话。')).toBeVisible({ timeout: 30_000 });

    await expectNoResearchError(page, captured);
    await expect
      .poll(
        async () => {
          const response = await request.get(`${API_BASE}/api/research-runs/${runId}`);
          if (!response.ok()) return `http-${response.status()}`;
          const body = await response.json();
          return body.run?.status as string | undefined;
        },
        { timeout: 60_000, intervals: [500, 1_000, 2_000] },
      )
      .toBe('cancelled');
  });

  test('local：上传中文资料 → 指定文档研究 → 报告含资料引用', async ({ page, request }) => {
    test.setTimeout(FLOW_TIMEOUT + 60_000);
    const fileName = 'e2e-本地资料.md';
    const fixture = [
      '# 隔离验收资料：ResearchRunner 数据保护约定',
      '',
      '本文是本地验收夹具，不代表外部权威资料。应用使用 PostgreSQL 保存研究报告、聊天、证据、附件和资料向量。',
      '迁移前要导出数据库备份，在隔离数据库恢复后核对记录。命名数据卷不是备份，禁止删除原数据卷。',
      '切换前停止旧后端接收新任务，等待任务结束，再启动新后端写入。',
    ].join('\n');
    const upload = await request.post(`${API_BASE}/api/documents`, {
      multipart: {
        file: { name: fileName, mimeType: 'text/markdown', buffer: Buffer.from(fixture, 'utf-8') },
      },
      timeout: 60_000,
    });
    expect(upload.status(), await upload.text()).toBe(200);
    const uploaded = await upload.json();
    const documentId = uploaded.document?.id as string;
    expect(documentId).toBeTruthy();

    await expect
      .poll(
        async () => {
          const response = await request.get(`${API_BASE}/api/documents`);
          if (!response.ok()) return `http-${response.status()}`;
          const body = await response.json();
          const document = (body.documents ?? []).find((item: any) => item.id === documentId);
          return document?.status as string | undefined;
        },
        { timeout: 90_000, intervals: [500, 1_000, 2_000] },
      )
      .toBe('ready');

    const captured = captureFrames(page);
    await openHome(page);
    await configureSource(page, 'local');
    await openLibrary(page);
    const library = librarySection(page);
    await library.getByLabel('文件检索范围').selectOption('selected');
    await library.getByLabel(new RegExp(`选择 ${fileName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)).check();
    await expect(page.getByRole('button', { name: /我的资料库/ })).toContainText('已选 1 份');

    await submitResearch(page, 'E2E local 全流程：依据所选中文资料总结数据保护约定');
    await waitForCompletedReport(page, captured);
    await expectNoResearchError(page, captured);

    const started = captured.researchStarted!;
    await expectRunStatus(request, started.run_id, 'completed');
    await expect(page.getByRole('link', { name: new RegExp(`资料 \\d+：${fileName}`) }).first()).toBeVisible();

    const evidenceResponse = await request.get(`${API_BASE}/api/reports/${started.research_id}/evidence`);
    expect(evidenceResponse.status()).toBe(200);
    const evidenceBody = await evidenceResponse.json();
    const documentEvidence = (evidenceBody.evidence as any[]).find((item) => item.source_type === 'document');
    expect(documentEvidence, JSON.stringify(evidenceBody.evidence)).toBeTruthy();
    const documentView = await request.get(
      `${API_BASE}/api/research-runs/${started.research_id}/evidence/${documentEvidence.number}/view`,
    );
    expect(documentView.status()).toBe(200);
  });

  test('hybrid：网络 stub + 本地资料，证据同时包含 web 与 document', async ({ page, request }) => {
    test.setTimeout(FLOW_TIMEOUT + 60_000);
    const captured = captureFrames(page);
    await openHome(page);
    await configureSource(page, 'hybrid');
    await submitResearch(page, 'E2E hybrid 冒烟：文件与网页来源合并的研究流程');
    await waitForCompletedReport(page, captured);
    await expectNoResearchError(page, captured);

    const started = captured.researchStarted!;
    await expectRunStatus(request, started.run_id, 'completed');
    const evidenceResponse = await request.get(`${API_BASE}/api/reports/${started.research_id}/evidence`);
    expect(evidenceResponse.status()).toBe(200);
    const evidence = (await evidenceResponse.json()).evidence as any[];
    const types = new Set(evidence.map((item) => item.source_type));
    const typeList = Array.from(types).join(',');
    expect(types.has('document'), `evidence types: ${typeList}`).toBeTruthy();
    expect(types.has('web'), `evidence types: ${typeList}`).toBeTruthy();
  });

  test('后端契约：follow_guidelines=true 走 planner/审阅/修订 JSON 协议', async ({ request }) => {
    test.setTimeout(FLOW_TIMEOUT);
    const statsBefore = await request.get(`${STUB_BASE}/control/stats`);
    expect(statsBefore.ok(), 'stub control endpoint should be reachable').toBeTruthy();
    await request.post(`${STUB_BASE}/control/reset`);

    const response = await request.post(`${API_BASE}/report/`, {
      data: {
        task: 'E2E 契约验证：审阅意见与章节修订 JSON',
        report_source: 'web',
        report_type: 'multi_agents',
        tone: 'Objective',
        generate_in_background: false,
        follow_guidelines: true,
        max_sections: 2,
        max_search_results: 2,
        max_draft_revisions: 2,
        max_fact_check_revisions: 1,
        export_pdf: true,
        export_docx: true,
      },
      timeout: FLOW_TIMEOUT,
    });
    expect(response.status(), await response.text()).toBe(200);
    const result = await response.json();
    expect(result.run_id).toBeTruthy();
    expect(result.research_id).toBeTruthy();
    expect(String(result.report)).toContain(REPORT_TITLE);
    expect(String(result.report)).toContain(REPORT_CONCLUSION);
    expect(String(result.report)).toContain('网页 1：');
    await exportPathsAreReachable(request, {
      md: result.md_path,
      pdf: result.pdf_path,
      docx: result.docx_path,
    });

    const stats = await (await request.get(`${STUB_BASE}/control/stats`)).json();
    const stages = stats.stages as Record<string, number>;
    expect(stages.initial_research ?? 0).toBeGreaterThan(0);
    expect(stages.planner ?? 0).toBeGreaterThan(0);
    expect(stages.writer ?? 0).toBeGreaterThan(0);
    expect(stages.fact_checker ?? 0).toBeGreaterThan(0);
    if (stats.reject_once) {
      // Reject-once is enabled by scripts/test_java_e2e_stub.ps1; it must drive the
      // JSON section_reviser contract and the reviewer-revision variant.
      expect(stages.section_reviewer ?? 0).toBeGreaterThan(1);
      expect(stages.section_reviser ?? 0).toBeGreaterThan(0);
    }
  });
});
