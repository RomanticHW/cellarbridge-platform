import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getReceivable, listReceivables, recordPayment, reversePayment } from './settlement';

const receivableId = '81000000-0000-4000-8000-000000000001';
const paymentId = '82000000-0000-4000-8000-000000000001';

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function incoming(input: string | URL | Request) {
  return input instanceof Request ? input : new Request(input);
}

describe('settlement API client', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('sends tenant-scoped filters and detail identity', async () => {
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = incoming(input);
      requests.push(request);
      return Promise.resolve(
        response(
          new URL(request.url).pathname.endsWith(receivableId)
            ? { id: receivableId }
            : { items: [], pageInfo: { nextCursor: null, hasNext: false, pageSize: 25 } },
        ),
      );
    });

    await listReceivables('access-token', {
      pageSize: 25,
      cursor: 'next-page',
      status: ['OPEN', 'OVERDUE'],
    });
    await getReceivable('access-token', receivableId);

    expect(requests.map((request) => new URL(request.url).pathname)).toEqual([
      '/api/v1/receivables',
      `/api/v1/receivables/${receivableId}`,
    ]);
    expect(requests[0].headers.get('authorization')).toBe('Bearer access-token');
    expect(new URL(requests[0].url).searchParams.get('cursor')).toBe('next-page');
    expect(new URL(requests[0].url).searchParams.getAll('status')).toEqual(['OPEN', 'OVERDUE']);
  });

  it('binds optimistic and idempotency headers for payment and reversal commands', async () => {
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = incoming(input);
      requests.push(request);
      return Promise.resolve(response({ id: receivableId, version: requests.length }));
    });

    await recordPayment(
      'access-token',
      receivableId,
      4,
      {
        amount: { amount: '25.0000', currency: 'USD' },
        occurredOn: '2026-07-22',
        method: 'BANK_TRANSFER',
        externalReference: 'BANK-2026-001',
      },
      'payment-ui-stable-request-key',
    );
    await reversePayment(
      'access-token',
      receivableId,
      paymentId,
      5,
      {
        amount: { amount: '5.0000', currency: 'USD' },
        reason: 'Duplicate remittance correction',
      },
      'reversal-ui-stable-request-key',
    );

    expect(requests[0].method).toBe('POST');
    expect(requests[0].headers.get('if-match')).toBe('"4"');
    expect(requests[0].headers.get('idempotency-key')).toBe('payment-ui-stable-request-key');
    expect(await requests[0].clone().json()).toEqual(
      expect.objectContaining({ externalReference: 'BANK-2026-001' }),
    );
    expect(new URL(requests[1].url).pathname).toBe(
      `/api/v1/receivables/${receivableId}/payments/${paymentId}/reversal`,
    );
    expect(requests[1].headers.get('if-match')).toBe('"5"');
    expect(requests[1].headers.get('idempotency-key')).toBe('reversal-ui-stable-request-key');
  });

  it('preserves stable conflict recovery evidence', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      response(
        {
          status: 409,
          code: 'PAYMENT_REFERENCE_REUSED',
          detail: 'The reference belongs to a different payload',
          retryable: false,
        },
        409,
      ),
    );

    await expect(
      recordPayment('access-token', receivableId, 4, {
        amount: { amount: '25.0000', currency: 'USD' },
        occurredOn: '2026-07-22',
        method: 'BANK_TRANSFER',
        externalReference: 'BANK-2026-001',
      }),
    ).rejects.toEqual(expect.objectContaining({ status: 409, code: 'PAYMENT_REFERENCE_REUSED' }));
  });

  it('preserves the current version when a reversal must be retried', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      response(
        {
          status: 412,
          code: 'RESOURCE_VERSION_CONFLICT',
          detail: 'Reload the latest receivable before retrying',
          currentVersion: 9,
          retryable: false,
        },
        412,
      ),
    );

    await expect(
      reversePayment('access-token', receivableId, paymentId, 8, {
        amount: { amount: '5.0000', currency: 'USD' },
        reason: 'Duplicate remittance correction',
      }),
    ).rejects.toEqual(
      expect.objectContaining({
        status: 412,
        code: 'RESOURCE_VERSION_CONFLICT',
        currentVersion: 9,
      }),
    );
  });

  it('uses safe fallback evidence for an empty detail error', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 404 }));

    await expect(getReceivable('access-token', receivableId)).rejects.toEqual(
      expect.objectContaining({
        status: 404,
        code: 'INTERNAL_ERROR',
        currentVersion: undefined,
        message: 'The settlement request could not be completed.',
      }),
    );
  });
});
