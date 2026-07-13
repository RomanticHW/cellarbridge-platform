import { expect, test, type Browser, type Page } from '@playwright/test';

interface MeResponse {
  status: number;
  tenantName?: string;
  tenantId?: string;
}

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
  await page.locator('#password').fill('CellarBridge-Demo-2026!');
  await page.locator('#kc-login').click();
  await expect(page.getByRole('heading', { name: 'System status' })).toBeVisible();
  return { page, browserErrors };
}

async function requestAsCurrentSession(
  page: Page,
  path: string,
  extraHeaders: Record<string, string> = {},
): Promise<MeResponse> {
  return page.evaluate(
    async ({ requestPath, headers }) => {
      const storedUser = Object.entries(window.sessionStorage)
        .filter(([key]) => key.startsWith('oidc.user:'))
        .map(([, value]) => JSON.parse(value) as { access_token?: string })
        .find((candidate) => candidate.access_token !== undefined);
      if (storedUser?.access_token === undefined) {
        throw new Error('Authenticated OIDC session is unavailable');
      }
      const response = await fetch(requestPath, {
        headers: { Authorization: `Bearer ${storedUser.access_token}`, ...headers },
      });
      const body = (await response.json()) as {
        tenant?: { id?: string; name?: string };
      };
      return {
        status: response.status,
        tenantName: body.tenant?.name,
        tenantId: body.tenant?.id,
      };
    },
    { requestPath: path, headers: extraHeaders },
  );
}

test('keeps two authenticated browser contexts isolated by verified tenant mapping', async ({
  browser,
}) => {
  const northSession = await login(browser, 'north.sales');
  const harborSession = await login(browser, 'harbor.manager');
  const northPage = northSession.page;
  const harborPage = harborSession.page;

  await expect(northPage.getByText('North Cellars')).toBeVisible();
  await expect(harborPage.getByText('Harbor Cellars')).toBeVisible();

  const northMe = await requestAsCurrentSession(northPage, '/api/v1/me', {
    'X-Tenant-Id': '20000000-0000-4000-8000-000000000001',
  });
  const harborMe = await requestAsCurrentSession(harborPage, '/api/v1/me');

  expect(northMe).toEqual({
    status: 200,
    tenantName: 'North Cellars',
    tenantId: '10000000-0000-4000-8000-000000000001',
  });
  expect(harborMe).toEqual({
    status: 200,
    tenantName: 'Harbor Cellars',
    tenantId: '20000000-0000-4000-8000-000000000001',
  });
  expect(northSession.browserErrors).toEqual([]);
  expect(harborSession.browserErrors).toEqual([]);

  const guessedTenantResource = await requestAsCurrentSession(
    northPage,
    '/api/v1/tenants/20000000-0000-4000-8000-000000000001',
  );
  expect(guessedTenantResource.status).toBe(403);
  expect(northSession.browserErrors).toHaveLength(1);
  expect(northSession.browserErrors[0]).toContain('403');

  await Promise.all([northPage.context().close(), harborPage.context().close()]);
});
