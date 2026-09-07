import { defineConfig } from '@playwright/test';

/**
 * scrum-ceremonies e2e harness.
 *
 * The static frontend on :3000 starts automatically below. The BACKEND does
 * not — start it yourself before running:
 *
 *   docker compose up -d redis
 *   mvn spring-boot:run          # :8080
 *   npx playwright test
 *
 * 14-v1-tools-api-verification and the room-flow specs talk to :8080 directly;
 * without the backend they fail loudly rather than skip.
 */
export default defineConfig({
  testDir: './e2e',
  // verification-run only, not CI (its own docstring): run with INCLUDE_AUDIT=1
  testIgnore: process.env.INCLUDE_AUDIT ? [] : ['**/v1-tools-multiuser-audit.spec.ts'],
  timeout: 60_000,
  retries: 1,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:3000',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: [
    {
      command: 'npx http-server frontend/v1 -p 3000 -c-1 --silent',
      port: 3000,
      reuseExistingServer: true,
      timeout: 10_000,
    },
  ],
});
