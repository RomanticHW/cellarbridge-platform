import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getOrder, listOrders, type BuyerOrderDetail, type OrderDetail } from './orders';

const orderId = '51000000-0000-4000-8000-000000000001';
const partnerId = '53000000-0000-4000-8000-000000000001';
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
const pageInfo = { nextCursor: null, hasNext: false, pageSize: 50 };

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function incoming(input: string | URL | Request) {
  return input instanceof Request ? input : new Request(input);
}

describe('order API client', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('selects the internal or Buyer endpoint from the identity-mapped partner scope', async () => {
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = incoming(input);
      requests.push(request);
      const buyer = new URL(request.url).pathname.endsWith('/buyer/orders');
      return Promise.resolve(
        response({
          items: [buyer ? common : { ...common, partnerId, version: 0 }],
          pageInfo,
        }),
      );
    });

    await listOrders('internal-access-token', null, {
      pageSize: 50,
      status: ['PENDING_RESERVATION'],
    });
    await listOrders('buyer-access-token', partnerId, { pageSize: 50 });

    expect(requests.map((request) => new URL(request.url).pathname)).toEqual([
      '/api/v1/orders',
      '/api/v1/buyer/orders',
    ]);
    expect(requests[0].headers.get('authorization')).toBe('Bearer internal-access-token');
    expect(requests[1].headers.get('authorization')).toBe('Bearer buyer-access-token');
    expect(new URL(requests[1].url).searchParams.has('partnerId')).toBe(false);
  });

  it('uses the scoped detail path and preserves safe API errors', async () => {
    const buyerDetail = { ...common } as BuyerOrderDetail;
    const internalDetail = { ...common, partnerId, version: 0 } as OrderDetail;
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce((input) => {
        requests.push(incoming(input));
        return Promise.resolve(response(buyerDetail));
      })
      .mockImplementationOnce((input) => {
        requests.push(incoming(input));
        return Promise.resolve(response(internalDetail));
      })
      .mockResolvedValueOnce(
        response(
          {
            status: 404,
            code: 'RESOURCE_NOT_FOUND',
            detail: 'Order is unavailable',
            retryable: false,
          },
          404,
        ),
      );

    await getOrder('buyer-access-token', partnerId, orderId);
    await getOrder('internal-access-token', null, orderId);

    expect(requests.map((request) => new URL(request.url).pathname)).toEqual([
      `/api/v1/buyer/orders/${orderId}`,
      `/api/v1/orders/${orderId}`,
    ]);
    await expect(getOrder('buyer-access-token', partnerId, orderId)).rejects.toEqual(
      expect.objectContaining({ status: 404, code: 'RESOURCE_NOT_FOUND' }),
    );
  });
});
