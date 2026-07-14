import { expect, test, type Browser, type Page } from '@playwright/test';

const password = 'CellarBridge-Demo-2026!';
const forbiddenBuyerFields = new Set([
  'cost',
  'totalCost',
  'estimatedMarginRate',
  'margin',
  'score',
  'scores',
  'supplyPoolId',
  'warehouseId',
  'warehouse',
  'warehouseLabel',
  'lotId',
  'lotCode',
  'lots',
  'snapshotHash',
  'correlationId',
  'causationId',
  'sourceEventId',
  'sourceOwnerId',
  'acceptanceId',
  'partnerId',
  'orderLineId',
  'sourceQuotationLineId',
  'revisionId',
  'skuId',
  'sourceVersion',
  'version',
  'safeReason',
  'policyVersion',
  'internalComment',
  'internalComments',
  'comments',
]);

interface AuthenticatedPage {
  page: Page;
  browserErrors: string[];
}

interface CreatedOrder {
  id: string;
  number: string;
  path: string;
  browserErrors: string[];
}

function sanitizeBrowserError(message: string): string {
  return message
    .replace(/\/portal\/(?:quotations|quotes)\/[A-Za-z0-9_-]+/g, '/portal/quotations/[redacted]')
    .replace(/[A-Za-z0-9_-]{40,}/g, '[redacted]');
}

function observeBrowserErrors(page: Page, errors: string[]) {
  page.on('pageerror', (error) => errors.push(sanitizeBrowserError(error.message)));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(sanitizeBrowserError(message.text()));
  });
}

async function login(browser: Browser, username: string): Promise<AuthenticatedPage> {
  const context = await browser.newContext();
  const page = await context.newPage();
  const browserErrors: string[] = [];
  observeBrowserErrors(page, browserErrors);
  await page.goto('/app');
  await page.getByRole('button', { name: 'Continue with OIDC' }).click();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await expect(page.getByRole('heading', { name: 'System status' })).toBeVisible();
  return { page, browserErrors };
}

function formItem(page: Page, label: string) {
  return page
    .getByText(label, { exact: true })
    .locator(
      "xpath=ancestor::div[contains(concat(' ', normalize-space(@class), ' '), ' ant-form-item ')][1]",
    );
}

async function selectOption(page: Page, label: string, option: string) {
  const input = formItem(page, label).getByRole('combobox');
  await input.fill(option.replace(/ /g, '_'));
  await input.press('Enter');
}

async function addTag(page: Page, label: string, value: string) {
  const input = formItem(page, label).getByRole('combobox');
  await input.fill(value);
  await input.press('Enter');
}

async function createAndActivatePartner(
  sales: Page,
  manager: Page,
  unique: string,
): Promise<string> {
  const displayName = `Cedar Terrace ${unique}`;
  await sales.goto('/app/partners');
  await sales.getByRole('link', { name: 'Create partner' }).click();
  await sales.locator('input[name="legalName"]').fill(`${displayName} Limited`);
  await sales.locator('input[name="displayName"]').fill(displayName);
  await sales.locator('input[name="registrationIdentifier"]').fill(`CN72${unique}`);
  await sales.locator('input[name="requestedPaymentTermDays"]').fill('30');
  await selectOption(sales, 'Trade routes', 'SH GENERAL TRADE');
  await addTag(sales, 'Service regions', 'CN-SH');
  await sales.locator('input[name="contactName"]').fill('Mei Chen');
  await sales.locator('input[name="contactEmail"]').fill(`mei.chen.${unique}@example.test`);
  await sales.locator('input[name="province"]').fill('Shanghai');
  await sales.locator('input[name="city"]').fill('Shanghai');
  await sales.locator('input[name="line1"]').fill('208 Riverside Avenue');
  await sales.getByRole('button', { name: 'Save partner draft' }).click();
  await sales.getByRole('button', { name: 'Submit for review' }).click();
  await expect(sales.getByText('PENDING REVIEW', { exact: true })).toBeVisible();
  const partnerPath = new URL(sales.url()).pathname;

  await manager.goto(partnerPath);
  await manager.locator('#partner-review-reason').fill('Verified commercial scope and eligibility');
  await manager.getByRole('button', { name: 'Record review decision' }).click();
  await expect(manager.getByText('ACTIVE', { exact: true })).toBeVisible();
  return displayName;
}

