import { expect, test, type Browser, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';

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

async function openSelect(page: Page, label: string) {
  await page
    .locator('.ant-select')
    .filter({ has: page.getByLabel(label) })
    .click();
}

function markQuotationLegacy(quotationId: string) {
  expect(quotationId).toMatch(/^[0-9a-f-]{36}$/);
  execFileSync('docker', [
    'compose',
    '--project-name',
    'cellarbridge-quotation-e2e',
    '--env-file',
    '../.env.example',
    '--file',
    '../deploy/compose/core.compose.yaml',
    'exec',
    '-T',
    'postgres',
    'psql',
    '-U',
    'cellarbridge',
    '-d',
    'cellarbridge',
    '-v',
    'ON_ERROR_STOP=1',
    '-c',
    `UPDATE quotation.quotation_revision SET supply_decision_status = 'LEGACY_REEVALUATION_REQUIRED', supply_decision_schema_version = NULL, supply_decision_policy_version = NULL, supply_decision_at = NULL, supply_decision_hash = NULL, supply_decision_snapshot = NULL WHERE quotation_id = '${quotationId}'::uuid`,
  ]);
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
  await sales.page.getByLabel('Line 1 quantity').fill('6');
  await sales.page.getByLabel('Line 1 discount rate').fill('0.0900');
  await sales.page.getByRole('button', { name: 'Save quotation draft' }).click();

  await expect(sales.page.getByRole('heading', { name: /QUO-\d{6}-\d{6}/ })).toBeVisible();
  const quotationPath = new URL(sales.page.url()).pathname;
  await expect(sales.page.getByText('Supply has not been decided')).toBeVisible();

  const manager = await login(browser, 'north.manager');
  await manager.page.goto(quotationPath);
  await manager.page.getByRole('button', { name: 'Manager route override' }).click();
  await manager.page.getByLabel('Override route').click();
  await manager.page.getByText('NB BONDED B2B').click();
  await manager.page
    .getByLabel('Override reason')
    .fill('Use the approved bonded route for this customer');
  await manager.page.getByRole('button', { name: 'Record override' }).click();
  await expect(manager.page.getByText('Route-bound supply decision frozen')).toBeVisible();
  await expect(manager.page.getByText('SUPPLY-DECISION-2026-01')).toBeVisible();
  await expect(manager.page.getByText('Automatic (route eligible)').first()).toBeVisible();
  await expect(manager.page.getByText(/Selected route: NB BONDED B2B/).first()).toBeVisible();

  await sales.page.reload();
  await sales.page.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(sales.page.getByText('PENDING APPROVAL')).toBeVisible();
  await expect(
    sales.page.getByText(/Discount exceeds the automatic approval threshold/),
  ).toBeVisible();

  await manager.page.reload();
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
  await expect(manager.page.getByRole('button', { name: 'Accept quotation' })).toBeVisible();
  await expect(manager.page.getByRole('button', { name: 'Reject quotation' })).toBeVisible();
  await expect(manager.page.getByText('Ningbo bonded B2B delivery')).toBeVisible();
  await expect(
    manager.page.getByText(
      'Physical availability remains subject to successful inventory reservation after acceptance.',
    ),
  ).toBeVisible();
  await expect(manager.page.getByText('Estimated margin')).toHaveCount(0);
  await expect(manager.page.getByText('Weighted score')).toHaveCount(0);
  await expect(manager.page.getByText('ROUTE-2026-03')).toHaveCount(0);

  const quotationId = quotationPath.split('/').pop() ?? '';
  markQuotationLegacy(quotationId);
  await manager.page.reload();
  await expect(manager.page.getByText(/legacy quotation is view-only/i)).toBeVisible();
  await expect(manager.page.getByRole('button', { name: 'Accept quotation' })).toHaveCount(0);
  await expect(manager.page.getByRole('button', { name: 'Reject quotation' })).toHaveCount(0);
  const publicApiPath = `/api/v1${publicPath}`;
  const legacyView = await manager.page.request.get(publicApiPath);
  const termsVersion = ((await legacyView.json()) as { termsVersion: string }).termsVersion;
  const directAcceptance = await manager.page.request.post(`${publicApiPath}/acceptance`, {
    headers: { 'Idempotency-Key': 'accept-legacy-e2e-0001' },
    data: { acceptedTermsVersion: termsVersion },
  });
  expect(directAcceptance.status()).toBe(409);
  expect(((await directAcceptance.json()) as { code: string }).code).toBe(
    'QUOTE_SUPPLY_DECISION_REQUIRED',
  );

  expect(sales.browserErrors).toEqual([]);
  expect(manager.browserErrors).toEqual([]);
  await Promise.all([sales.page.context().close(), manager.page.context().close()]);
});

test('freezes an explicitly selected supply pool without fallback', async ({ browser }) => {
  const sales = await login(browser, 'north.sales');
  await sales.page.getByRole('menuitem', { name: 'Catalog & supply' }).click();
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

  await sales.page.getByLabel('Active customer').click();
  await sales.page.getByText('Aurora Market Services · PAR-DEMO-QUOTATION').click();
  await sales.page.getByLabel('Address line').fill('88 Harbor Avenue');
  await openSelect(sales.page, 'Line 1 supply strategy');
  await sales.page.keyboard.press('ArrowDown');
  await sales.page.keyboard.press('Enter');
  await openSelect(sales.page, 'Line 1 specific supply pool');
  await sales.page
    .locator('.ant-select-item-option')
    .filter({ hasText: /DOMESTIC ON HAND.*CASE/ })
    .click();
  await expect(
    sales.page
      .locator('.ant-select')
      .filter({ has: sales.page.getByLabel('Line 1 specific supply pool') }),
  ).toContainText(/Eastbank.*DOMESTIC ON HAND.*CASE.*HIGH/);
  await sales.page.getByRole('button', { name: 'Save quotation draft' }).click();
  await sales.page.getByRole('button', { name: 'Evaluate routes' }).click();

  await expect(sales.page.getByText('Route-bound supply decision frozen')).toBeVisible();
  await expect(sales.page.getByText('Specific pool').first()).toBeVisible();
  await expect(sales.page.getByText('DOMESTIC ON HAND').first()).toBeVisible();
  expect(sales.browserErrors).toEqual([]);
  await sales.page.context().close();
});
