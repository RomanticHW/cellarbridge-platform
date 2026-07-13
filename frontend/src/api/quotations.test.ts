import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createQuotation,
  decideQuotationApproval,
  getPublicQuotation,
  getQuotation,
  issueQuotation,
  type QuotationApiError,
  type QuotationDetail,
  type QuotationDraftRequest,
} from './quotations';

const quotation: QuotationDetail = {
  id: '50000000-0000-4000-8000-000000000001',
  number: 'QUO-202607-000001',
  partnerId: '30000000-0000-4000-8000-000000000001',
  partnerName: 'Aurora Market Services',
  revision: 1,
  status: 'DRAFT',
  total: { amount: '8600.0000', currency: 'CNY' },
  selectedRouteCode: null,
  expiresAt: '2026-07-28T00:00:00Z',
  ownerId: '11200000-0000-4000-8000-000000000001',
  version: 0,
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
  lines: [],
  subtotal: { amount: '8600.0000', currency: 'CNY' },
  approvalRequirements: [],
  allowedActions: ['EDIT', 'EVALUATE_ROUTE', 'SUBMIT'],
  timeline: [],
};

const requestBody: QuotationDraftRequest = {
  partnerId: quotation.partnerId,
  currency: 'CNY',
  requestedDeliveryDate: '2026-08-01',
  expiresAt: '2026-07-28T00:00:00Z',
  paymentTermDays: 30,
  deliveryAddress: quotation.deliveryAddress,
  lines: [
    {
      skuId: '34000000-0000-4000-8000-000000000001',
      quantity: { value: '2', unit: 'CASE' },
      discountRate: '0.0500',
    },
  ],
};

function json(body: unknown, status = 200, etag?: string) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json',
      ...(etag ? { ETag: etag } : {}),
    },
  });
}

describe('quotation API client', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('maps versioned quotation commands and concurrency headers', async () => {
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const incoming = input as Request;
      requests.push(incoming);
      const path = new URL(incoming.url).pathname;
      if (incoming.method === 'POST' && path.endsWith('/quotations'))
        return json(quotation, 201, '"0"');
      if (path.endsWith('/approval'))
        return json(
          {
            quotationId: quotation.id,
            number: quotation.number,
            revision: 1,
            status: 'APPROVED',
            version: 4,
            allowedActions: ['ISSUE'],
          },
          200,
          '"4"',
        );
      if (path.endsWith('/issue'))
        return json(
          {
            quotationId: quotation.id,
            status: 'SENT',
            version: 5,
            portalUrl: '/portal/quotations/safe-token',
            expiresAt: quotation.expiresAt,
          },
          200,
          '"5"',
        );
      return json(quotation, 200, '"0"');
    });

    await expect(createQuotation('access-token', requestBody)).resolves.toEqual({
      data: quotation,
      etag: '"0"',
    });
    await expect(getQuotation('access-token', quotation.id)).resolves.toEqual({
      data: quotation,
      etag: '"0"',
    });
    await decideQuotationApproval('access-token', quotation.id, '"3"', {
      decision: 'APPROVE',
      reason: 'Commercial thresholds reviewed',
    });
    await issueQuotation('access-token', quotation.id, '"4"');

    expect(
      requests.every((item) => item.headers.get('authorization') === 'Bearer access-token'),
    ).toBe(true);
    expect(requests[2].headers.get('if-match')).toBe('"3"');
    expect(requests[3].headers.get('if-match')).toBe('"4"');
  });

  it('keeps the public preview unauthenticated and preserves safe errors', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json({ number: quotation.number }, 200))
      .mockResolvedValueOnce(
        json(
          {
            status: 404,
            code: 'RESOURCE_NOT_FOUND',
            detail: 'Quotation link is unavailable',
            traceId: 'trace-quotation',
            retryable: false,
          },
          404,
        ),
      );

    await getPublicQuotation('safe-token');
    const publicRequest = fetchMock.mock.calls[0]?.[0] as Request;
    expect(publicRequest.headers.has('authorization')).toBe(false);
    await expect(getPublicQuotation('missing-token')).rejects.toEqual(
      expect.objectContaining<Partial<QuotationApiError>>({
        status: 404,
        code: 'RESOURCE_NOT_FOUND',
        message: 'Quotation link is unavailable',
      }),
    );
  });
});
