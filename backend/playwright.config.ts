import { defineConfig, devices } from '@playwright/test';

/**
 * Browser tests, against a running stack.
 *
 * These exist because of a specific bug that shipped: the landing pages
 * returned correct HTML, every status code was 200, and in a browser they
 * showed "Sidan finns inte". The SPA bundle mounted into the same element the
 * server had rendered into, React replaced its children, and the router had no
 * matching route.
 *
 * curl could not have caught that, and nothing else here runs JavaScript. The
 * point of this suite is not coverage — it is to check what a person actually
 * sees, which is the one thing every other test in this project cannot.
 *
 * Deliberately run against a live server rather than starting one: the stack is
 * Cal, two databases, api-v2, Redis and the backend, and a test harness that
 * pretends it can conjure that is a harness that lies about what it verified.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30000,
  expect: { timeout: 10000 },
  fullyParallel: true,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8090',
    trace: 'retain-on-failure',
  },
  // Uses the Chrome already installed on the machine rather than downloading
  // Playwright's own build. One less 170 MB artefact to keep in step, and it
  // tests the browser people actually have. Override with E2E_CHANNEL if a
  // pinned build is wanted instead.
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], channel: process.env.E2E_CHANNEL ?? 'chrome' },
    },
  ],
});
