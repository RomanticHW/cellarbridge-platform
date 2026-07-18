import { beforeEach, describe, expect, it, vi } from 'vitest';
import { actOnFulfillmentStep, getFulfillmentPlan, listFulfillmentPlans } from './fulfillment';

const planId = '71000000-0000-4000-8000-000000000001';
const stepId = '72000000-0000-4000-8000-000000000001';

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function incoming(input: string | URL | Request) {
  return input instanceof Request ? input : new Request(input);
}

describe('fulfillment API client', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('sends filters and detail identity through tenant-scoped endpoints', async () => {
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = incoming(input);
      requests.push(request);
      return Promise.resolve(
        response(
          new URL(request.url).pathname.endsWith(planId)
            ? { id: planId }
            : { items: [], pageInfo: { nextCursor: null, hasNext: false, pageSize: 25 } },
        ),
      );
    });

    await listFulfillmentPlans('access-token', {
      pageSize: 25,
      status: ['READY', 'IN_PROGRESS'],
      overdue: true,
      ownerRole: 'WAREHOUSE_OPERATOR',
      orderId: '51000000-0000-4000-8000-000000000001',
    });
    await getFulfillmentPlan('access-token', planId);

    expect(requests.map((request) => new URL(request.url).pathname)).toEqual([
      '/api/v1/fulfillment/plans',
      `/api/v1/fulfillment/plans/${planId}`,
    ]);
    expect(requests[0].headers.get('authorization')).toBe('Bearer access-token');
    expect(new URL(requests[0].url).searchParams.get('overdue')).toBe('true');
    expect(new URL(requests[0].url).searchParams.get('ownerRole')).toBe('WAREHOUSE_OPERATOR');
    expect(new URL(requests[0].url).searchParams.get('orderId')).toBe(
      '51000000-0000-4000-8000-000000000001',
    );
  });

  it('binds optimistic and idempotency headers and preserves refresh evidence', async () => {
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce((input) => {
        const request = incoming(input);
        requests.push(request);
        return Promise.resolve(
          response({
            planId,
            stepId,
            stepStatus: 'COMPLETED',
            planStatus: 'IN_PROGRESS',
            version: 8,
            replayed: false,
          }),
        );
      })
      .mockResolvedValueOnce(
        response(
          {
            status: 412,
            code: 'OPTIMISTIC_VERSION_CONFLICT',
            detail: 'Refresh the plan',
            currentVersion: 9,
            retryable: false,
          },
          412,
        ),
      );

    await actOnFulfillmentStep('access-token', planId, stepId, 7, 'COMPLETE', undefined, 'SUCCESS');
    const request = requests[0];
    expect(request.method).toBe('POST');
    expect(request.headers.get('if-match')).toBe('"7"');
    expect(request.headers.get('idempotency-key')).toMatch(/^fulfillment-ui-/);
    await expect(actOnFulfillmentStep('access-token', planId, stepId, 8, 'START')).rejects.toEqual(
      expect.objectContaining({
        status: 412,
        code: 'OPTIMISTIC_VERSION_CONFLICT',
        currentVersion: 9,
      }),
    );
  });
});
