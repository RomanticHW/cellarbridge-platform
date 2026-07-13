import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthSessionProvider } from '../features/identity-access/AuthSessionProvider';
import type { AuthSession } from '../features/identity-access/authSession';
import { Application } from './App';
import { AppErrorBoundary } from './AppErrorBoundary';
import { router } from './router';

const currentUser = {
  userId: '11200000-0000-4000-8000-000000000001',
  displayName: 'North Sales',
  tenant: {
    id: '10000000-0000-4000-8000-000000000001',
    name: 'North Cellars',
    status: 'ACTIVE',
  },
  roles: ['Sales Representative'],
  permissions: ['partner:read', 'catalog:read'],
} as const;

const authenticatedSession: AuthSession = {
  isLoading: false,
  isAuthenticated: true,
  accessToken: 'test-access-token',
  sessionIdentity: '11000000-0000-4000-8000-000000000001',
  signIn: vi.fn(),
  signOut: vi.fn(),
  clearSession: vi.fn(),
};

function requestPath(input: string | URL | Request): string {
  if (input instanceof Request) return new URL(input.url).pathname;
  return new URL(input.toString(), window.location.origin).pathname;
}

function renderApplication(session: AuthSession = authenticatedSession) {
  return render(
    <AuthSessionProvider value={session}>
      <Application />
    </AuthSessionProvider>,
  );
}

function BrokenView(): never {
  throw new Error('render failure');
}

describe('application shell', () => {
  beforeEach(async () => {
    vi.restoreAllMocks();
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = requestPath(input);
      const body = path === '/api/v1/me' ? currentUser : { status: 'UP' };
      return Promise.resolve(
        new Response(JSON.stringify(body), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    });
    await router.navigate('/app');
  });

  it('protects application routes and preserves the intended return path', async () => {
    renderApplication({
      ...authenticatedSession,
      isAuthenticated: false,
      accessToken: undefined,
      sessionIdentity: undefined,
    });

    expect(await screen.findByRole('heading', { name: 'Sign in to Operations' })).toBeVisible();
    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.location.state).toEqual({ returnTo: '/app' });
  });

  it('renders tenant identity and permission-aware navigation', async () => {
    const user = userEvent.setup();
    renderApplication();

    expect(await screen.findByRole('heading', { name: 'System status' })).toBeVisible();
    expect(screen.getByText('North Cellars')).toBeVisible();
    const navigation = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(within(navigation).getByText('Partners')).toBeVisible();
    expect(within(navigation).queryByText('Settlement')).not.toBeInTheDocument();

    await user.click(screen.getByText('Identity & access'));
    expect(await screen.findByRole('heading', { name: 'Access profile' })).toBeVisible();
    expect(screen.getByText('Sales Representative')).toBeVisible();
  });

  it('routes a locally denied identity mapping to the forbidden page', async () => {
    vi.mocked(globalThis.fetch).mockResolvedValue(
      new Response(
        JSON.stringify({
          type: 'about:blank',
          title: 'Access denied',
          status: 403,
          code: 'ACCESS_DENIED',
          traceId: 'test-trace',
          retryable: false,
        }),
        { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    );

    renderApplication();

    expect(await screen.findByText('Access forbidden')).toBeVisible();
    expect(router.state.location.pathname).toBe('/forbidden');
  });

  it('renders the not-found route without resolving protected data', async () => {
    await router.navigate('/not-part-of-foundation');
    renderApplication();

    expect(await screen.findByText('Page not found')).toBeVisible();
    expect(screen.getByRole('link', { name: 'Return to system status' })).toHaveAttribute(
      'href',
      '/app',
    );
  });

  it('contains render failures without exposing error details', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    render(
      <AppErrorBoundary>
        <BrokenView />
      </AppErrorBoundary>,
    );

    expect(screen.getByText('The operations console could not be displayed')).toBeVisible();
    expect(screen.queryByText('render failure')).not.toBeInTheDocument();
    expect(consoleError).toHaveBeenCalled();
  });
});
