import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { expect, test, type Browser, type Page } from '@playwright/test';

const composeFile = requiredEnvironment('PERFORMANCE_COMPOSE_FILE');
const environmentFile = requiredEnvironment('PERFORMANCE_ENV_FILE');
const projectName = requiredEnvironment('PERFORMANCE_COMPOSE_PROJECT');
const resultDirectory = requiredEnvironment('PERFORMANCE_RESULT_DIR');

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (value === undefined || value.length === 0) throw new Error(`${name} is required`);
  return value;
}

function compose(...args: string[]): string {
  return execFileSync(
    'docker',
    [
      'compose',
      '--project-name',
      projectName,
      '--env-file',
      environmentFile,
      '--file',
      composeFile,
      ...args,
    ],
    { encoding: 'utf8' },
  ).trim();
}

function keycloakHealth(): string {
  const containerId = compose('ps', '--all', '-q', 'keycloak');
  if (containerId.length === 0) return 'missing';
  const state = execFileSync('docker', ['inspect', '--format', '{{.State.Status}}', containerId], {
    encoding: 'utf8',
  }).trim();
  if (state !== 'running') return state;
  return execFileSync(
    'docker',
    [
      'inspect',
      '--format',
      '{{if .State.Health}}{{.State.Health.Status}}{{else}}running{{end}}',
      containerId,
    ],
    { encoding: 'utf8' },
  ).trim();
}

async function login(browser: Browser): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/app');
  await page.getByRole('button', { name: 'Continue with OIDC' }).click();
  await page.locator('#username').fill('north.sales');
  await page.locator('#password').fill('CellarBridge-Demo-2026!');
  await page.locator('#kc-login').click();
  await expect(page.getByRole('heading', { name: 'System status' })).toBeVisible();
  return page;
}

async function accessToken(page: Page): Promise<string> {
  return page.evaluate(() => {
    const user = Object.entries(window.sessionStorage)
      .filter(([key]) => key.startsWith('oidc.user:'))
      .map(([, value]) => JSON.parse(value) as { access_token?: string })
      .find((candidate) => candidate.access_token !== undefined);
    if (user?.access_token === undefined) throw new Error('OIDC access token is unavailable');
    return user.access_token;
  });
}

async function authenticatedMe(page: Page, token: string): Promise<number> {
  const response = await page.context().request.get('http://127.0.0.1:8080/api/v1/me', {
    headers: { Authorization: `Bearer ${token}` },
  });
  return response.status();
}

test('keeps cached JWK validation available during a bounded Keycloak outage', async ({
  browser,
}) => {
  const originalPage = await login(browser);
  const originalToken = await accessToken(originalPage);
  expect(await authenticatedMe(originalPage, originalToken)).toBe(200);

  let keycloakStopped = false;
  let cachedTokenStatus: number | undefined;
  let tokenEndpointUnavailable = false;
  try {
    compose('stop', 'keycloak');
    keycloakStopped = true;
    await expect.poll(keycloakHealth).toBe('exited');

    try {
      await fetch('http://127.0.0.1:8081/realms/cellarbridge/protocol/openid-connect/token', {
        method: 'POST',
      });
    } catch {
      tokenEndpointUnavailable = true;
    }
    expect(tokenEndpointUnavailable).toBe(true);

    cachedTokenStatus = await authenticatedMe(originalPage, originalToken);
    expect(cachedTokenStatus).toBe(200);
  } finally {
    if (keycloakStopped) {
      compose('start', 'keycloak');
      await expect.poll(keycloakHealth, { timeout: 180_000 }).toBe('healthy');
    }
  }

  const recoveredPage = await login(browser);
  const recoveredToken = await accessToken(recoveredPage);
  const recoveredTokenStatus = await authenticatedMe(recoveredPage, recoveredToken);
  expect(recoveredTokenStatus).toBe(200);

  const result = {
    schemaVersion: 'cellarbridge.resilience-result.v1',
    scenario: 'keycloak-jwk-cache-outage',
    executed: true,
    passed: true,
    assertions: {
      initialTokenValidatedAndJwkCacheWarmed: true,
      tokenEndpointUnavailableDuringOutage: tokenEndpointUnavailable,
      cachedTokenAcceptedDuringOutage: cachedTokenStatus === 200,
      newLoginAndTokenAcceptedAfterRecovery: recoveredTokenStatus === 200,
    },
  };
  mkdirSync(resultDirectory, { recursive: true });
  writeFileSync(
    path.join(resultDirectory, 'keycloak-outage.json'),
    `${JSON.stringify(result, null, 2)}\n`,
    'utf8',
  );

  await Promise.all([originalPage.context().close(), recoveredPage.context().close()]);
});
