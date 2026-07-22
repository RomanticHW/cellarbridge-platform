import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ExceptionDetail, ExceptionSummary } from '../../api/exceptions';
import { Application } from '../../app/App';
import { router } from '../../app/router';
import { AuthSessionProvider } from '../identity-access/AuthSessionProvider';
import type { AuthSession } from '../identity-access/authSession';

const exceptionId = '81000000-0000-4000-8000-000000000001';
const summary: ExceptionSummary = {
  id: exceptionId,
  number: 'EXC-202607-000001',
  category: 'FULFILLMENT_STEP_FAILED',
  severity: 'HIGH',
  status: 'IN_PROGRESS',
  sourceType: 'FULFILLMENT_STEP',
  sourceId: '72000000-0000-4000-8000-000000000001',
  sourceNumber: 'FUL-202607-000001/DISPATCH',
  summary: 'Fulfillment step requires operational recovery',
  assigneeId: '11200000-0000-4000-8000-000000000005',
  openedAt: '2026-07-21T20:00:00Z',
  dueAt: '2026-07-21T22:00:00Z',
  version: 4,
};
const detail: ExceptionDetail = {
  summary,
  safeDetails: {
    planId: '71000000-0000-4000-8000-000000000001',
    stepId: '72000000-0000-4000-8000-000000000001',
    failureCode: 'ADAPTER_TIMEOUT',
  },
  occurrences: [
    {
      sourceEventId: '91000000-0000-4000-8000-000000000001',
      eventType: 'cellarbridge.fulfillment.step-failed.v1',
      detectedAt: '2026-07-21T20:00:00Z',
      evidence: { failureCode: 'ADAPTER_TIMEOUT', attempt: 2 },
    },
  ],
  history: [
    {
      action: 'BEGIN_INVESTIGATION',
      actorType: 'INTERNAL_USER',
      actorId: '11200000-0000-4000-8000-000000000005',
      previousStatus: 'ACKNOWLEDGED',
      newStatus: 'IN_PROGRESS',
      reasonCode: 'BEGIN_INVESTIGATION',
      safeReason: 'Recovery prerequisites are confirmed',
      correlationId: '92000000-0000-4000-8000-000000000001',
      occurredAt: '2026-07-21T20:05:00Z',
    },
  ],
  recoveries: [],
  allowedRecoveryActions: ['RETRY_FULFILLMENT_STEP', 'MANUAL_ACKNOWLEDGE'],
  allowedActions: ['REQUEST_RECOVERY'],
};
const currentUser = {
  userId: '11200000-0000-4000-8000-000000000005',
  displayName: 'North Trade',
  partnerId: null,
  tenant: {
    id: '10000000-0000-4000-8000-000000000001',
    name: 'North Cellars',
    status: 'ACTIVE',
  },
  roles: ['Trade Operator'],
  permissions: [
    'exception:read',
    'exception:assign',
    'exception:recover',
    'event-publication:read',
    'event-publication:replay',
  ],
} as const;
const session: AuthSession = {
  isLoading: false,
  isAuthenticated: true,
  accessToken: 'test-access-token',
  sessionIdentity: '11000000-0000-4000-8000-000000000005',
  signIn: vi.fn(),
  signOut: vi.fn(),
  clearSession: vi.fn(),
};

function response(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
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

describe('exception workspace', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('exposes a live queue, source filters and a masked publication workspace', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(incoming(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      if (path.endsWith('/event-publications/failed')) {
        return Promise.resolve(
          response({
            items: [
              {
                eventId: '91000000-0000-4000-8000-000000000002',
                eventType: 'cellarbridge.test.delivery.v1',
                consumerName: 'test-consumer',
                status: 'FAILED_FINAL',
                attempts: 3,
                nextRetryAt: null,
                errorCode: 'HANDLER_REJECTED',
                lastAttemptAt: '2026-07-21T20:00:00Z',
                version: 2,
              },
            ],
            nextCursor: null,
            hasNext: false,
            pageSize: 50,
          }),
        );
      }
      return Promise.resolve(
        response({ items: [summary], nextCursor: null, hasNext: false, pageSize: 50 }),
      );
    });
    await router.navigate('/app/exceptions');
    renderApplication();

    expect(await screen.findByRole('heading', { name: 'Exception Center' })).toBeVisible();
    expect(screen.getByRole('link', { name: summary.number })).toHaveAttribute(
      'href',
      `/app/exceptions/${exceptionId}`,
    );
    expect(screen.getByRole('combobox', { name: 'Filter by source type' })).toBeVisible();
    const navigation = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(within(navigation).getByText('Exception Center')).toBeVisible();

    await userEvent.click(screen.getByRole('tab', { name: 'Failed publications' }));
    expect(await screen.findByText('HANDLER_REJECTED')).toBeVisible();
    expect(screen.queryByText(/commercial-secret/i)).not.toBeInTheDocument();
  });

  it('renders immutable evidence and submits a versioned recovery command', async () => {
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = incoming(input);
      const path = new URL(request.url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      if (request.method === 'POST') {
        requests.push(request);
        return Promise.resolve(
          response({
            caseId: exceptionId,
            attemptId: '93000000-0000-4000-8000-000000000001',
            status: 'SUCCEEDED',
            resultCode: 'SOURCE_STATE_VERIFIED',
            safeResult: 'Source state verified',
            sourceState: 'IN_PROGRESS/READY',
            caseStatus: 'RESOLVED',
            version: 6,
            completedAt: '2026-07-21T20:10:00Z',
            replayed: false,
          }),
        );
      }
      return Promise.resolve(response(detail));
    });
    await router.navigate(`/app/exceptions/${exceptionId}`);
    renderApplication();

    expect(await screen.findByRole('heading', { name: summary.number })).toBeVisible();
    expect(screen.getAllByText(/ADAPTER_TIMEOUT/)).toHaveLength(2);
    await userEvent.click(screen.getByRole('button', { name: 'Choose recovery' }));
    await userEvent.type(
      screen.getByRole('textbox', { name: 'Operational reason' }),
      'Retry after source readiness review',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(requests).toHaveLength(1);
    expect(requests[0]?.headers.get('if-match')).toBe('"4"');
    expect(requests[0]?.headers.get('idempotency-key')).toMatch(/^exception-ui-/);
  });
});