function escaped(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

async function createIssuedQuotation(
  sales: Page,
  manager: Page,
  customerDisplayName: string,
): Promise<string> {
  await sales.goto('/app/catalog');
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
  await sales
    .getByText(new RegExp(`^${escaped(customerDisplayName)} · `))
    .last()
    .click();
  await sales.getByLabel('Address line').fill('88 Harbor Avenue');
  await sales.getByLabel('Line 1 discount rate').fill('0.0900');
  await sales.getByRole('button', { name: 'Save quotation draft' }).click();
  await expect(sales.getByRole('heading', { name: /QUO-\d{6}-\d{6}/ })).toBeVisible();
  const quotationPath = new URL(sales.url()).pathname;
  await sales.getByRole('button', { name: 'Evaluate routes' }).click();
  await sales.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(sales.getByText('PENDING APPROVAL')).toBeVisible();

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
  if (publicPath === null || !/^\/portal\/quotations\/[A-Za-z0-9_-]{40,100}$/.test(publicPath)) {
    throw new Error('A valid customer portal path was not issued');
  }
  return publicPath;
}

async function acceptAndAwaitOrder(browser: Browser, publicPath: string): Promise<CreatedOrder> {
  const context = await browser.newContext();
  const customer = await context.newPage();
  const browserErrors: string[] = [];
  observeBrowserErrors(customer, browserErrors);
  await customer.goto(publicPath);
  await expect(customer.getByRole('heading', { name: /Quotation QUO-/ })).toBeVisible();
  await customer.getByRole('checkbox').check();
  const acceptanceResponse = customer.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith('/acceptance'),
  );
  await customer.getByRole('button', { name: 'Accept quotation' }).click();
  expect((await acceptanceResponse).status()).toBe(201);
  await expect(customer.getByText('Quotation accepted')).toBeVisible();
  const orderLink = customer.getByRole('link', { name: /Sign in to view ORD-/ });
  await expect(orderLink).toBeVisible({ timeout: 45_000 });
  const orderPath = await orderLink.getAttribute('href');
  const orderText = await orderLink.textContent();
  if (
    orderPath === null ||
    orderText === null ||
    !/^\/app\/orders\/[0-9a-f-]{36}$/.test(orderPath) ||
    !/^Sign in to view ORD-\d{6}-\d{6}$/.test(orderText)
  ) {
    throw new Error('The accepted quotation did not expose a valid secured order link');
  }
  const created = {
    id: orderPath.slice(orderPath.lastIndexOf('/') + 1),
    number: orderText.replace('Sign in to view ', ''),
    path: orderPath,
    browserErrors,
  };
  await context.close();
  return created;
}

async function authenticatedGetStatus(page: Page, path: string): Promise<number> {
  const accessToken = await page.evaluate(() => {
    const storedUser = Object.entries(window.sessionStorage)
      .filter(([key]) => key.startsWith('oidc.user:'))
      .map(([, value]) => JSON.parse(value) as { access_token?: string })
      .find((candidate) => candidate.access_token !== undefined);
    if (storedUser?.access_token === undefined) {
      throw new Error('Authenticated OIDC session is unavailable');
    }
    return storedUser.access_token;
  });
  const response = await page.context().request.get(path, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  return response.status();
}

function collectObjectKeys(value: unknown, keys: string[] = []): string[] {
  if (Array.isArray(value)) {
    value.forEach((item) => collectObjectKeys(item, keys));
  } else if (value !== null && typeof value === 'object') {
    Object.entries(value).forEach(([key, item]) => {
      keys.push(key);
      collectObjectKeys(item, keys);
    });
  }
  return keys;
}

test('converts accepted quotations once and enforces Buyer, partner, and tenant order scope', async ({
  browser,
}) => {
  const sales = await login(browser, 'north.sales');
  const manager = await login(browser, 'north.manager');

  const mappedPortalPath = await createIssuedQuotation(
    sales.page,
    manager.page,
    'Aurora Market Services',
  );
  const mappedOrder = await acceptAndAwaitOrder(browser, mappedPortalPath);

  const unique = Date.now().toString();
  const otherPartner = await createAndActivatePartner(sales.page, manager.page, unique);
  const otherPortalPath = await createIssuedQuotation(sales.page, manager.page, otherPartner);
  const otherOrder = await acceptAndAwaitOrder(browser, otherPortalPath);

  const buyer = await login(browser, 'north.buyer');
  const buyerResponsePromise = buyer.page.waitForResponse(
    (response) => new URL(response.url()).pathname === `/api/v1/buyer/orders/${mappedOrder.id}`,
  );
  await buyer.page.goto(mappedOrder.path);
  const buyerResponse = await buyerResponsePromise;
  expect(buyerResponse.status()).toBe(200);
  const buyerBody = (await buyerResponse.json()) as unknown;
  const leakedFields = collectObjectKeys(buyerBody).filter((key) => forbiddenBuyerFields.has(key));
  expect(leakedFields).toEqual([]);
  await expect(buyer.page.getByRole('heading', { name: mappedOrder.number })).toBeVisible();
  await expect(buyer.page.getByText('Buyer-safe view')).toBeVisible();
  await expect(buyer.page.getByText('Conversion evidence')).toHaveCount(0);
  await expect(buyer.page.getByText('Snapshot hash')).toHaveCount(0);

  const internalBoundaryStatus = await authenticatedGetStatus(
    buyer.page,
    `/api/v1/orders/${mappedOrder.id}`,
  );
  expect(internalBoundaryStatus).toBe(403);
  const crossPartnerStatus = await authenticatedGetStatus(
    buyer.page,
    `/api/v1/buyer/orders/${otherOrder.id}`,
  );
  expect(crossPartnerStatus).toBe(404);

  const harborManager = await login(browser, 'harbor.manager');
  const crossTenantStatus = await authenticatedGetStatus(
    harborManager.page,
    `/api/v1/orders/${mappedOrder.id}`,
  );
  expect(crossTenantStatus).toBe(404);

  expect(mappedOrder.browserErrors).toEqual([]);
  expect(otherOrder.browserErrors).toEqual([]);
  expect(sales.browserErrors).toEqual([]);
  expect(manager.browserErrors).toEqual([]);
  expect(buyer.browserErrors).toEqual([]);
  expect(harborManager.browserErrors).toEqual([]);
  await Promise.all([
    sales.page.context().close(),
    manager.page.context().close(),
    buyer.page.context().close(),
    harborManager.page.context().close(),
  ]);
});
