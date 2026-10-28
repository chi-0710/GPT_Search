import { expect, test, type Page } from '@playwright/test';
import {
  ARTIFACT_DOCX,
  ARTIFACT_PDF,
  HISTORY_QUESTION,
  REPORT_ID,
  installApiMocks,
  installMockWebSocket,
  pushSocketFrame,
  sentFrames,
  submitResearch,
} from './fixtures';

/**
 * Read-only UI acceptance tests for the ResearchRunner frontend.
 *
 * Scope: home page controls, source switching, human-review toggle, document
 * library selection, stop-while-running, report evidence modal, history report
 * opening and the export links. All backend traffic is mocked (see fixtures.ts).
 */

async function openSettings(page: Page): Promise<void> {
  await page.getByRole('button', { name: '偏好设置' }).click();
  await expect(page.getByText('多智能体研究')).toBeVisible();
}

async function closeSettings(page: Page): Promise<void> {
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

test.describe('ResearchRunner UI acceptance (read-only, mocked API)', () => {
  test.beforeEach(async ({ page }) => {
    await installApiMocks(page);
  });

  test('首页加载关键控件', async ({ page }) => {
    await page.goto('/');

    await expect(page).toHaveTitle(/知行研究助手/);
    await expect(page.getByRole('heading', { name: '你接下来想研究什么？' })).toBeVisible();
    await expect(page.locator('textarea').first()).toHaveAttribute(
      'placeholder',
      '输入你想研究的主题、问题或感兴趣的领域……',
    );
    await expect(page.getByRole('button', { name: '开始研究' })).toBeVisible();

    for (const suggestion of ['分析这只股票：', '帮我规划一次旅行：', '查找这个主题的最新消息：']) {
      await expect(page.getByText(suggestion, { exact: true })).toBeVisible();
    }

    await expect(page.getByRole('button', { name: /我的资料库/ })).toBeVisible();
    await expect(page.getByRole('button', { name: '偏好设置' })).toBeVisible();
    await expect(page.getByText(/保留所有权利/)).toBeVisible();
    await expect(page.getByText(/知行研究助手 可能会出错/)).toBeVisible();
  });

  test('来源切换 web/local/hybrid 并联动资料库提示', async ({ page }) => {
    await page.goto('/');
    await openSettings(page);

    const source = page.locator('select[name="report_source"]');
    await expect(source).toHaveValue('hybrid');
    await expect(page.getByText('按域名筛选')).toBeVisible();

    await source.selectOption('web');
    await expect(source).toHaveValue('web');
    await expect(page.getByText('按域名筛选')).toBeVisible();

    await source.selectOption('local');
    await expect(source).toHaveValue('local');
    await expect(page.getByText('按域名筛选')).toBeHidden();

    await closeSettings(page);
    await openLibrary(page);
    await expect(librarySection(page).getByLabel('文件检索范围')).toBeVisible();

    await openSettings(page);
    await source.selectOption('web');
    await closeSettings(page);
    await expect(librarySection(page).getByText(/当前为“仅网络”/)).toBeVisible();
    await expect(librarySection(page).getByLabel('文件检索范围')).toBeHidden();

    await openSettings(page);
    await source.selectOption('hybrid');
    await closeSettings(page);
    await expect(librarySection(page).getByLabel('文件检索范围')).toBeVisible();
    await expect(page.getByRole('button', { name: /我的资料库/ })).toContainText('使用全部可检索资料');
  });

  test('规划阶段人工审核开关可切换', async ({ page }) => {
    await page.goto('/');
    await openSettings(page);

    const toggle = page.getByRole('checkbox', { name: '规划阶段人工审核' });
    await expect(toggle).toBeVisible();
    await expect(toggle).not.toBeChecked();
    await expect(page.getByText('开启后研究会在规划完成后等待你的意见（接受或修改）。')).toBeVisible();

    await toggle.check();
    await expect(toggle).toBeChecked();

    await toggle.uncheck();
    await expect(toggle).not.toBeChecked();
  });

  test('资料库列表、检索范围与指定文件选择', async ({ page }) => {
    await page.goto('/');
    await openLibrary(page);
    const library = librarySection(page);

    await expect(library.getByText('市场调研.pdf')).toBeVisible();
    await expect(library.getByText('技术白皮书.docx')).toBeVisible();
    await expect(library.getByText('扫描件.pdf')).toBeVisible();
    await expect(library.getByText('可检索', { exact: true })).toHaveCount(2);
    await expect(library.getByText('解析中', { exact: true })).toBeVisible();

    await library.getByLabel('文件检索范围').selectOption('selected');
    await expect(library.getByLabel(/选择 市场调研\.pdf/)).toBeEnabled();

    const processing = library.getByLabel(/选择 扫描件\.pdf/);
    await expect(processing).toBeDisabled();

    await library.getByLabel(/选择 市场调研\.pdf/).check();
    await expect(page.getByRole('button', { name: /我的资料库/ })).toContainText('已选 1 份');

    await library.getByLabel(/选择 市场调研\.pdf/).uncheck();
    await expect(page.getByRole('button', { name: /我的资料库/ })).toContainText('已选 0 份');
  });

  test('研究运行中显示停止按钮并可停止', async ({ page }) => {
    await installMockWebSocket(page);
    await page.goto('/');

    await submitResearch(page, '测试研究运行中的停止按钮');

    const stop = page.getByRole('button', { name: '停止研究' });
    await expect(stop).toBeVisible();

    const frames = await sentFrames(page);
    const startFrame = frames.find((frame) => frame.startsWith('start '));
    expect(startFrame, 'WebSocket start frame should be sent').toBeTruthy();
    const payload = JSON.parse(startFrame!.slice('start '.length));
    expect(payload.task).toBe('测试研究运行中的停止按钮');
    expect(payload.report_type).toBe('multi_agents');
    expect(payload.report_source).toBe('hybrid');
    expect(payload.include_human_feedback).toBe(false);

    await stop.click();
    await expect(stop).toBeHidden();
    await expect(page.getByRole('button', { name: '新建研究' })).toBeVisible();
  });

  test('人工反馈请求展示并可提交', async ({ page }) => {
    await installMockWebSocket(page);
    await page.goto('/');
    await submitResearch(page, '测试规划阶段人工反馈');
    await expect(page.getByRole('button', { name: '停止研究' })).toBeVisible();

    await pushSocketFrame(page, {
      type: 'human_feedback',
      content: 'request',
      run_id: 'run-e2e-1',
      round: 1,
      request_id: 'req-e2e-1',
      output: '请确认研究计划：先分析市场，再分析技术。',
    });

    await expect(page.getByText('需要人工反馈')).toBeVisible();
    await expect(page.getByText('请确认研究计划：先分析市场，再分析技术。')).toBeVisible();

    await page.getByPlaceholder('在此输入修改意见；如果没有意见，可以留空').fill('同意该计划');
    await page.getByRole('button', { name: '提交反馈' }).click();
    await expect(page.getByText('需要人工反馈')).toBeHidden();

    const frames = await sentFrames(page);
    const feedbackFrame = frames
      .map((frame) => {
        try {
          return JSON.parse(frame);
        } catch {
          return null;
        }
      })
      .find((frame) => frame && frame.type === 'human_feedback');
    expect(feedbackFrame).toBeTruthy();
    expect(feedbackFrame.content).toBe('同意该计划');
    expect(feedbackFrame.run_id).toBe('run-e2e-1');
    expect(feedbackFrame.request_id).toBe('req-e2e-1');
  });

  test('报告页证据弹窗加载 /api/reports/{id}/evidence', async ({ page }) => {
    const api = await installApiMocks(page);
    await page.goto(`/research/${REPORT_ID}`);

    await expect(page.getByText('历史报告标题')).toBeVisible();
    await expect(page.getByText(HISTORY_QUESTION).first()).toBeVisible();

    await page.getByRole('button', { name: '查看证据' }).click();

    await expect(page.getByText('证据清单（2）')).toBeVisible();
    await expect(page.getByRole('link', { name: /E1.*示例来源：行业报告/ })).toBeVisible();
    const secondLink = page.getByRole('link', { name: /E2.*本地资料\.pdf/ });
    await expect(secondLink).toBeVisible();
    await expect(page.getByText('local · 第 3 页')).toBeVisible();

    const href = await secondLink.getAttribute('href');
    expect(href).toContain(`/api/research-runs/${REPORT_ID}/evidence/2/view`);

    expect(api.evidenceRequests).toHaveLength(1);
    expect(api.evidenceRequests[0]).toContain(`/api/reports/${REPORT_ID}/evidence`);

    await page.getByRole('button', { name: '关闭' }).click();
    await expect(page.getByText('证据清单（2）')).toBeHidden();
  });

  test('历史报告打开与导出链接', async ({ page }) => {
    await page.goto('/');

    await page.locator('[aria-label="打开研究历史侧边栏"]').first().click();
    await expect(page.getByText('研究历史')).toBeVisible();
    const historyLink = page.getByRole('link', { name: new RegExp(HISTORY_QUESTION) });
    await expect(historyLink).toBeVisible();
    await historyLink.click();

    await page.waitForURL(new RegExp(`/research/${REPORT_ID}$`));
    await expect(page.getByText('历史报告标题')).toBeVisible();
    await expect(page.getByText(HISTORY_QUESTION).first()).toBeVisible();

    const pdfLink = page.getByRole('link', { name: /查看 PDF/i });
    const docxLink = page.getByRole('link', { name: /下载 Word 文档/i });
    await expect(pdfLink).toBeVisible();
    await expect(docxLink).toBeVisible();

    const pdfHref = await pdfLink.getAttribute('href');
    const docxHref = await docxLink.getAttribute('href');
    expect(pdfHref).toContain(`/api/artifacts/${ARTIFACT_PDF}`);
    expect(docxHref).toContain(`/api/artifacts/${ARTIFACT_DOCX}`);
    await expect(pdfLink).toHaveAttribute('target', '_blank');
    await expect(docxLink).toHaveAttribute('target', '_blank');
  });
});
