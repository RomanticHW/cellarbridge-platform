import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { BuyerOrderDetail, OrderDetail } from '../../api/orders';
import { Application } from '../../app/App';
import { router } from '../../app/router';
import { AuthSessionProvider } from '../identity-access/AuthSessionProvider';
import type { AuthSession } from '../identity-access/authSession';

const orderId = '51000000-0000-4000-8000-000000000001';
const partnerId = '53000000-0000-4000-8000-000000000001';
const currentUser = {
  userId: '11200000-0000-4000-8000-000000000007',
  displayName: 'North Order Manager',
  partnerId: null,
  tenant: {
    id: '10000000-0000-4000-8000-000000000001',
    name: 'North Cellars',
    status: 'ACTIVE',
  },
  roles: ['Order Manager'],
  permissions: ['order:read'],
} as const;
const buyerUser = {
  ...currentUser,
  userId: '11200000-0000-4000-8000-000000000008',
  displayName: 'Harbor Buyer',
  partnerId,
  roles: ['Customer Buyer'],
};
const session: AuthSession = {
  isLoading: false,
  isAuthenticated: true,
  accessToken: 'test-access-token',
  sessionIdentity: '11000000-0000-4000-8000-000000000007',
  signIn: vi.fn(),
  signOut: vi.fn(),
  clearSession: vi.fn(),
};
const common = {
  id: orderId,
  number: 'ORD-202607-000001',
  sourceQuotationNumber: 'QUO-202607-000001',
  partnerName: 'Harbor Market Services',
  status: 'PENDING_RESERVATION' as const,
  total: { amount: '128400.00', currency: 'CNY' },
  routeCode: 'NB_BONDED_B2B' as const,
  createdAt: '2026-07-20T10:16:00Z',
};
const address = {
  countryCode: 'CN',
  province: 'Shanghai',
  city: 'Shanghai',
  district: 'Pudong',
  line1: '88 Harbor Avenue',
  postalCode: '200120',
};
const internalDetail: OrderDetail = {
  ...common,
  partnerId,
  version: 0,
  supplyDecisionStatus: 'FROZEN',
  supplyDecision: {
    schemaVersion: 1,
    policyVersion: 'SUPPLY-DECISION-2026-01',
    decidedAt: '2026-07-20T10:14:00Z',
    sourceRouteEvaluationId: '28000000-0000-4000-8000-000000000001',
    sourceRouteInputHash: 'a'.repeat(64),
    selectedRouteCode: 'NB_BONDED_B2B',
    inventoryDataAsOf: '2026-07-20T10:13:00Z',
    decisionHash: 'b'.repeat(64),
    lineDecisions: [
      {
        quotationLineId: '25000000-0000-4000-8000-000000000001',
        skuId: '26000000-0000-4000-8000-000000000001',
        requestedQuantity: '20',
        quantityUnit: 'CASE',
        allocationMode: 'FIXED_POOL',
        supplyPoolId: '27000000-0000-4000-8000-000000000001',
        supplyType: 'BONDED_ON_HAND',
      },
    ],
  },
  sourceQuotation: {
    id: '21000000-0000-4000-8000-000000000001',
    number: common.sourceQuotationNumber,
    revisionId: '22000000-0000-4000-8000-000000000001',
    revision: 1,
  },
  commercialSnapshot: {
    customer: {
      partnerId,
      partnerNumber: 'PAR-202607-000001',
      displayName: common.partnerName,
      sourceVersion: 3,
    },
    lines: [
      {
        orderLineId: '52000000-0000-4000-8000-000000000001',
        sourceQuotationLineId: '25000000-0000-4000-8000-000000000001',
        skuId: '26000000-0000-4000-8000-000000000001',
        skuCode: 'CB-BDX-2019-750X6',
        description: 'Bordeaux Blend 2019 750ml x 6',
        quantity: { value: '20', unit: 'CASE' },
        netUnitPrice: { amount: '6420.00', currency: 'CNY' },
        lineTotal: { amount: '128400.00', currency: 'CNY' },
        supplyPoolId: '27000000-0000-4000-8000-000000000001',
        allocationMode: 'FIXED_POOL',
        supplyType: 'BONDED_ON_HAND',
      },
    ],
    paymentTermDays: 30,
    acceptedTermsVersion: 'TERMS-2026-01',
    requestedDeliveryDate: '2026-08-15',
    deliveryAddress: address,
    route: {
      code: 'NB_BONDED_B2B',
      policyVersion: 'TRP-2026-01',
      estimatedDeliveryDate: '2026-08-15',
    },
    capturedAt: '2026-07-20T10:15:30Z',
    snapshotHash: 'sha256:order-snapshot-example',
  },
  reservation: { status: 'PENDING', message: 'Inventory reservation is pending' },
  fulfillment: { status: 'NOT_STARTED', message: 'Starts after inventory reservation.' },
  settlement: { status: 'NOT_STARTED', message: 'Starts at the configured business trigger.' },
  timeline: [
    {
      id: '54000000-0000-4000-8000-000000000001',
      occurredAt: common.createdAt,
      action: 'ORDER_CREATED',
      previousState: null,
      newState: 'PENDING_RESERVATION',
      safeReason: null,
      visibility: 'CUSTOMER',
    },
  ],
  allowedActions: ['VIEW'],
};
const buyerDetail: BuyerOrderDetail = {
  ...common,
  sourceQuotation: { number: common.sourceQuotationNumber, revision: 1 },
  commercialSnapshot: {
    customer: {
      partnerNumber: 'PAR-202607-000001',
      displayName: common.partnerName,
    },
    lines: [
      {
        skuCode: internalDetail.commercialSnapshot.lines[0].skuCode,
        description: internalDetail.commercialSnapshot.lines[0].description,
        quantity: internalDetail.commercialSnapshot.lines[0].quantity,
        netUnitPrice: internalDetail.commercialSnapshot.lines[0].netUnitPrice,
        lineTotal: internalDetail.commercialSnapshot.lines[0].lineTotal,
      },
    ],
    paymentTermDays: 30,
    acceptedTermsVersion: 'TERMS-2026-01',
    requestedDeliveryDate: '2026-08-15',
    deliveryAddress: address,
    route: { code: 'NB_BONDED_B2B', estimatedDeliveryDate: '2026-08-15' },
    capturedAt: '2026-07-20T10:15:30Z',
  },
  reservation: { status: 'PENDING', message: 'Inventory reservation is pending' },
  fulfillment: { status: 'NOT_STARTED', message: 'Starts after inventory reservation.' },
  settlement: { status: 'NOT_STARTED', message: 'Starts at the configured business trigger.' },
  timeline: [
    {
      occurredAt: common.createdAt,
      action: 'ORDER_CREATED',
      newState: 'PENDING_RESERVATION',
    },
  ],
  allowedActions: ['VIEW'],
};

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
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

