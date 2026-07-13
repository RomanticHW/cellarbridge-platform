import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PublicQuotation, QuotationDetail } from '../../api/quotations';
import { Application } from '../../app/App';
import { router } from '../../app/router';
import { AuthSessionProvider } from '../identity-access/AuthSessionProvider';
import type { AuthSession } from '../identity-access/authSession';

const quotationId = '50000000-0000-4000-8000-000000000001';
const currentUser = {
  userId: '11200000-0000-4000-8000-000000000003',
  displayName: 'North Manager',
  tenant: { id: '10000000-0000-4000-8000-000000000001', name: 'North Cellars', status: 'ACTIVE' },
  roles: ['Sales Manager'],
  permissions: [
    'partner:read',
    'catalog:read',
    'inventory:read',
    'quotation:read',
    'quotation:create',
    'quotation:submit',
    'quotation:approve',
    'quotation:issue',
    'quotation:read-commercial-sensitive',
  ],
};
const session: AuthSession = {
  isLoading: false,
  isAuthenticated: true,
  accessToken: 'test-access-token',
  sessionIdentity: '11000000-0000-4000-8000-000000000003',
  signIn: vi.fn(),
  signOut: vi.fn(),
  clearSession: vi.fn(),
};
const detail: QuotationDetail = {
  id: quotationId,
  number: 'QUO-202607-000001',
  partnerId: '30000000-0000-4000-8000-000000000001',
  partnerName: 'Aurora Market Services',
  revision: 1,
  status: 'APPROVED',
  total: { amount: '8420.0000', currency: 'CNY' },
  selectedRouteCode: 'SH_GENERAL_TRADE',
  expiresAt: '2026-07-28T00:00:00Z',
  ownerId: currentUser.userId,
  version: 4,
  updatedAt: '2026-07-14T00:00:00Z',
  partnerSnapshot: {
    partnerId: '30000000-0000-4000-8000-000000000001',
    number: 'PAR-202607-000001',
    displayName: 'Aurora Market Services',
    paymentTermDays: 30,
    sourceVersion: 2,
    capturedAt: '2026-07-14T00:00:00Z',
  },
  requestedDeliveryDate: '2026-08-01',
  paymentTermDays: 30,
  deliveryAddress: {
    countryCode: 'CN',
    province: 'Shanghai',
    city: 'Shanghai',
    line1: '18 Riverside Road',
  },
  lines: [
    {
      lineId: '51000000-0000-4000-8000-000000000001',
      sku: {
        skuId: '34000000-0000-4000-8000-000000000001',
        skuCode: 'CB-MTV-2019-750X6',
        displayName: 'Moonlit Terrace',
        vintage: '2019',
        volumeMl: 750,
        unitsPerCase: 6,
        sourceVersion: 3,
      },
      quantity: { value: '2', unit: 'CASE' },
      listUnitPrice: { amount: '4200.0000', currency: 'CNY' },
      discountRate: '0.0000',
      netUnitPrice: { amount: '4200.0000', currency: 'CNY' },
      allocatedCharges: { amount: '20.0000', currency: 'CNY' },
      lineTotal: { amount: '8420.0000', currency: 'CNY' },
    },
  ],
  subtotal: { amount: '8420.0000', currency: 'CNY' },
  estimatedMarginRate: '0.2210',
  routeEvaluation: {
    evaluationId: '52000000-0000-4000-8000-000000000001',
    policyVersion: 'ROUTE-2026-01',
    evaluatedAt: '2026-07-14T00:00:00Z',
    inputHash: 'internal-input-hash',
    recommendedRouteCode: 'SH_GENERAL_TRADE',
    selectedRouteCode: 'SH_GENERAL_TRADE',
    candidates: [
      {
        routeCode: 'SH_GENERAL_TRADE',
        eligibility: 'ELIGIBLE',
        score: {
          cost: '34.00',
          leadTime: '21.00',
          supplyConfidence: '18.00',
          operationalSimplicity: '9.00',
          total: '82.00',
        },
        estimatedDeliveryDate: '2026-08-01',
        estimatedCharges: { amount: '20.0000', currency: 'CNY' },
      },
      {
        routeCode: 'NB_BONDED_B2B',
        eligibility: 'REJECTED',
        rejections: [
          {
            ruleId: 'ROUTE-PARTNER-ELIGIBILITY',
            code: 'PARTNER_NOT_ELIGIBLE',
            message: 'Customer is not eligible for this route',
          },
        ],
      },
    ],
  },
  approvalRequirements: [
    {
      ruleId: 'APPROVAL-MARGIN',
      code: 'LOW_MARGIN',
      actualValue: '0.1400',
      threshold: '0.1500',
      message: 'Margin is below the approval threshold',
    },
  ],
  approvals: [
    {
      decision: 'APPROVE',
      reviewerId: currentUser.userId,
      reason: 'Commercial thresholds reviewed',
      occurredAt: '2026-07-14T00:05:00Z',
    },
  ],
  allowedActions: ['VIEW', 'ISSUE'],
  timeline: [
    {
      id: '53000000-0000-4000-8000-000000000001',
      occurredAt: '2026-07-14T00:00:00Z',
      action: 'TRADE_ROUTE_EVALUATED',
      previousState: 'DRAFT',
      newState: 'DRAFT',
      safeReason: null,
    },
  ],
};
const publicDetail: PublicQuotation = {
  number: detail.number,
  revision: 1,
  supplierDisplayName: 'North Cellars',
  customerDisplayName: 'Aurora Market Services',
  status: 'SENT',
  expiresAt: detail.expiresAt,
  lines: [
    {
      description: 'Moonlit Terrace',
      vintage: '2019',
      package: '750 ml × 6',
      quantity: { value: '2', unit: 'CASE' },
      unitPrice: { amount: '4200.0000', currency: 'CNY' },
      lineTotal: { amount: '8400.0000', currency: 'CNY' },
    },
  ],
  total: { amount: '8420.0000', currency: 'CNY' },
  deliveryOption: { label: 'Shanghai general trade', estimatedWindow: '2026-08-01' },
  paymentTermDays: 30,
  termsVersion: 'PRICE-2026-01',
  allowedActions: [],
};
const draftDetail: QuotationDetail = {
  ...detail,
  status: 'DRAFT',
  version: 0,
  selectedRouteCode: null,
  routeEvaluation: null,
  approvalRequirements: [],
  approvals: [],
  allowedActions: ['VIEW', 'EDIT', 'EVALUATE_ROUTE', 'SUBMIT'],
};
const partnerPage = {
  items: [
    {
      id: detail.partnerId,
      number: detail.partnerSnapshot.number,
      legalName: 'Aurora Market Services Ltd.',
      displayName: detail.partnerName,
      status: 'ACTIVE',
      defaultCurrency: 'CNY',
      routeEligibility: ['SH_GENERAL_TRADE', 'NB_BONDED_B2B'],
      version: 2,
      updatedAt: '2026-07-14T00:00:00Z',
    },
  ],
  pageInfo: { nextCursor: null, hasNext: false, pageSize: 100 },
};
const catalogPage = {
  items: [
    {
      sku: detail.lines[0].sku,
      supplies: [],
    },
  ],
  pageInfo: { nextCursor: null, hasNext: false, pageSize: 100 },
  dataAsOf: '2026-07-14T00:00:00Z',
  availabilityDisclaimer: 'Availability is informational and is not an inventory commitment.',
};

