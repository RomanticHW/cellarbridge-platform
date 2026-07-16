import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PublicQuotation, QuotationDetail } from '../../api/quotations';
import { Application } from '../../app/App';
import { router } from '../../app/router';
import { AuthSessionProvider } from '../identity-access/AuthSessionProvider';
import type { AuthSession } from '../identity-access/authSession';

const quotationId = '50000000-0000-4000-8000-000000000001';
const supplyPoolId = '54000000-0000-4000-8000-000000000001';
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
      allocationMode: 'ROUTE_ELIGIBLE_AUTO',
      supplyType: 'DOMESTIC_ON_HAND',
      supplyPoolId: null,
    },
  ],
  subtotal: { amount: '8420.0000', currency: 'CNY' },
  estimatedMarginRate: '0.2210',
  supplyDecisionStatus: 'FROZEN',
  supplyDecision: {
    schemaVersion: 1,
    policyVersion: 'SUPPLY-DECISION-2026-01',
    decidedAt: '2026-07-14T00:00:00Z',
    sourceRouteEvaluationId: '52000000-0000-4000-8000-000000000001',
    sourceRouteInputHash: 'a'.repeat(64),
    selectedRouteCode: 'SH_GENERAL_TRADE',
    inventoryDataAsOf: '2026-07-14T00:00:00Z',
    decisionHash: 'b'.repeat(64),
    lineDecisions: [
      {
        quotationLineId: '51000000-0000-4000-8000-000000000001',
        skuId: '34000000-0000-4000-8000-000000000001',
        requestedQuantity: '2.000000',
        quantityUnit: 'CASE',
        allocationMode: 'ROUTE_ELIGIBLE_AUTO',
        supplyPoolId: null,
        supplyType: 'DOMESTIC_ON_HAND',
      },
    ],
  },
  routeEvaluation: {
    evaluationId: '52000000-0000-4000-8000-000000000001',
    policyVersion: 'ROUTE-2026-01',
    evaluatedAt: '2026-07-14T00:00:00Z',
    inputHash: 'internal-input-hash',
    recommendedRouteCode: 'SH_GENERAL_TRADE',
    selectedRouteCode: 'SH_GENERAL_TRADE',
    supplyDecision: undefined,
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
      skuCode: 'CB-MTV-2019-750X6',
      description: 'Moonlit Terrace',
      vintage: '2019',
      package: '750 ml × 6',
      quantity: { value: '2', unit: 'CASE' },
      unitPrice: { amount: '4200.0000', currency: 'CNY' },
      lineTotal: { amount: '8400.0000', currency: 'CNY' },
    },
  ],
  subtotal: { amount: '8400.0000', currency: 'CNY' },
  fees: { amount: '20.0000', currency: 'CNY' },
  total: { amount: '8420.0000', currency: 'CNY' },
  deliveryOption: { label: 'Shanghai general trade', estimatedWindow: '2026-08-01' },
  paymentTermDays: 30,
  termsVersion: 'PRICE-2026-01',
  termsSummary: [
    'Prices and availability apply only to this quotation revision.',
    'Delivery dates remain subject to the selected delivery option.',
  ],
  allowedActions: ['ACCEPT', 'REJECT'],
  decisionReceipt: null,
};
const draftDetail: QuotationDetail = {
  ...detail,
  status: 'DRAFT',
  version: 0,
  selectedRouteCode: null,
  supplyDecisionStatus: 'UNDECIDED',
  supplyDecision: undefined,
  routeEvaluation: null,
  approvalRequirements: [],
  approvals: [],
  allowedActions: ['VIEW', 'EDIT', 'EVALUATE_ROUTE'],
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
      supplies: [
        {
          supplyPoolId,
          supplyType: 'DOMESTIC_ON_HAND',
          quantityUnit: 'CASE',
          locationLabel: 'Shanghai Warehouse',
          availabilityLevel: 'AVAILABLE',
          displayQuantityBand: 'HIGH',
          automaticallyReservable: true,
          exactLots: [],
          updatedAt: '2026-07-14T00:00:00Z',
        },
      ],
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
    expect(screen.getByText('Route-bound supply decision frozen')).toBeVisible();
    expect(screen.getByText('Automatic (route eligible)')).toBeVisible();
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
    await user.click(screen.getByLabelText('Line 1 supply strategy'));
    await user.click(screen.getByText('Specific supply pool'));
    await user.click(screen.getByLabelText('Line 1 specific supply pool'));
    await user.click(screen.getByText('Shanghai Warehouse · DOMESTIC ON HAND · CASE · HIGH'));
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
          preferredSupplyPoolId: supplyPoolId,
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
    expect(screen.getByText(publicDetail.termsSummary[0])).toBeVisible();
    expect(screen.getByRole('checkbox', { name: /reviewed and agree/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Accept quotation' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Reject quotation' })).toBeEnabled();
    expect(requests.some((request) => new URL(request.url).pathname.endsWith('/me'))).toBe(false);
    expect(requests[0]?.headers.has('authorization')).toBe(false);
  });

  it('accepts once with confirmed terms and reloads the terminal receipt without another POST', async () => {
    let accepted = false;
    const requests: Request[] = [];
    const receipt = {
      decisionId: '61000000-0000-4000-8000-000000000001',
      decision: 'ACCEPTED' as const,
      decidedAt: '2026-07-14T01:00:00Z',
      reference: 'ACC-202607-000001',
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const request = incoming(input);
      requests.push(request);
      const path = new URL(request.url).pathname;
      if (path.endsWith('/acceptance')) {
        accepted = true;
        return response(
          {
            acceptanceId: receipt.decisionId,
            quotationNumber: publicDetail.number,
            status: 'ACCEPTED',
            acceptedAt: receipt.decidedAt,
            orderCreationStatus: 'PENDING',
            orderId: null,
            orderNumber: null,
            replayed: false,
          },
          201,
        );
      }
      return response(
        accepted
          ? {
              ...publicDetail,
              status: 'ACCEPTED',
              allowedActions: [],
              decisionReceipt: receipt,
            }
          : publicDetail,
      );
    });
    await router.navigate('/portal/quotes/customer-safe-token');
    const user = userEvent.setup();
    const firstRender = renderApplication();

    const acceptButton = await screen.findByRole('button', { name: 'Accept quotation' });
    await user.click(screen.getByRole('checkbox', { name: /reviewed and agree/i }));
    expect(acceptButton).toBeEnabled();
    fireEvent.click(acceptButton);
    fireEvent.click(acceptButton);

    expect(await screen.findByText('Quotation accepted')).toBeVisible();
    expect(await screen.findByText(receipt.reference)).toBeVisible();
    await waitFor(() =>
      expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1),
    );

    firstRender.unmount();
    renderApplication();
    expect(await screen.findByText('Quotation accepted')).toBeVisible();
    expect(await screen.findByText(receipt.reference)).toBeVisible();
    expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
    expect(requests.filter((request) => request.method === 'GET').length).toBeGreaterThanOrEqual(2);
  });

  it('refreshes an accepted receipt until the secured order link is available', async () => {
    const orderId = '51000000-0000-4000-8000-000000000001';
    const orderNumber = 'ORD-202607-000001';
    let accepted = false;
    const requests: Request[] = [];
    const receipt = {
      decisionId: '61000000-0000-4000-8000-000000000003',
      decision: 'ACCEPTED' as const,
      decidedAt: '2026-07-14T01:08:00Z',
      reference: 'ACC-202607-000003',
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const request = incoming(input);
      requests.push(request);
      if (new URL(request.url).pathname.endsWith('/acceptance')) {
        accepted = true;
        return response(
          {
            acceptanceId: receipt.decisionId,
            quotationNumber: publicDetail.number,
            status: 'ACCEPTED',
            acceptedAt: receipt.decidedAt,
            orderCreationStatus: 'PENDING',
            orderId: null,
            orderNumber: null,
            replayed: false,
          },
          201,
        );
      }
      return response(
        accepted
          ? {
              ...publicDetail,
              status: 'CONVERTED',
              allowedActions: ['VIEW_ORDER'],
              decisionReceipt: receipt,
              orderCreationStatus: 'CREATED',
              orderId,
              orderNumber,
            }
          : publicDetail,
      );
    });
    await router.navigate('/portal/quotations/customer-safe-token');
    const user = userEvent.setup();
    renderApplication();

    await user.click(await screen.findByRole('checkbox', { name: /reviewed and agree/i }));
    await user.click(screen.getByRole('button', { name: 'Accept quotation' }));

    expect(
      await screen.findByRole('link', { name: `Sign in to view ${orderNumber}` }),
    ).toHaveAttribute('href', `/app/orders/${orderId}`);
    expect(screen.getByText('CREATED')).toBeVisible();
    expect(requests.some((request) => new URL(request.url).pathname.endsWith('/me'))).toBe(false);
  });

  it('reuses the same idempotency key when an unchanged acceptance is retried', async () => {
    let acceptanceAttempts = 0;
    let accepted = false;
    const idempotencyKeys: Array<string | null> = [];
    const receipt = {
      decisionId: '61000000-0000-4000-8000-000000000002',
      decision: 'ACCEPTED' as const,
      decidedAt: '2026-07-14T01:03:00Z',
      reference: 'ACC-202607-000002',
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const request = incoming(input);
      if (new URL(request.url).pathname.endsWith('/acceptance')) {
        acceptanceAttempts += 1;
        idempotencyKeys.push(request.headers.get('idempotency-key'));
        if (acceptanceAttempts === 1) {
          return response(
            {
              status: 503,
              code: 'DEPENDENCY_UNAVAILABLE',
              detail: 'The decision service is temporarily unavailable.',
              traceId: 'portal-retry-trace',
              retryable: true,
            },
            503,
          );
        }
        accepted = true;
        return response(
          {
            acceptanceId: receipt.decisionId,
            quotationNumber: publicDetail.number,
            status: 'ACCEPTED',
            acceptedAt: receipt.decidedAt,
            orderCreationStatus: 'PENDING',
            orderId: null,
            orderNumber: null,
            replayed: false,
          },
          201,
        );
      }
      return response(
        accepted
          ? {
              ...publicDetail,
              status: 'ACCEPTED',
              allowedActions: [],
              decisionReceipt: receipt,
            }
          : publicDetail,
      );
    });
    await router.navigate('/portal/quotations/customer-safe-token');
    const user = userEvent.setup();
    renderApplication();

    await user.click(await screen.findByRole('checkbox', { name: /reviewed and agree/i }));
    await user.click(screen.getByRole('button', { name: 'Accept quotation' }));
    expect(await screen.findByText(/DEPENDENCY_UNAVAILABLE/)).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Accept quotation' }));

    expect(await screen.findByText('Quotation accepted')).toBeVisible();
    expect(idempotencyKeys).toHaveLength(2);
    expect(idempotencyKeys[0]).toMatch(/^[A-Za-z0-9._~-]{20,200}$/);
    expect(idempotencyKeys[1]).toBe(idempotencyKeys[0]);
  });

  it('records an optional categorized rejection and shows the immutable receipt', async () => {
    const requests: Request[] = [];
    let rejected = false;
    const receipt = {
      decisionId: '62000000-0000-4000-8000-000000000001',
      decision: 'REJECTED_BY_CUSTOMER' as const,
      decidedAt: '2026-07-14T01:05:00Z',
      reference: 'REJ-202607-000001',
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const request = incoming(input);
      requests.push(request);
      if (new URL(request.url).pathname.endsWith('/rejection')) {
        rejected = true;
        return response(
          {
            rejectionId: receipt.decisionId,
            quotationNumber: publicDetail.number,
            status: 'REJECTED_BY_CUSTOMER',
            rejectedAt: receipt.decidedAt,
            reasonCategory: 'DELIVERY_TIMING',
            replayed: false,
          },
          201,
        );
      }
      return response(
        rejected
          ? {
              ...publicDetail,
              status: 'REJECTED_BY_CUSTOMER',
              allowedActions: [],
              decisionReceipt: receipt,
            }
          : publicDetail,
      );
    });
    await router.navigate('/portal/quotations/customer-safe-token');
    const user = userEvent.setup();
    renderApplication();

    await user.click(await screen.findByLabelText('Optional rejection reason'));
    await user.click(screen.getByText('Delivery timing'));
    await user.click(screen.getByRole('button', { name: 'Reject quotation' }));

    expect(await screen.findByText('Quotation rejected')).toBeVisible();
    expect(await screen.findByText(receipt.reference)).toBeVisible();
    const rejectionRequest = requests.find((request) => request.method === 'POST');
    if (!rejectionRequest) throw new Error('Expected a rejection request');
    await expect(rejectionRequest.json()).resolves.toEqual({ reasonCategory: 'DELIVERY_TIMING' });
    expect(rejectionRequest.headers.get('idempotency-key')).toMatch(/^[A-Za-z0-9._~-]{20,200}$/);
  });

  it.each([
    ['QUOTE_EXPIRED', 409, 'Quotation expired'],
    ['RESOURCE_NOT_FOUND', 404, 'Quotation unavailable or revoked'],
  ])('renders the safe terminal for %s without decision actions', async (code, status, title) => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      response(
        {
          status,
          code,
          detail: 'The secure quotation is unavailable.',
          traceId: 'safe-portal-trace',
          retryable: false,
        },
        status,
      ),
    );
    await router.navigate('/portal/quotations/customer-safe-token');
    renderApplication();

    expect(await screen.findByText(title)).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Accept quotation' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject quotation' })).not.toBeInTheDocument();
  });

  it.each([
    ['EXPIRED', 'Quotation expired'],
    ['WITHDRAWN', 'Quotation withdrawn'],
  ])('renders a readable %s quotation as a terminal view', async (status, title) => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      response({
        ...publicDetail,
        status,
        allowedActions: [],
        decisionReceipt: null,
      }),
    );
    await router.navigate('/portal/quotations/customer-safe-token');
    renderApplication();

    expect(await screen.findByText(title)).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Accept quotation' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject quotation' })).not.toBeInTheDocument();
  });
});
