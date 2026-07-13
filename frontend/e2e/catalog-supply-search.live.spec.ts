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

test('searches visible supply and adds an SKU to local quote selection without writing', async ({
  browser,
}) => {
  const sales = await login(browser, 'north.sales');
  const businessWrites: string[] = [];
  sales.page.on('request', (request) => {
    if (
      request.url().includes('/api/v1/') &&
      ['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method())
    ) {
      businessWrites.push(`${request.method()} ${request.url()}`);
    }
  });

  await sales.page.getByRole('menuitem', { name: 'Catalog & supply' }).click();
  await expect(sales.page.getByRole('heading', { name: 'Catalog & supply search' })).toBeVisible();
  const response = sales.page.waitForResponse(
    (candidate) =>
      candidate.url().includes('/api/v1/catalog/skus') &&
      new URL(candidate.url()).searchParams.get('keyword') === 'Moonlit Terrace',
  );
  await sales.page.getByRole('textbox', { name: 'Search catalog' }).fill('Moonlit Terrace');
  await expect(sales.page).toHaveURL(/keyword=Moonlit(?:\+|%20)Terrace/);
  expect((await response).ok()).toBe(true);

  await expect(sales.page.getByText('CB-MTV-2019-750X6')).toBeVisible();
  await expect(sales.page.getByText('Eastbank · Shanghai')).toBeVisible();
  await expect(sales.page.getByText('HIGH', { exact: true }).first()).toBeVisible();
  await expect(sales.page.getByText(/not an inventory commitment/i)).toBeVisible();
  await sales.page.getByRole('button', { name: 'Add to quote selection' }).first().click();
  await expect(sales.page.getByText('Pending quote selection (1)')).toBeVisible();
  await expect(
    sales.page.getByText(/No quotation, reservation, or order has been created/),
  ).toBeVisible();

  const buyer = await login(browser, 'north.buyer');
  await expect(buyer.page.getByRole('menuitem', { name: 'Catalog & supply' })).toHaveCount(0);
  await buyer.page.goto('/app/catalog');
  await expect(buyer.page.getByText('Catalog supply access denied')).toBeVisible();

  expect(businessWrites).toEqual([]);
  expect(sales.browserErrors).toEqual([]);
  expect(buyer.browserErrors.every((message) => message.includes('403'))).toBe(true);
  await Promise.all([sales.page.context().close(), buyer.page.context().close()]);
});
