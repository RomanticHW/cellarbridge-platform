import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createPartner,
  getPartner,
  listPartners,
  requestPartnerReactivation,
  reviewPartner,
  submitPartner,
  suspendPartner,
  updatePartner,
  type CreatePartnerRequest,
  type PartnerApiError,
  type PartnerDetail,
} from './partners';

const partner: PartnerDetail = {
  id: '30000000-0000-4000-8000-000000000001',
  number: 'PAR-202607-000001',
  legalName: 'Cedar Dining Group',
  displayName: 'Cedar Dining',
  status: 'DRAFT',
  defaultCurrency: 'CNY',
  routeEligibility: ['SH_GENERAL_TRADE'],
  salesOwnerId: '11200000-0000-4000-8000-000000000001',
  version: 0,
  updatedAt: '2026-07-13T08:00:00Z',
  type: 'RESTAURANT_GROUP',
  registrationIdentifierMasked: '****1001',
  contacts: [{ name: 'Lin Wen', email: 'lin.wen@example.test', primary: true }],
  billingAddress: {
    countryCode: 'CN',
    province: 'Shanghai',
    city: 'Shanghai',
    line1: '301 Huaihai Road',
  },
  paymentTermDays: 30,
  creditLimit: null,
  eligibility: null,
  requestedServiceRegions: ['CN-SH'],
  requestedCurrencies: ['CNY'],
  allowedActions: ['EDIT', 'SUBMIT'],
  duplicateWarnings: [],
  timeline: [],
};

const createRequest: CreatePartnerRequest = {
  legalName: 'Cedar Dining Group',
  displayName: 'Cedar Dining',
  type: 'RESTAURANT_GROUP',
  defaultCurrency: 'CNY',
  contact: { name: 'Lin Wen', email: 'lin.wen@example.test', primary: true },
  billingAddress: {
    countryCode: 'CN',
    province: 'Shanghai',
    city: 'Shanghai',
    line1: '301 Huaihai Road',
  },
};

function json(body: unknown, status = 200, etag?: string) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json',
      ...(etag === undefined ? {} : { ETag: etag }),
    },
  });
}

describe('partner API client', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('maps list and versioned detail responses', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = input as Request;
      if (new URL(request.url).pathname.endsWith('/partners')) {
        return Promise.resolve(
          json({ items: [partner], pageInfo: { nextCursor: null, hasNext: false, pageSize: 25 } }),
        );
      }
      return Promise.resolve(json(partner, 200, '"0"'));
    });

    const page = await listPartners('access-token', {
      keyword: 'Cedar',
      status: ['DRAFT', 'ACTIVE'],
      routeCode: 'SH_GENERAL_TRADE',
      ownerId: '11200000-0000-4000-8000-000000000001',
      updatedFrom: '2026-07-01T00:00:00Z',
      updatedTo: '2026-08-01T00:00:00Z',
      pageSize: 25,
    });
    const detail = await getPartner('access-token', partner.id);

    expect(page.items).toHaveLength(1);
    expect(detail).toEqual({ data: partner, etag: '"0"' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const listRequest = fetchMock.mock.calls[0]?.[0] as Request;
    const listUrl = new URL(listRequest.url);
    expect(listUrl.searchParams.get('keyword')).toBe('Cedar');
    expect(
      listUrl.searchParams
        .getAll('status')
        .flatMap((value) => value.split(','))
        .sort(),
    ).toEqual(['ACTIVE', 'DRAFT']);
    expect(listUrl.searchParams.get('routeCode')).toBe('SH_GENERAL_TRADE');
    expect(listUrl.searchParams.get('ownerId')).toBe('11200000-0000-4000-8000-000000000001');
    expect(listUrl.searchParams.get('updatedFrom')).toBe('2026-07-01T00:00:00Z');
    expect(listUrl.searchParams.get('updatedTo')).toBe('2026-08-01T00:00:00Z');
  });

  it('sends create, update, submit, review, suspension, and reactivation commands', async () => {
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = input as Request;
      requests.push(request);
      if (request.method === 'POST' && new URL(request.url).pathname.endsWith('/partners')) {
        return Promise.resolve(json(partner, 201, '"0"'));
      }
      if (request.method === 'PATCH') {
        return Promise.resolve(
          json({ ...partner, displayName: 'Cedar Group', version: 1 }, 200, '"1"'),
        );
      }
      return Promise.resolve(
        json(
          {
            partnerId: partner.id,
            number: partner.number,
            status: 'PENDING_REVIEW',
            version: 1,
            allowedActions: [],
          },
          200,
          '"1"',
        ),
      );
    });

    await createPartner('access-token', createRequest);
    await updatePartner('access-token', partner.id, '"0"', { displayName: 'Cedar Group' });
    await submitPartner('access-token', partner.id, '"0"');
    await reviewPartner('access-token', partner.id, '"1"', {
      decision: 'APPROVE',
      reason: 'Commercial profile verified',
    });
    await suspendPartner('access-token', partner.id, '"2"', { reason: 'Commercial hold' });
    await requestPartnerReactivation('access-token', partner.id, '"3"', {
      reason: 'Documents updated',
    });

    expect(requests).toHaveLength(6);
    expect(requests[1].headers.get('if-match')).toBe('"0"');
    expect(requests[1].headers.get('content-type')).toContain('application/merge-patch+json');
    expect(requests.slice(2).map((request) => request.headers.get('if-match'))).toEqual([
      '"0"',
      '"1"',
      '"2"',
      '"3"',
    ]);
  });

  it('preserves stable problem details for conflict and concurrency UI', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      json(
        {
          type: 'about:blank',
          title: 'Partner changed',
          status: 412,
          detail: 'The partner changed after it was loaded',
          code: 'RESOURCE_VERSION_CONFLICT',
          traceId: 'trace-301',
          retryable: false,
          currentVersion: 4,
          currentState: 'PENDING_REVIEW',
          errors: [{ field: 'registrationIdentifier', code: 'REQUIRED', message: 'Required' }],
        },
        412,
      ),
    );

    await expect(updatePartner('access-token', partner.id, '"3"', {})).rejects.toEqual(
      expect.objectContaining<Partial<PartnerApiError>>({
        status: 412,
        code: 'RESOURCE_VERSION_CONFLICT',
        currentVersion: 4,
        currentState: 'PENDING_REVIEW',
        errors: [{ field: 'registrationIdentifier', code: 'REQUIRED', message: 'Required' }],
      }),
    );
  });
});