describe('order workspace', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('lists immutable orders and exposes the implemented navigation entry', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(incoming(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return Promise.resolve(
        response({
          items: [
            {
              ...common,
              partnerId,
              version: 0,
            },
          ],
          pageInfo: { nextCursor: null, hasNext: false, pageSize: 50 },
        }),
      );
    });
    await router.navigate('/app/orders');
    renderApplication();

    expect(await screen.findByRole('heading', { name: 'Trade orders' })).toBeVisible();
    expect(screen.getByRole('link', { name: common.number })).toHaveAttribute(
      'href',
      `/app/orders/${orderId}`,
    );
    expect(screen.getByText(common.partnerName)).toBeVisible();
    const navigation = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(within(navigation).getByText('Trade orders')).toBeVisible();
    expect(within(navigation).queryByText('Planned')).not.toBeInTheDocument();
  });

  it('uses the Buyer-safe detail endpoint and never renders internal conversion evidence', async () => {
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = incoming(input);
      requests.push(request);
      const path = new URL(request.url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(buyerUser));
      return Promise.resolve(response(buyerDetail));
    });
    await router.navigate(`/app/orders/${orderId}`);
    renderApplication();

    expect(await screen.findByRole('heading', { name: common.number })).toBeVisible();
    expect(screen.getByText('Buyer-safe view')).toBeVisible();
    expect(screen.getByText('Bordeaux Blend 2019 750ml x 6')).toBeVisible();
    expect(screen.getByText('Inventory reservation is pending')).toBeVisible();
    expect(screen.queryByText('Conversion evidence')).not.toBeInTheDocument();
    expect(
      screen.queryByText(internalDetail.commercialSnapshot.snapshotHash),
    ).not.toBeInTheDocument();
    const orderRequest = requests.find((request) =>
      new URL(request.url).pathname.includes('/buyer/orders/'),
    );
    expect(orderRequest).toBeDefined();
    expect(new URL(orderRequest?.url ?? window.location.href).searchParams.has('partnerId')).toBe(
      false,
    );
  });

  it('shows verified frozen supply evidence and keeps reservation pending for internal users', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(incoming(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return Promise.resolve(response(internalDetail));
    });
    await router.navigate(`/app/orders/${orderId}`);
    renderApplication();

    expect(await screen.findByText('FROZEN')).toBeVisible();
    expect(screen.getByText('SUPPLY-DECISION-2026-01')).toBeVisible();
    expect(
      screen.getByText(
        'Frozen supply decision is an allocation constraint, not an inventory reservation.',
      ),
    ).toBeVisible();
    expect(screen.getByText('FIXED POOL')).toBeVisible();
    expect(screen.getByText('Inventory reservation is pending')).toBeVisible();
  });

  it('blocks legacy reservation without exposing unverified historical supply evidence', async () => {
    const legacy: OrderDetail = {
      ...internalDetail,
      supplyDecisionStatus: 'LEGACY_UNVERIFIED',
      supplyDecision: null,
      commercialSnapshot: {
        ...internalDetail.commercialSnapshot,
        lines: internalDetail.commercialSnapshot.lines.map((line) => ({
          ...line,
          allocationMode: null,
          supplyPoolId: null,
          supplyType: null,
        })),
      },
      reservation: {
        status: 'BLOCKED',
        message: 'Verified supply decision is missing; inventory reservation cannot start.',
      },
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(incoming(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return Promise.resolve(response(legacy));
    });
    await router.navigate(`/app/orders/${orderId}`);
    renderApplication();

    expect(await screen.findByText('LEGACY UNVERIFIED')).toBeVisible();
    expect(
      screen.getByText(
        'Legacy order requires controlled supply-decision remediation before inventory reservation.',
      ),
    ).toBeVisible();
    expect(screen.getByText('BLOCKED')).toBeVisible();
    expect(screen.queryByText('SUPPLY-DECISION-2026-01')).not.toBeInTheDocument();
  });
});
