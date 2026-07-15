import { expect, test, type Browser } from '@playwright/test';

const password = 'CellarBridge-Demo-2026!';

async function login(browser: Browser, username: string) {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/app');
  await page.getByRole('button', { name: 'Continue with OIDC' }).click();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await expect(page.getByRole('heading', { name: 'System status' })).toBeVisible();
  return page;
}

async function createIssuedQuotation(browser: Browser) {
  const sales = await login(browser, 'north.sales');
  await sales.getByRole('menuitem', { name: 'Catalog & supply' }).click();
  const catalogResponse = sales.waitForResponse(
    (response) =>
      response.url().includes('/api/v1/catalog/skus') &&
      new URL(response.url()).searchParams.get('keyword') === 'Moonlit Terrace',
  );
  await sales.getByRole('textbox', { name: 'Search catalog' }).fill('Moonlit Terrace');
  expect((await catalogResponse).ok()).toBe(true);
  await sales.getByRole('button', { name: 'Add to quote selection' }).first().click();
  await sales.getByRole('link', { name: 'Create quotation with selection' }).click();
  await sales.getByLabel('Active customer').click();
  await sales.getByText('Aurora Market Services · PAR-DEMO-QUOTATION').click();
  await sales.getByLabel('Address line').fill('88 Harbor Avenue');
  await sales.getByLabel('Line 1 discount rate').fill('0.0900');
  await sales.getByRole('button', { name: 'Save quotation draft' }).click();
  await expect(sales.getByRole('heading', { name: /QUO-\d{6}-\d{6}/ })).toBeVisible();
  const quotationPath = new URL(sales.url()).pathname;
  await sales.getByRole('button', { name: 'Evaluate routes' }).click();
  await sales.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(sales.getByText('PENDING APPROVAL')).toBeVisible();

  const manager = await login(browser, 'north.manager');
  await manager.goto(quotationPath);
  await manager.getByRole('button', { name: 'Review quotation' }).click();
  await manager
    .getByLabel('Approval reason')
    .fill('Commercial thresholds and route evidence reviewed');
  await manager.getByRole('button', { name: 'Record decision' }).click();
  await manager.getByRole('button', { name: 'Issue quotation' }).click();
  const publicLink = manager.getByRole('link', {
    name: 'Open the customer-safe quotation preview',
  });
  await expect(publicLink).toBeVisible();
  const publicPath = await publicLink.getAttribute('href');
  expect(publicPath).toMatch(/^\/portal\/quotations\/[A-Za-z0-9_-]{40,100}$/);
  await Promise.all([sales.context().close(), manager.context().close()]);
  return publicPath ?? '';
}

test('accepts once, renders a durable receipt, and refreshes without a duplicate command', async ({
  browser,
}) => {
  const publicPath = await createIssuedQuotation(browser);
  const customerContext = await browser.newContext();
  const customer = await customerContext.newPage();
  const browserErrors: string[] = [];
  let acceptanceRequests = 0;
  customer.on('pageerror', (error) => browserErrors.push(error.message));
  customer.on('console', (message) => {
    if (message.type() === 'error') browserErrors.push(message.text());
  });
  customer.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/acceptance')) {
      acceptanceRequests += 1;
    }
  });

  const portalResponse = await customer.goto(publicPath);
  expect(portalResponse?.status()).toBe(200);
  expect(portalResponse?.headers()['cache-control']).toBe('no-store');
  expect(portalResponse?.headers()['referrer-policy']).toBe('no-referrer');
  expect(portalResponse?.headers()['content-security-policy']).toContain("default-src 'self'");
  expect(portalResponse?.headers()['x-content-type-options']).toBe('nosniff');
  expect(portalResponse?.headers()['x-frame-options']).toBe('DENY');
  await expect(customer.getByRole('heading', { name: /Quotation QUO-/ })).toBeVisible();
  await expect(customer.getByText('Moonlit Terrace')).toBeVisible();
  await expect(customer.getByText('Estimated margin')).toHaveCount(0);
  await expect(customer.getByText('Weighted score')).toHaveCount(0);
  await expect(customer.getByText('ROUTE-2026-03')).toHaveCount(0);
  await customer.getByRole('checkbox').check();

  const acceptanceResponse = customer.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith('/acceptance'),
  );
  await customer.getByRole('button', { name: 'Accept quotation' }).evaluate((button) => {
    const acceptanceButton = button as HTMLButtonElement;
    acceptanceButton.click();
    acceptanceButton.click();
  });
  const response = await acceptanceResponse;
  expect(response.status()).toBe(201);
  expect(response.headers()['idempotency-replayed']).toBe('false');
  await expect(customer.getByText('Quotation accepted')).toBeVisible();
  await expect(
    customer.getByText(
      /Order creation is in progress|Order creation is retrying safely|order is ready to review/,
    ),
  ).toBeVisible();
  expect(acceptanceRequests).toBe(1);

  await customer.reload();
  await expect(customer.getByText('Quotation accepted')).toBeVisible();
  expect(acceptanceRequests).toBe(1);

  const aliasPath = publicPath.replace('/portal/quotations/', '/portal/quotes/');
  const aliasResponse = await customer.goto(aliasPath);
  expect(aliasResponse?.headers()['cache-control']).toBe('no-store');
  expect(aliasResponse?.headers()['referrer-policy']).toBe('no-referrer');
  await expect(customer.getByText('Quotation accepted')).toBeVisible();
  expect(acceptanceRequests).toBe(1);
  expect(browserErrors).toEqual([]);
  await customerContext.close();
});
