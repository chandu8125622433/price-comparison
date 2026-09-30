import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E configuration for the PriceComparison app.
 *
 * - Tests run against the real Spring Boot frontend/API at http://localhost:8080.
 * - If a Spring Boot server is ALREADY listening on port 8080 it is reused as-is
 *   (reuseExistingServer: true) — Playwright never starts a second instance.
 *   If it is not running, Playwright boots it once via the Maven wrapper.
 */
export default defineConfig({
  testDir: './tests',

  /* Deterministic MVP smoke run: one worker, no parallelism, no retries. */
  fullyParallel: false,
  workers: 1,
  retries: 0,

  /*
   * Generous per-test timeout (default is 30s). On this machine a cold run
   * boots the real Spring Boot app via the webServer and then launches
   * headless Chromium while the system is still under load; both the
   * initial page creation and the first navigation have repeatedly
   * exceeded 30s (observed twice, passing in ~2-10s when idle).
   */
  timeout: 60_000,

  /* Machine-readable terminal output; HTML report written to playwright-report/ */
  reporter: [['list'], ['html', { open: 'never' }]],

  use: {
    /* Base URL for the Spring Boot app — page.goto('/') hits http://localhost:8080/ */
    baseURL: 'http://localhost:8080',
    trace: 'retain-on-failure',
  },

  /* Chromium only for the MVP smoke suite */
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  /* Reuse an already-running Spring Boot server on :8080; boot one only if absent. */
  webServer: {
    command: 'mvnw.cmd spring-boot:run',
    url: 'http://localhost:8080',
    reuseExistingServer: true,
    timeout: 180_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
