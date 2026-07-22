import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Application } from '../../app/App';
import { router } from '../../app/router';
import { AuthSessionProvider } from '../identity-access/AuthSessionProvider';
import type { AuthSession } from '../identity-access/authSession';

vi.mock('echarts/core', () => ({
  use: vi.fn(),
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}));

const currentUser = {
  userId: '11200000-0000-4000-8000-000000000004',
  displayName: 'North Operations',
  partnerId: null,
  tenant: {
    id: '10000000-0000-4000-8000-000000000001',
    name: 'North Cellars',
    status: 'ACTIVE',
  },
  roles: ['Tenant Administrator'],
  permissions: ['reporting:read', 'audit:read'],
};

const session: AuthSession = {
  isLoading: false,
  isAuthenticated: true,
  accessToken: 'test-access-token',
  sessionIdentity: '11200000-0000-4000-8000-000000000004',
  signIn: vi.fn(),
  signOut: vi.fn(),
  clearSession: vi.fn(),
};

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function request(input: string | URL | Request) {
  return input instanceof Request ? input : new Request(input);
}

function renderApplication() {
  return render(
    <AuthSessionProvider value={session}>
      <Application />
    </AuthSessionProvider>,
  );
}

describe('reporting workspace', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('shows loading, stale evidence, accessible charts and table fallbacks', async () => {
    let releaseDashboard: ((value: Response) => void) | undefined;
    const pendingDashboard = new Promise<Response>((resolve) => {
      releaseDashboard = resolve;
    });
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(request(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return pendingDashboard;
    });
    await router.navigate('/app/dashboard');
    renderApplication();

    expect(
      await screen.findByText('Loading operational dashboard…', {}, { timeout: 5_000 }),
    ).toBeVisible();
    releaseDashboard?.(
      response({
        from: '2026-07-01',
        to: '2026-07-22',
        generatedAt: '2026-07-22T12:00:30Z',
        dataAsOf: '2026-07-22T12:00:00Z',
        projectionLagSeconds: 30,
        projectionStatus: 'STALE',
        metrics: {
          quotationCount: 12,
          approvalBacklog: 2,
          quoteToOrderConversion: 0.5,
          idempotencyHits: 3,
          reservationEvents: 5,
          openExceptions: 1,
          overdueWorkItems: 1,
          receivableEvents: 4,
        },
        charts: {
          routeDistribution: [{ label: 'NB_BONDED_B2B', value: 7 }],
          reservationResults: [{ label: 'CONFIRMED', value: 4 }],
        },
      }),
    );

    expect(await screen.findByRole('heading', { name: 'Business dashboard' })).toBeVisible();
    expect(screen.getByText('Projection is catching up')).toBeVisible();
    expect(screen.getByRole('img', { name: 'Route distribution chart' })).toBeVisible();
    expect(screen.getByRole('img', { name: 'Reservation outcomes chart' })).toBeVisible();
    expect(document.querySelector('table[aria-label="Route distribution data"]')).not.toBeNull();
  });

  it('shows a safe empty projection when no event facts are available', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(request(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return Promise.resolve(
        response({
          from: '2026-07-01',
          to: '2026-07-22',
          generatedAt: '2026-07-22T12:00:30Z',
          dataAsOf: null,
          projectionLagSeconds: 0,
          projectionStatus: 'EMPTY',
          metrics: { quotationCount: '2' },
          charts: { routeDistribution: [{ label: 42, value: 'invalid' }] },
        }),
      );
    });
    await router.navigate('/app/dashboard');
    renderApplication();

    expect(await screen.findByText('No projected events yet')).toBeVisible();
    expect(screen.getByText(/Data as of/)).toHaveTextContent('Data as of not available');
    expect(screen.getAllByText('No data in the selected period.')).toHaveLength(5);
    expect(
      screen
        .getByText('Quotations')
        .closest('.ant-statistic')
        ?.querySelector('.ant-statistic-content-value'),
    ).toHaveTextContent('2');
  });

  it('renders the permission-filtered work table', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(request(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return Promise.resolve(
        response({
          scope: 'PERSONAL',
          items: [
            {
              id: '41000000-0000-4000-8000-000000000001',
              type: 'QUOTATION_APPROVAL',
              subjectType: 'QUOTATION',
              subjectId: '42000000-0000-4000-8000-000000000001',
              subjectNumber: 'QUO-202607-000021',
              title: 'Review quotation',
              safeSummary: 'Quotation approval is pending',
              priority: 'HIGH',
              status: 'OPEN',
              candidateRole: 'sales-manager',
              assigneeUserId: null,
              dueAt: '2026-07-23T12:00:00Z',
              createdAt: '2026-07-22T12:00:00Z',
              completedAt: null,
              version: 0,
            },
          ],
        }),
      );
    });
    await router.navigate('/app/work-items');
    renderApplication();

    expect(await screen.findByRole('heading', { name: 'Work queue' })).toBeVisible();
    expect(screen.getByText('QUO-202607-000021')).toBeVisible();
    expect(screen.getByText('Quotation approval is pending')).toBeVisible();
    expect(screen.getByRole('table')).toBeVisible();
  });

  it('renders authorized immutable audit evidence', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(request(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return Promise.resolve(
        response({
          items: [
            {
              id: '51000000-0000-4000-8000-000000000001',
              occurredAt: '2026-07-22T12:00:00Z',
              module: 'quotation',
              action: 'QUOTATION_APPROVED',
              outcome: 'SUCCEEDED',
              subjectType: 'QUOTATION',
              subjectId: '52000000-0000-4000-8000-000000000001',
              subjectNumber: 'QUO-202607-000022',
              actorType: 'USER',
              actorId: '11200000-0000-4000-8000-000000000004',
              actorDisplay: null,
              previousState: 'PENDING_APPROVAL',
              newState: 'APPROVED',
              safeReason: null,
              correlationId: '53000000-0000-4000-8000-000000000001',
              causationId: '54000000-0000-4000-8000-000000000001',
            },
          ],
          pageInfo: { nextCursor: null, hasNext: false, pageSize: 50 },
        }),
      );
    });
    await router.navigate('/app/audit');
    renderApplication();

    expect(await screen.findByRole('heading', { name: 'Audit search' })).toBeVisible();
    expect(screen.getByText('QUO-202607-000022')).toBeVisible();
    expect(screen.getByText('QUOTATION APPROVED')).toBeVisible();
  });
});
