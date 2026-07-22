import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  getDashboard,
  getTimeline,
  listAuditEntries,
  listWorkItems,
  type ReportingApiError,
} from './reporting';

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function incoming(input: string | URL | Request) {
  return input instanceof Request ? input : new Request(input);
}

describe('reporting API client', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('serializes every tenant-derived reporting query without accepting tenant input', async () => {
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = incoming(input);
      requests.push(request);
      const path = new URL(request.url).pathname;
      if (path.endsWith('/dashboard'))
        return Promise.resolve(response({ projectionStatus: 'CURRENT' }));
      if (path.endsWith('/audit/entries')) return Promise.resolve(response({ items: [] }));
      if (path.endsWith('/timeline')) return Promise.resolve(response({ items: [] }));
      return Promise.resolve(response({ items: [] }));
    });

    await getDashboard('access-token', '2026-07-01', '2026-07-22');
    await listAuditEntries('access-token', {
      pageSize: 25,
      cursor: 'next-page',
      subjectType: 'QUOTATION',
      subjectId: '52000000-0000-4000-8000-000000000001',
      correlationId: '53000000-0000-4000-8000-000000000001',
      actorId: '11200000-0000-4000-8000-000000000004',
      action: 'QUOTATION_APPROVED',
      from: '2026-07-01T00:00:00Z',
      to: '2026-07-22T23:59:59Z',
    });
    await getTimeline('access-token', 'QUOTATION', '52000000-0000-4000-8000-000000000001');
    await listWorkItems('access-token', {
      status: ['OPEN', 'CLAIMED'],
      priority: ['HIGH', 'CRITICAL'],
      type: ['QUOTATION_APPROVAL'],
      dueFrom: '2026-07-01T00:00:00Z',
      dueTo: '2026-07-22T23:59:59Z',
      subjectNumber: 'QUO-202607-000021',
      scope: 'team',
      pageSize: 50,
    });

    expect(requests.map((request) => request.headers.get('authorization'))).toEqual([
      'Bearer access-token',
      'Bearer access-token',
      'Bearer access-token',
      'Bearer access-token',
    ]);
    expect(requests.map((request) => new URL(request.url).pathname)).toEqual([
      '/api/v1/dashboard',
      '/api/v1/audit/entries',
      '/api/v1/timeline',
      '/api/v1/work-items',
    ]);
    expect(new URL(requests[0].url).searchParams.get('from')).toBe('2026-07-01');
    expect(new URL(requests[1].url).searchParams.get('cursor')).toBe('next-page');
    expect(new URL(requests[2].url).searchParams.get('pageSize')).toBe('50');
    expect(new URL(requests[3].url).searchParams.getAll('status')).toEqual(['OPEN', 'CLAIMED']);
    expect(new URL(requests[3].url).searchParams.get('scope')).toBe('team');
    expect(requests.every((request) => !new URL(request.url).searchParams.has('tenantId'))).toBe(
      true,
    );
  });

  it('preserves stable problem details across reporting reads', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
      Promise.resolve(
        response(
          {
            status: 403,
            code: 'ACCESS_DENIED',
            detail: 'Reporting read access is required',
          },
          403,
        ),
      ),
    );

    const operations = [
      getDashboard('access-token', '2026-07-01', '2026-07-22'),
      listAuditEntries('access-token', {}),
      getTimeline('access-token', 'ORDER', '62000000-0000-4000-8000-000000000001'),
      listWorkItems('access-token', {}),
    ];

    for (const operation of operations) {
      await expect(operation).rejects.toEqual(
        expect.objectContaining<Partial<ReportingApiError>>({
          status: 403,
          code: 'ACCESS_DENIED',
          message: 'Reporting read access is required',
        }),
      );
    }
  });

  it('uses safe fallback evidence for an empty projection error', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 503 }));

    await expect(getDashboard('access-token', '2026-07-01', '2026-07-22')).rejects.toEqual(
      expect.objectContaining({
        status: 503,
        code: 'INTERNAL_ERROR',
        message: 'The reporting projection could not be loaded.',
      }),
    );
  });
});
