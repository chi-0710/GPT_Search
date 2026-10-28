import type { Page, Route } from '@playwright/test';

/**
 * Fixtures shared by the read-only ResearchRunner UI E2E tests.
 *
 * Nothing here talks to the real Python/Java backends: every browser request to
 * an `/api/*` URL is fulfilled locally, so the tests can run without providers,
 * API services, or a database.
 */

export const REPORT_ID = '11111111-2222-3333-4444-555555555555';
export const ARTIFACT_PDF = 'aaaaaaaa-1111-2222-3333-444444444444';
export const ARTIFACT_DOCX = 'bbbbbbbb-1111-2222-3333-444444444444';
export const HISTORY_QUESTION = '历史研究：共享资料库如何影响研究质量？';
export const REPORT_ANSWER = '# 历史报告标题\n\n这是历史报告的正文段落。';

export interface StoredDocument {
  id: string;
  filename: string;
  status: 'processing' | 'ready' | 'failed';
  size: number;
  createdAt: string;
}

export const DOCUMENTS: StoredDocument[] = [
  {
    id: 'doc-ready-1',
    filename: '市场调研.pdf',
    status: 'ready',
    size: 20480,
    createdAt: '2026-10-01T00:00:00Z',
  },
  {
    id: 'doc-ready-2',
    filename: '技术白皮书.docx',
    status: 'ready',
    size: 4096,
    createdAt: '2026-10-02T00:00:00Z',
  },
  {
    id: 'doc-processing-1',
    filename: '扫描件.pdf',
    status: 'processing',
    size: 1024,
    createdAt: '2026-10-03T00:00:00Z',
  },
];

export interface EvidenceItem {
  number: number;
  source_type?: string;
  title?: string;
  page?: number | null;
  url?: string;
}

export const EVIDENCE: EvidenceItem[] = [
  { number: 1, source_type: 'web', title: '示例来源：行业报告', page: null, url: 'https://example.com/a' },
  { number: 2, source_type: 'local', title: '本地资料.pdf', page: 3 },
];

const orderedDataForReport = () => [
  { type: 'question', content: HISTORY_QUESTION },
  { type: 'report_complete', output: REPORT_ANSWER },
  {
    type: 'path',
    output: {
      pdf: `/api/artifacts/${ARTIFACT_PDF}`,
      docx: `/api/artifacts/${ARTIFACT_DOCX}`,
      json: `/api/research-runs/${REPORT_ID}/log`,
    },
  },
];

const HISTORY_REPORTS = [
  {
    id: REPORT_ID,
    question: HISTORY_QUESTION,
    answer: REPORT_ANSWER,
    orderedData: orderedDataForReport(),
    chatMessages: [],
    timestamp: Date.now() - 3_600_000,
  },
];

const REPORT_DETAIL = {
  report: {
    id: REPORT_ID,
    question: HISTORY_QUESTION,
    answer: REPORT_ANSWER,
    orderedData: orderedDataForReport(),
    chatMessages: [],
    timestamp: Date.now() - 3_600_000,
  },
};

export interface ApiMockHandle {
  /** URLs of every `/api/reports/{id}/evidence` request the UI made. */
  evidenceRequests: string[];
  /** URLs of every `/api/documents` request the UI made. */
  documentRequests: string[];
}

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({
    status,
    headers: {
      'content-type': 'application/json',
      // The UI resolves its API host via getHost(); tests may run the app on a
      // different origin/port than the mocked API URLs, so allow the response.
      'access-control-allow-origin': '*',
    },
    body: JSON.stringify(body),
  });

/**
 * Intercept the whole `/api/*` surface with deterministic fixtures.
 *
 * A catch-all is registered first and the specific handlers afterwards:
 * Playwright evaluates the most recently registered matching handler first, so
 * the specific fixtures win.
 */
