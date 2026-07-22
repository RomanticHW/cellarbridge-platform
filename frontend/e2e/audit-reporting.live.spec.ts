import { expect, test, type APIResponse, type Browser, type Page } from '@playwright/test';

const password = 'CellarBridge-Demo-2026!';

async function login(browser: Browser, username: string) {
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

async function oidcAccessToken(page: Page): Promise<string> {
  return page.evaluate(() => {
    const storedUser = Object.entries(window.sessionStorage)
      .filter(([key]) => key.startsWith('oidc.user:'))
      .map(([, value]) => JSON.parse(value) as { access_token?: string })
      .find((candidate) => candidate.access_token !== undefined);
    if (storedUser?.access_token === undefined) {
      throw new Error('Authenticated OIDC session is unavailable');
    }
    return storedUser.access_token;
  });
}

async function authenticatedGet(page: Page, path: string): Promise<APIResponse> {
  return page.context().request.get(path, {
    headers: { Authorization: `Bearer ${await oidcAccessToken(page)}` },
  });
}

async function awaitWorkItem(page: Page, quotationNumber: string) {
  await expect
    .poll(
      async () => {
        const response = await authenticatedGet(
          page,
          `/api/v1/work-items?scope=team&subjectNumber=${encodeURIComponent(quotationNumber)}&pageSize=100`,
        );
        if (response.status() !== 200) return `HTTP_${response.status()}`;
        const body = (await response.json()) as {
          items?: { subjectNumber: string; status: string }[];
        };
        return (
          body.items?.find((item) => item.subjectNumber === quotationNumber)?.status ?? 'MISSING'
        );
      },
      { timeout: 45_000 },
    )
    .toBe('OPEN');
}

async function awaitTimeline(page: Page, quotationId: string) {
  await expect
    .poll(
      async () => {
        const response = await authenticatedGet(
          page,
          `/api/v1/timeline?subjectType=QUOTATION&subjectId=${quotationId}&pageSize=50`,
        );
        if (response.status() !== 200) return `HTTP_${response.status()}`;
        const body = (await response.json()) as { items?: { eventType: string }[] };
        return body.items?.some((item) => item.eventType === 'cellarbridge.quotation.accepted.v1')
          ? 'ACCEPTED'
          : 'PENDING';
      },
      { timeout: 45_000 },
    )
    .toBe('ACCEPTED');
}

async function awaitDashboard(page: Page) {
  const today = new Date().toISOString().slice(0, 10);
  await expect
    .poll(
      async () => {
        const response = await authenticatedGet(
          page,
          `/api/v1/dashboard?from=${today}&to=${today}`,
        );
        if (response.status() !== 200) return -1;
        const body = (await response.json()) as { metrics?: { quotationCount?: number } };
        return Number(body.metrics?.quotationCount ?? 0);
      },
      { timeout: 45_000 },
    )
    .toBeGreaterThan(0);
}

test('projects the quotation mainline into work, dashboard, audit, and timeline views', async ({
  browser,
}) => {
  const sales = await login(browser, 'north.sales');
  await sales.page.goto('/app/catalog');
  const catalogResponse = sales.page.waitForResponse(
    (response) =>
      response.url().includes('/api/v1/catalog/skus') &&
      new URL(response.url()).searchParams.get('keyword') === 'Moonlit Terrace',
  );
  await sales.page.getByRole('textbox', { name: 'Search catalog' }).fill('Moonlit Terrace');
  expect((await catalogResponse).ok()).toBe(true);
  await sales.page.getByRole('button', { name: 'Add to quote selection' }).first().click();
  await sales.page.getByRole('link', { name: 'Create quotation with selection' }).click();
  await sales.page.getByLabel('Active customer').click();
  await sales.page.getByText('Aurora Market Services · PAR-DEMO-QUOTATION').click();
  await sales.page.getByLabel('Address line').fill('88 Harbor Avenue');
  await sales.page.getByLabel('Line 1 discount rate').fill('0.0900');
  await sales.page.getByRole('button', { name: 'Save quotation draft' }).click();
  const quotationHeading = sales.page.getByRole('heading', { name: /QUO-\d{6}-\d{6}/ });
  await expect(quotationHeading).toBeVisible();
  const quotationNumber = (await quotationHeading.textContent())?.trim() ?? '';
  expect(quotationNumber).toMatch(/^QUO-\d{6}-\d{6}$/);
  const quotationPath = new URL(sales.page.url()).pathname;
  const quotationId = quotationPath.slice(quotationPath.lastIndexOf('/') + 1);
  await sales.page.getByRole('button', { name: 'Evaluate routes' }).click();
  await sales.page.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(sales.page.getByText('PENDING APPROVAL')).toBeVisible();

  const manager = await login(browser, 'north.manager');
  await awaitWorkItem(manager.page, quotationNumber);
  await manager.page.goto('/app/work-items');
  await manager.page.getByText('Team work', { exact: true }).click();
  await manager.page.getByRole('textbox', { name: 'Subject number' }).fill(quotationNumber);
  await expect(manager.page.getByRole('cell', { name: quotationNumber })).toBeVisible();
  await expect(manager.page.getByText('Approve quotation')).toBeVisible();

  await manager.page.goto(quotationPath);
  await manager.page.getByRole('button', { name: 'Review quotation' }).click();
  await manager.page
    .getByLabel('Approval reason')
    .fill('Commercial thresholds and route evidence reviewed');
  await manager.page.getByRole('button', { name: 'Record decision' }).click();
  await manager.page.getByRole('button', { name: 'Issue quotation' }).click();
  const publicLink = manager.page.getByRole('link', {
    name: 'Open the customer-safe quotation preview',
  });
  await expect(publicLink).toBeVisible();
  const publicPath = await publicLink.getAttribute('href');
  expect(publicPath).toMatch(/^\/portal\/quotations\/[A-Za-z0-9_-]{40,100}$/);

  const customerContext = await browser.newContext();
  const customer = await customerContext.newPage();
  await customer.goto(publicPath ?? '');
  await customer.getByRole('checkbox').check();
  const acceptanceResponse = customer.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith('/acceptance'),
  );
  await customer.getByRole('button', { name: 'Accept quotation' }).click();
  expect((await acceptanceResponse).status()).toBe(201);
  await expect(customer.getByText('Quotation accepted')).toBeVisible();

  await awaitTimeline(manager.page, quotationId);
  await awaitDashboard(manager.page);
  await manager.page.goto('/app/dashboard');
  await expect(manager.page.getByRole('heading', { name: 'Business dashboard' })).toBeVisible();
  await expect(
    manager.page
      .locator('.ant-statistic')
      .filter({ hasText: 'Quotations' })
      .locator('.ant-statistic-content-value'),
  ).not.toHaveText('0');
  await expect(manager.page.getByText('Route distribution').first()).toBeVisible();

  await manager.page.goto('/app/audit');
  await expect(manager.page.getByRole('heading', { name: 'Audit search' })).toBeVisible();
  await expect(manager.page.getByRole('cell', { name: quotationNumber }).first()).toBeVisible();

  await manager.page.goto(quotationPath);
  await expect(manager.page.getByText('Unified business timeline')).toBeVisible();
  await expect(manager.page.getByText(/quotation accepted/i).first()).toBeVisible();

  expect(sales.browserErrors).toEqual([]);
  expect(manager.browserErrors).toEqual([]);
  await Promise.all([
    sales.page.context().close(),
    manager.page.context().close(),
    customerContext.close(),
  ]);
});
