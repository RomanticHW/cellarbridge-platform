import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

test('protects the operations shell with an accessible OIDC sign-in page', async ({ page }) => {
  const browserErrors: string[] = [];
  page.on('pageerror', (error) => browserErrors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') browserErrors.push(message.text());
  });

  await page.goto('/app');

  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: 'Sign in to Operations' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Continue with OIDC' })).toBeVisible();
  await expect(page.locator('.vite-error-overlay')).toHaveCount(0);
  expect((await page.locator('body').innerText()).trim().length).toBeGreaterThan(0);

  const accessibility = await new AxeBuilder({ page }).analyze();
  expect(accessibility.violations).toEqual([]);
  expect(browserErrors).toEqual([]);
});
