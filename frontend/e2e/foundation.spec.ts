import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

test('opens the foundation status page with accessible module status', async ({ page }) => {
  const browserErrors: string[] = [];
  page.on('pageerror', (error) => browserErrors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') browserErrors.push(message.text());
  });

  await page.route('**/actuator/health/readiness', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"status":"UP"}' });
  });

  await page.goto('/app');

  await expect(page.getByRole('heading', { name: 'System status' })).toBeVisible();
  await expect(page.getByText('Foundation available')).toBeVisible();
  await expect(page.getByText('Reported by the backend readiness health group.')).toBeVisible();
  await expect(page.getByLabel('Planned business modules')).toContainText('Trade orders');
  await expect(page.locator('.vite-error-overlay')).toHaveCount(0);
  expect((await page.locator('body').innerText()).trim().length).toBeGreaterThan(0);

  const accessibility = await new AxeBuilder({ page }).analyze();
  expect(accessibility.violations).toEqual([]);
  expect(browserErrors).toEqual([]);
});