function response(body: unknown, status = 200, etag?: string) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json',
      ...(etag ? { ETag: etag } : {}),
    },
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

describe('quotation workspace', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('explains route, approval, snapshot and issues a customer link', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = incoming(input);
      const path = new URL(request.url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      if (path.endsWith('/issue'))
        return Promise.resolve(
          response(
            {
              quotationId,
              status: 'SENT',
              version: 5,
              portalUrl: '/portal/quotations/customer-safe-token',
              expiresAt: detail.expiresAt,
            },
            200,
            '"5"',
          ),
        );
      return Promise.resolve(response(detail, 200, '"4"'));
    });
    await router.navigate(`/app/quotations/${quotationId}`);
    const user = userEvent.setup();
    renderApplication();

    expect(await screen.findByRole('heading', { name: detail.number })).toBeVisible();
    expect(screen.getByText(/snapshot v3/)).toBeVisible();
    expect(screen.getByText('ROUTE-2026-01')).toBeVisible();
    expect(screen.getByText('Customer is not eligible for this route')).toBeVisible();
    expect(screen.getByText('Margin is below the approval threshold')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Issue quotation' }));
    expect(
      await screen.findByRole('link', { name: 'Open the customer-safe quotation preview' }),
    ).toHaveAttribute('href', '/portal/quotations/customer-safe-token');
  });

  it('lists tenant quotations and their commercial workflow state', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(incoming(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return Promise.resolve(
        response({ items: [detail], pageInfo: { nextCursor: null, hasNext: false, pageSize: 50 } }),
      );
    });
    await router.navigate('/app/quotations');
    renderApplication();

    expect(await screen.findByRole('heading', { name: 'Quotations' })).toBeVisible();
    expect(screen.getByRole('link', { name: detail.number })).toBeVisible();
    expect(screen.getByText(detail.partnerName)).toBeVisible();
    expect(screen.getByText('CNY 8420.0000')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Create quotation' })).toBeVisible();
  });

  it('creates a validated quotation from the catalog selection', async () => {
    let submittedBody: Record<string, unknown> | undefined;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const request = incoming(input);
      const path = new URL(request.url).pathname;
      if (path.endsWith('/me')) return response(currentUser);
      if (path.endsWith('/partners')) return response(partnerPage);
      if (path.endsWith('/catalog/skus')) return response(catalogPage);
      if (request.method === 'POST' && path.endsWith('/quotations')) {
        submittedBody = (await request.json()) as Record<string, unknown>;
        return response(draftDetail, 201, '"0"');
      }
      return response(draftDetail, 200, '"0"');
    });
    await router.navigate(
      `/app/quotations/new?skuIds=${encodeURIComponent(detail.lines[0].sku.skuId)}`,
    );
    const user = userEvent.setup();
    renderApplication();

    expect(await screen.findByRole('heading', { name: 'Create quotation draft' })).toBeVisible();
    await user.click(screen.getByLabelText('Active customer'));
    await user.click(screen.getByText(`${detail.partnerName} · ${detail.partnerSnapshot.number}`));
    await user.type(screen.getByLabelText('Address line'), '88 Harbor Avenue');
    await user.clear(screen.getByLabelText('Line 1 discount rate'));
    await user.type(screen.getByLabelText('Line 1 discount rate'), '0.0900');
    await user.click(screen.getByRole('button', { name: 'Save quotation draft' }));

    await vi.waitFor(() => expect(submittedBody).toBeDefined());
    expect(submittedBody).toMatchObject({
      partnerId: detail.partnerId,
      currency: 'CNY',
      deliveryAddress: { line1: '88 Harbor Avenue' },
      lines: [
        {
          skuId: detail.lines[0].sku.skuId,
          quantity: { value: '1', unit: 'CASE' },
          discountRate: '0.0900',
        },
      ],
    });
    expect(await screen.findByRole('heading', { name: detail.number })).toBeVisible();
  });

  it('surfaces an optimistic concurrency conflict while editing a draft', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = incoming(input);
      const path = new URL(request.url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      if (path.endsWith('/partners')) return Promise.resolve(response(partnerPage));
      if (path.endsWith('/catalog/skus')) return Promise.resolve(response(catalogPage));
      if (request.method === 'PUT') {
        return Promise.resolve(
          response(
            {
              status: 412,
              code: 'RESOURCE_VERSION_CONFLICT',
              detail: 'The quotation changed after it was loaded',
              traceId: 'trace-quotation-conflict',
              retryable: false,
              currentVersion: 1,
              currentState: 'DRAFT',
            },
            412,
          ),
        );
      }
      return Promise.resolve(response(draftDetail, 200, '"0"'));
    });
    await router.navigate(`/app/quotations/${quotationId}/edit`);
    const user = userEvent.setup();
    renderApplication();

    expect(await screen.findByRole('heading', { name: 'Edit quotation draft' })).toBeVisible();
    await user.clear(screen.getByLabelText('Address line'));
    await user.type(screen.getByLabelText('Address line'), '99 Updated Avenue');
    await user.click(screen.getByRole('button', { name: 'Save quotation draft' }));
    expect(await screen.findByText('This quotation changed after you opened it')).toBeVisible();
  });

  it('renders a public allow-listed preview without resolving an authenticated session', async () => {
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = incoming(input);
      requests.push(request);
      return Promise.resolve(
        response({
          ...publicDetail,
          estimatedMarginRate: '0.2210',
          inputHash: 'must-not-render',
          totalCost: '6550.0000',
        }),
      );
    });
    await router.navigate('/portal/quotations/customer-safe-token');
    renderApplication();

    expect(
      await screen.findByRole('heading', { name: `Quotation ${detail.number}` }),
    ).toBeVisible();
    expect(screen.getByText('Moonlit Terrace')).toBeVisible();
    expect(screen.queryByText('0.2210')).not.toBeInTheDocument();
    expect(screen.queryByText('must-not-render')).not.toBeInTheDocument();
    expect(screen.queryByText('6550.0000')).not.toBeInTheDocument();
    expect(requests.some((request) => new URL(request.url).pathname.endsWith('/me'))).toBe(false);
    expect(requests[0]?.headers.has('authorization')).toBe(false);
  });
});
