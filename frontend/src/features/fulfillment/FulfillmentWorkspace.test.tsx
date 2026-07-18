import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { FulfillmentPlanDetail, FulfillmentPlanSummary } from '../../api/fulfillment';
import { Application } from '../../app/App';
import { router } from '../../app/router';
import { AuthSessionProvider } from '../identity-access/AuthSessionProvider';
import type { AuthSession } from '../identity-access/authSession';

const planId = '71000000-0000-4000-8000-000000000001';
const summary: FulfillmentPlanSummary = {
  id: planId,
  number: 'FUL-202607-000001',
  orderId: '51000000-0000-4000-8000-000000000001',
  orderNumber: 'ORD-202607-000001',
  routeCode: 'NB_BONDED_B2B',
  status: 'IN_PROGRESS',
  currentStep: 'Dispatch',
  dueAt: '2026-07-22T10:00:00Z',
  overdue: false,
  version: 4,
};
const detail: FulfillmentPlanDetail = {
  ...summary,
  templateCode: 'NB_BONDED_B2B',
  templateVersion: '2026.1',
  steps: [
    {
      id: '72000000-0000-4000-8000-000000000001',
      code: 'ORDER_CONFIRMATION',
      name: 'Order confirmation',
      status: 'COMPLETED',
      dependencies: [],
      assigneeRole: 'TRADE_OPERATOR',
      plannedStartAt: '2026-07-20T10:00:00Z',
      dueAt: '2026-07-20T11:00:00Z',
      startedAt: '2026-07-20T10:01:00Z',
      completedAt: '2026-07-20T10:02:00Z',
      customerVisible: true,
      optional: false,
      skippable: false,
      failureCode: null,
      safeMessage: null,
      allowedActions: [],
      latestAdapterAttempt: {
        scenario: 'SUCCESS',
        outcome: 'CONFIRMED',
        reference: 'SIM-1',
        occurredAt: '2026-07-20T10:02:00Z',
      },
      version: 2,
    },
    {
      id: '72000000-0000-4000-8000-000000000002',
      code: 'DISPATCH',
      name: 'Dispatch',
      status: 'READY',
      dependencies: ['ORDER_CONFIRMATION'],
      assigneeRole: 'WAREHOUSE_OPERATOR',
      plannedStartAt: '2026-07-20T11:00:00Z',
      dueAt: '2026-07-20T15:00:00Z',
      startedAt: null,
      completedAt: null,
      customerVisible: true,
      optional: false,
      skippable: false,
      failureCode: null,
      safeMessage: null,
      allowedActions: ['START'],
      latestAdapterAttempt: null,
      version: 0,
    },
  ],
  milestones: [
    {
      code: 'ORDER_CONFIRMATION',
      label: 'Order confirmation',
      occurredAt: '2026-07-20T10:02:00Z',
      customerVisible: true,
    },
  ],
  allowedActions: ['START'],
};
const currentUser = {
  userId: '11200000-0000-4000-8000-000000000005',
  displayName: 'North Trade',
  partnerId: null,
  tenant: { id: '10000000-0000-4000-8000-000000000001', name: 'North Cellars', status: 'ACTIVE' },
  roles: ['Trade Operator', 'Warehouse Operator'],
  permissions: ['order:read', 'fulfillment:read', 'fulfillment:operate'],
} as const;
const session: AuthSession = {
  isLoading: false,
  isAuthenticated: true,
  accessToken: 'test-access-token',
  sessionIdentity: '11000000-0000-4000-8000-000000000005',
  signIn: vi.fn(),
  signOut: vi.fn(),
  clearSession: vi.fn(),
};

function response(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function incoming(input: string | URL | Request) {
  return input instanceof Request ? input : new Request(input);
}

function renderApplication() {
  return render(
    <AuthSessionProvider value={session}>
      <Application />
    </AuthSessionProvider>,
  );
}

describe('fulfillment workspace', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('exposes a live board entry and route-bound work summary', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(incoming(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return Promise.resolve(
        response({
          items: [summary],
          pageInfo: { nextCursor: null, hasNext: false, pageSize: 50 },
        }),
      );
    });
    await router.navigate('/app/fulfillment');
    renderApplication();

    expect(await screen.findByRole('heading', { name: 'Fulfillment control board' })).toBeVisible();
    expect(screen.getByRole('link', { name: summary.number })).toHaveAttribute(
      'href',
      `/app/fulfillment/${planId}`,
    );
    expect(screen.getByText('NB BONDED B2B')).toBeVisible();
    expect(screen.getByRole('combobox', { name: 'Filter by fulfillment owner' })).toBeVisible();
    const navigation = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(within(navigation).getByText('Fulfillment')).toBeVisible();
  });

  it('renders dependencies, simulator evidence and only customer-visible milestones', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(incoming(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return Promise.resolve(response(detail));
    });
    await router.navigate(`/app/fulfillment/${planId}`);
    renderApplication();

    expect(await screen.findByRole('heading', { name: summary.number })).toBeVisible();
    expect(screen.getByText('Depends on ORDER_CONFIRMATION')).toBeVisible();
    expect(screen.getByText(/Simulator: SUCCESS → CONFIRMED/)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Start' })).toBeVisible();
    expect(screen.getAllByText('Order confirmation').length).toBeGreaterThan(1);
    expect(screen.queryByText('SIMULATED_ADAPTER_FAILURE')).not.toBeInTheDocument();
  });

  it('renders overdue, retry and optional-step actions from the latest plan state', async () => {
    const alternatePlanId = '71000000-0000-4000-8000-000000000002';
    const baseStep = detail.steps[1];
    if (baseStep === undefined) throw new Error('Fulfillment fixture is incomplete');
    const alternate: FulfillmentPlanDetail = {
      ...detail,
      id: alternatePlanId,
      number: 'FUL-202607-000002',
      status: 'ON_HOLD',
      currentStep: null,
      dueAt: null,
      overdue: true,
      allowedActions: ['START', 'COMPLETE', 'FAIL', 'RETRY', 'SKIP'],
      milestones: [],
      steps: [
        {
          ...baseStep,
          id: `${baseStep.id.slice(0, -1)}3`,
          status: 'OVERDUE',
          allowedActions: ['START'],
        },
        {
          ...baseStep,
          id: `${baseStep.id.slice(0, -1)}4`,
          status: 'OVERDUE',
          startedAt: '2026-07-20T11:01:00Z',
          allowedActions: ['COMPLETE', 'FAIL'],
        },
        {
          ...baseStep,
          id: `${baseStep.id.slice(0, -1)}5`,
          status: 'FAILED',
          allowedActions: ['RETRY'],
        },
        {
          ...baseStep,
          id: `${baseStep.id.slice(0, -1)}6`,
          status: 'BLOCKED',
          skippable: true,
          allowedActions: ['SKIP'],
        },
      ],
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(incoming(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return Promise.resolve(response(alternate));
    });
    await router.navigate(`/app/fulfillment/${alternatePlanId}`);
    renderApplication();

    expect(await screen.findByRole('heading', { name: alternate.number })).toBeVisible();
    expect(screen.getByText('All required work complete')).toBeVisible();
    expect(screen.getByText('No customer-visible milestones have been reached.')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Start' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Complete' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Fail' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Skip' })).toBeVisible();
  });
});
