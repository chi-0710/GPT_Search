import { defineConfig } from '@playwright/test';

/**
 * E2E config for the ResearchRunner Next.js frontend.
 *
 * Two suites live in `./e2e`:
 *  - `researchrunner-ui.spec.ts`   mocked, read-only UI acceptance tests (no backend).
 *  - `full-flow.spec.ts`           real end-to-end flows (Java backend + provider stub +
 *                                  throw-away PostgreSQL) driven by
 *                                  `scripts/test_java_e2e_stub.ps1`.
 *
 * The Docker dev server at http://localhost:3800 is the default target; set
 * PLAYWRIGHT_BASE_URL to point at the one-shot `next dev` server (port 3901) used by the
 * full-flow script.  PLAYWRIGHT_OUTPUT_DIR lets that script keep traces/screenshots under
 * backend-java/target/e2e-stub instead of the checkout.
 *
 * The local machine has system Chrome installed, so use `channel: 'chrome'`
 * instead of downloading Playwright browser binaries.
 */
const outputDir = process.env.PLAYWRIGHT_OUTPUT_DIR;

export default defineConfig({
  testDir: './e2e',
  outputDir: outputDir && outputDir.trim() ? outputDir : './test-results',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 20_000 },
  reporter: [['list']],
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:3800',
    channel: 'chrome',
    headless: true,
    viewport: { width: 1440, height: 900 },
    actionTimeout: 15_000,
    navigationTimeout: 60_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [
    {
      name: 'chromium',
      use: { browserName: 'chromium', channel: 'chrome' },
    },
  ],
});