export async function installApiMocks(page: Page): Promise<ApiMockHandle> {
  const handle: ApiMockHandle = { evidenceRequests: [], documentRequests: [] };

  await page.route('**/api/**', (route) => json(route, { detail: 'e2e: unmocked API call' }, 404));

  await page.route(/\/api\/documents(\?.*)?$/, (route) => {
    handle.documentRequests.push(route.request().url());
    if (route.request().method() === 'GET') {
      return json(route, { documents: DOCUMENTS });
    }
    return json(route, { ok: true });
  });

  await page.route(/\/api\/reports(\?.*)?$/, (route) => {
    if (route.request().method() === 'GET') {
      return json(route, { reports: HISTORY_REPORTS });
    }
    return json(route, { ok: true });
  });

  await page.route(/\/api\/reports\/[^/]+\/evidence$/, (route) => {
    handle.evidenceRequests.push(route.request().url());
    return json(route, { evidence: EVIDENCE });
  });

  await page.route(/\/api\/reports\/[^/]+$/, (route) => {
    if (route.request().method() === 'GET') {
      return json(route, REPORT_DETAIL);
    }
    return json(route, { ok: true });
  });

  return handle;
}

/**
 * Replace the browser WebSocket with a controllable stub.
 *
 * The app's `useWebSocket` hook only uses `onopen`/`onmessage`/`onclose`/
 * `onerror` properties, so this stub keeps the connection "open" and lets the
 * test inspect outgoing frames (`__e2eSent`) and push server frames
 * (`__e2eSockets[last].onmessage`). No network connection is made.
 */
export async function installMockWebSocket(page: Page): Promise<void> {
  await page.addInitScript(() => {
    interface MockWindow extends Window {
      __e2eSockets?: MockWebSocket[];
      __e2eSent?: string[];
    }

    class MockWebSocket {
      static readonly CONNECTING = 0;
      static readonly OPEN = 1;
      static readonly CLOSING = 2;
      static readonly CLOSED = 3;

      readonly url: string;
      readyState = MockWebSocket.CONNECTING;
      onopen: ((event: unknown) => void) | null = null;
      onmessage: ((event: { data: string }) => void) | null = null;
      onclose: ((event: unknown) => void) | null = null;
      onerror: ((event: unknown) => void) | null = null;
      readonly sent: string[] = [];

      constructor(url: string) {
        this.url = url;
        const scope = window as MockWindow;
        scope.__e2eSockets = scope.__e2eSockets ?? [];
        scope.__e2eSockets.push(this);
        window.setTimeout(() => {
          this.readyState = MockWebSocket.OPEN;
          this.onopen?.({});
        }, 30);
      }

      send(data: string): void {
        this.sent.push(data);
        const scope = window as MockWindow;
        scope.__e2eSent = scope.__e2eSent ?? [];
        scope.__e2eSent.push(data);
      }

      close(code?: number, reason?: string): void {
        this.readyState = MockWebSocket.CLOSED;
        this.onclose?.({ code, reason });
      }

      addEventListener(): void {
        /* The app registers handlers through properties only. */
      }

      removeEventListener(): void {
        /* Not used by the app. */
      }

      dispatchEvent(): boolean {
        return false;
      }
    }

    (window as MockWindow & { WebSocket: unknown }).WebSocket = MockWebSocket;
  });
}

/** Textarea submit button on the home page. */
export async function submitResearch(page: Page, question: string): Promise<void> {
  await page.locator('textarea').first().fill(question);
  await page.getByRole('button', { name: '开始研究' }).click();
}

/** Read all WebSocket frames the stubbed client sent so far. */
export async function sentFrames(page: Page): Promise<string[]> {
  return page.evaluate(() => (window as Window & { __e2eSent?: string[] }).__e2eSent ?? []);
}

/** Push one server frame to the most recently created stubbed socket. */
export async function pushSocketFrame(page: Page, frame: unknown): Promise<void> {
  await page.evaluate((payload) => {
    const scope = window as Window & { __e2eSockets?: Array<{ onmessage: ((event: { data: string }) => void) | null }> };
    const sockets = scope.__e2eSockets ?? [];
    const socket = sockets[sockets.length - 1];
    socket?.onmessage?.({ data: JSON.stringify(payload) });
  }, frame);
}
