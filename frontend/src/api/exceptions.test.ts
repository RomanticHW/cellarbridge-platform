import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  assignException,
  closeException,
  listExceptions,
  recoverException,
  transitionException,
} from './exceptions';

const exceptionId = '81000000-0000-4000-8000-000000000001';

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function incoming(input: string | URL | Request) {
  return input instanceof Request ? input : new Request(input);
}

describe('exception API client', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('serializes queue filters without exposing tenant input', async () => {
    let request: Request | undefined;
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      request = incoming(input);
      return Promise.resolve(
        response({ items: [], nextCursor: null, hasNext: false, pageSize: 25 }),
      );
    });

    await listExceptions('access-token', {
      pageSize: 25,
      status: ['OPEN', 'IN_PROGRESS'],
      severity: 'HIGH',
      assigneeId: '11200000-0000-4000-8000-000000000005',
      sourceType: 'FULFILLMENT_STEP',
      overdue: true,
    });

    expect(request).toBeDefined();
    const url = new URL(request?.url ?? 'http://invalid');
    expect(url.pathname).toBe('/api/v1/exceptions');
    expect(url.searchParams.getAll('status')).toEqual(['OPEN', 'IN_PROGRESS']);
    expect(url.searchParams.get('sourceType')).toBe('FULFILLMENT_STEP');
    expect(url.searchParams.get('overdue')).toBe('true');
    expect(url.searchParams.has('tenantId')).toBe(false);
    expect(request?.headers.get('authorization')).toBe('Bearer access-token');
  });

  it('binds versions, idempotency and reviewed closure evidence to commands', async () => {
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = incoming(input);
      requests.push(request);
      return Promise.resolve(response({ id: exceptionId }));
    });

    await assignException(
      'access-token',
      exceptionId,
      4,
      '11200000-0000-4000-8000-000000000005',
      'Accept ownership',
    );
    await transitionException('access-token', exceptionId, 5, 'ACKNOWLEDGE', 'Review evidence');
    await recoverException(
      'access-token',
      exceptionId,
      6,
      'RETRY_FULFILLMENT_STEP',
      'Retry verified source step',
    );
    await closeException(
      'access-token',
      exceptionId,
      8,
      'DUPLICATE',
      'Retain the linked primary case',
      '81000000-0000-4000-8000-000000000002',
    );

    expect(requests.map((request) => request.headers.get('if-match'))).toEqual([
      '"4"',
      '"5"',
      '"6"',
      '"8"',
    ]);
    expect(requests[2]?.headers.get('idempotency-key')).toMatch(/^exception-ui-/);
    await expect(requests[3]?.json()).resolves.toEqual(
      expect.objectContaining({
        reasonCode: 'DUPLICATE',
        primaryCaseId: '81000000-0000-4000-8000-000000000002',
      }),
    );
  });
});
