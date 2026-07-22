import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testMatch: 'keycloak-outage.evidence.ts',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  timeout: 10 * 60 * 1000,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    ...devices['Desktop Chrome'],
  },
});
