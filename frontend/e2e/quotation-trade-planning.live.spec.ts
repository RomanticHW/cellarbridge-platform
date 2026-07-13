import { expect, test, type Browser, type Page } from '@playwright/test';

const password = 'CellarBridge-Demo-2026!';

interface AuthenticatedPage {
  page: Page;
  browserErrors: string[];
}

async function login(browser: Browser, username: string): Promise<AuthenticatedPage> {
  const context = await browser.newContext();
  const page = await context.newPage();
  const browserErrors: string[] = [];
  page.on('pageerror', (error) => browserErrors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') browserErrors.push(message.text());
  });
  await page.goto('/app');
  await page.getByRole('button', { name: 'Continue with OIDC' }).click();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await expect(page.getByRole('heading', { name: 'System status' })).toBeVisible();
  return { page, browserErrors };
}

test('creates, routes, approves, issues, and safely previews a revisioned quotation', async ({
  browser,
}) => {
  const sales = await login(browser, 'north.sales');
  await sales.page.getByRole('menuitem', { name: 'Catalog & supply' }).click();
  await expect(sales.page.getByRole('heading', { name: 'Catalog & supply search' })).toBeVisible();
  const catalogResponse = sales.page.waitForResponse(
    (response) =>
      response.url().includes('/api/v1/catalog/skus') &&
      new URL(response.url()).searchParams.get('keyword') === 'Moonlit Terrace',
  );
  await sales.page.getByRole('textbox', { name: 'Search catalog' }).fill('Moonlit Terrace');
  expect((await catalogResponse).ok()).toBe(true);
  await expect(sales.page.getByText('CB-MTV-2019-750X6')).toBeVisible();
  await sales.page.getByRole('button', { name: 'Add to quote selection' }).first().click();
  await sales.page.getByRole('link', { name: 'Create quotation with selection' }).click();

  await expect(sales.page.getByRole('heading', { name: 'Create quotation draft' })).toBeVisible();
  await sales.page.getByLabel('Active customer').click();
  await sales.page.getByText('Aurora Market Services · PAR-DEMO-QUOTATION').click();
  await sales.page.getByLabel('Address line').fill('88 Harbor Avenue');
  await sales.page.getByLabel('Line 1 discount rate').fill('0.0900');
  await sales.page.getByRole('button', { name: 'Save quotation draft' }).click();

  await expect(sales.page.getByRole('heading', { name: /QUO-\d{6}-\d{6}/ })).toBeVisible();
  const quotationPath = new URL(sales.page.url()).pathname;
  await sales.page.getByRole('button', { name: 'Evaluate routes' }).click();
  await expect(sales.page.getByText('ROUTE-2026-01')).toBeVisible();
  await expect(sales.page.getByText(/Selected route:/)).toBeVisible();
  await sales.page.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(sales.page.getByText('PENDING APPROVAL')).toBeVisible();
  await expect(
    sales.page.getByText(/Discount exceeds the automatic approval threshold/),
  ).toBeVisible();

  const manager = await login(browser, 'north.manager');
  await manager.page.goto(quotationPath);
  await expect(manager.page.getByRole('button', { name: 'Review quotation' })).toBeVisible();
  await manager.page.getByRole('button', { name: 'Review quotation' }).click();
  await manager.page
    .getByLabel('Approval reason')
    .fill('Commercial thresholds and route evidence reviewed');
  await manager.page.getByRole('button', { name: 'Record decision' }).click();
  const issueButton = manager.page.getByRole('button', { name: 'Issue quotation' });
  await expect(issueButton).toBeVisible();
  await issueButton.click();

  const publicLink = manager.page.getByRole('link', {
    name: 'Open the customer-safe quotation preview',
  });
  await expect(publicLink).toBeVisible();
  const publicPath = await publicLink.getAttribute('href');
  expect(publicPath).toMatch(/^\/portal\/quotations\/[A-Za-z0-9_-]+$/);
  await manager.page.goto(publicPath ?? '');
  await expect(manager.page.getByRole('heading', { name: /Quotation QUO-/ })).toBeVisible();
  await expect(manager.page.getByText('Moonlit Terrace')).toBeVisible();
  await expect(
    manager.page.getByText(/acceptance will be available in the next workflow stage/i),
  ).toBeVisible();
  await expect(manager.page.getByText('Estimated margin')).toHaveCount(0);
  await expect(manager.page.getByText('Weighted score')).toHaveCount(0);
  await expect(manager.page.getByText('ROUTE-2026-01')).toHaveCount(0);

  expect(sales.browserErrors).toEqual([]);
  expect(manager.browserErrors).toEqual([]);
  await Promise.all([sales.page.context().close(), manager.page.context().close()]);
});
