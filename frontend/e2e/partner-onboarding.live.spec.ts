import { expect, test, type Browser, type Page } from '@playwright/test';

const password = 'CellarBridge-Demo-2026!';

interface AuthenticatedPage {
  page: Page;
  browserErrors: string[];
}

interface PartnerProfile {
  legalName: string;
  displayName: string;
  registrationIdentifier: string;
  contactEmail: string;
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

async function createAndSubmit(page: Page, profile: PartnerProfile): Promise<string> {
  await page.goto('/app/partners');
  await page.getByRole('link', { name: 'Create partner' }).click();
  await expect(page.getByRole('heading', { name: 'Create partner draft' })).toBeVisible();

  await page.locator('input[name="legalName"]').fill(profile.legalName);
  await page.locator('input[name="displayName"]').fill(profile.displayName);
  await page.locator('input[name="registrationIdentifier"]').fill(profile.registrationIdentifier);
  await page.locator('input[name="requestedPaymentTermDays"]').fill('30');
  await selectOption(page, 'Trade routes', 'SH GENERAL TRADE');
  await addTag(page, 'Service regions', 'CN-SH');
  await page.locator('input[name="contactName"]').fill('Lin Mei');
  await page.locator('input[name="contactEmail"]').fill(profile.contactEmail);
  await page.locator('input[name="province"]').fill('Shanghai');
  await page.locator('input[name="city"]').fill('Shanghai');
  await page.locator('input[name="line1"]').fill('208 Riverside Avenue');
  await page.getByRole('button', { name: 'Save partner draft' }).click();

  await expect(page.getByRole('heading', { name: profile.displayName })).toBeVisible();
  await page.getByRole('button', { name: 'Submit for review' }).click();
  await expect(page.getByText('PENDING REVIEW', { exact: true })).toBeVisible();
  return new URL(page.url()).pathname;
}

test('activates an independently reviewed partner and rejects submitter self-review', async ({
  browser,
}) => {
  const unique = Date.now().toString();
  const sales = await login(browser, 'north.sales');
  const salesPath = await createAndSubmit(sales.page, {
    legalName: `Willow Terrace ${unique} Limited`,
    displayName: `Willow Terrace ${unique}`,
    registrationIdentifier: `CN31${unique}`,
    contactEmail: `lin.mei.${unique}@example.test`,
  });

  const manager = await login(browser, 'north.manager');
  await manager.page.goto(salesPath);
  await expect(manager.page.getByText('Independent review', { exact: true })).toBeVisible();
  await manager.page.locator('#partner-review-reason').fill('Verified commercial profile');
  await manager.page.getByRole('button', { name: 'Record review decision' }).click();
  await expect(manager.page.getByText('ACTIVE', { exact: true })).toBeVisible();
  await expect(manager.page.getByText('Approved eligibility · version 1')).toBeVisible();

  await sales.page.goto(salesPath);
  await expect(sales.page.getByText('ACTIVE', { exact: true })).toBeVisible();

  const admin = await login(browser, 'north.admin');
  await createAndSubmit(admin.page, {
    legalName: `Silver Harbor ${unique} Limited`,
    displayName: `Silver Harbor ${unique}`,
    registrationIdentifier: `CN91${unique}`,
    contactEmail: `mei.chen.${unique}@example.test`,
  });
  await expect(admin.page.getByText('Independent review', { exact: true })).toBeVisible();
  await admin.page.locator('#partner-review-reason').fill('Independent approval attempted');
  await admin.page.getByRole('button', { name: 'Record review decision' }).click();
  await expect(
    admin.page.getByText('Submitter cannot review this partner', { exact: true }),
  ).toBeVisible();
  await expect(admin.page.getByText('PENDING REVIEW', { exact: true })).toBeVisible();

  expect(sales.browserErrors).toEqual([]);
  expect(manager.browserErrors).toEqual([]);
  expect(admin.browserErrors).toHaveLength(1);
  expect(admin.browserErrors[0]).toContain('409');
  await Promise.all([
    sales.page.context().close(),
    manager.page.context().close(),
    admin.page.context().close(),
  ]);
});
