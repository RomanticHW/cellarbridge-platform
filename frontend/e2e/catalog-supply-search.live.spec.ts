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
  const businessWrites: string[] = [];
  const observeBusinessWrites = ({ page }: AuthenticatedPage) => {
    page.on('request', (request) => {
      if (
        request.url().includes('/api/v1/') &&
        ['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method())
      ) {
        businessWrites.push(`${request.method()} ${request.url()}`);
      }
    });
  };
  const sales = await login(browser, 'north.sales');
  observeBusinessWrites(sales);

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
  await expect(sales.page.getByLabel('CASE supply at Eastbank · Shanghai')).toBeVisible();
  await expect(sales.page.getByLabel('BOTTLE supply at Eastbank · Shanghai')).toBeVisible();
  await expect(sales.page.getByText('HIGH', { exact: true }).first()).toBeVisible();
  await expect(sales.page.getByText(/not an inventory commitment/i)).toBeVisible();
  await expect(sales.page.getByText('Warehouse priority is readiness evidence only')).toHaveCount(
    0,
  );
  await sales.page.getByRole('button', { name: 'Add to quote selection' }).first().click();
  await expect(sales.page.getByText('Pending quote selection (1)')).toBeVisible();
  await expect(
    sales.page.getByText(/No quotation, reservation, or order has been created/),
  ).toBeVisible();

  const warehouse = await login(browser, 'north.warehouse');
  observeBusinessWrites(warehouse);
  await warehouse.page.goto(
    '/app/catalog?keyword=Moonlit%20Terrace&supplyType=DOMESTIC_ON_HAND&quantityUnit=CASE',
  );
  await expect(warehouse.page.getByLabel('CASE supply at Eastbank · Shanghai')).toBeVisible();
  await warehouse.page.getByRole('button', { name: '2 authorized lots' }).click();
  await expect(
    warehouse.page.getByText('Warehouse priority is readiness evidence only'),
  ).toBeVisible();
  await expect(warehouse.page.getByRole('columnheader', { name: 'Priority' })).toBeVisible();
  await expect(
    warehouse.page.getByRole('columnheader', { name: 'Warehouse version' }),
  ).toBeVisible();
  await expect(warehouse.page.getByRole('cell', { name: '10' }).first()).toBeVisible();

  await warehouse.page.goto('/app/catalog?supplyType=HONG_KONG_ON_HAND');
  await expect(warehouse.page.getByText('Harbor Quay · Hong Kong').first()).toBeVisible();
  await expect(warehouse.page.getByRole('button', { name: /authorized lot/ })).toHaveCount(0);

  const harbor = await login(browser, 'harbor.manager');
  observeBusinessWrites(harbor);
  await harbor.page.goto('/app/catalog?keyword=Moonlit%20Terrace');
  await expect(harbor.page.getByText('HB-MTV-2019-750X6')).toBeVisible();
  await expect(harbor.page.getByText('CB-MTV-2019-750X6')).toHaveCount(0);

  const buyer = await login(browser, 'north.buyer');
  observeBusinessWrites(buyer);
  await expect(buyer.page.getByRole('menuitem', { name: 'Catalog & supply' })).toHaveCount(0);
  await buyer.page.goto('/app/catalog');
  await expect(buyer.page.getByText('Catalog supply access denied')).toBeVisible();

  expect(businessWrites).toEqual([]);
  expect(sales.browserErrors).toEqual([]);
  expect(warehouse.browserErrors).toEqual([]);
  expect(harbor.browserErrors).toEqual([]);
  expect(buyer.browserErrors.every((message) => message.includes('403'))).toBe(true);
  await Promise.all([sales, warehouse, harbor, buyer].map(({ page }) => page.context().close()));
});
