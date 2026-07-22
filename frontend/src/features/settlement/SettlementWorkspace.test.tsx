import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReceivableDetail, ReceivableSummary } from '../../api/settlement';
import { Application } from '../../app/App';
import { router } from '../../app/router';
import { AuthSessionProvider } from '../identity-access/AuthSessionProvider';
import type { AuthSession } from '../identity-access/authSession';

const receivableId = '81000000-0000-4000-8000-000000000001';
const paymentId = '82000000-0000-4000-8000-000000000001';
const summary: ReceivableSummary = {
  id: receivableId,
  number: 'REC-202607-000001',
  orderId: '51000000-0000-4000-8000-000000000001',
  orderNumber: 'ORD-202607-000001',
  partnerId: '30000000-0000-4000-8000-000000000001',
  partnerNumber: 'BUYER-001',
  partnerName: 'Harbor Imports',
  originalAmount: { amount: '100.0000', currency: 'USD' },
  outstandingAmount: { amount: '60.0000', currency: 'USD' },
  dueDate: '2026-08-01',
  status: 'PARTIALLY_PAID',
  version: 1,
};
const detail: ReceivableDetail = {
  ...summary,
  payments: [
    {
      id: paymentId,
      type: 'PAYMENT',
      amount: { amount: '40.0000', currency: 'USD' },
      externalReference: 'BANK-2026-001',
      method: 'BANK_TRANSFER',
      occurredOn: '2026-07-22',
      recordedAt: '2026-07-22T12:00:00Z',
      actorId: '11200000-0000-4000-8000-000000000004',
      reversibleAmount: { amount: '40.0000', currency: 'USD' },
    },
  ],
  history: [
    {
      id: '83000000-0000-4000-8000-000000000001',
      action: 'PAYMENT_RECORDED',
      previousStatus: 'OPEN',
      newStatus: 'PARTIALLY_PAID',
      amount: { amount: '40.0000', currency: 'USD' },
      actorId: '11200000-0000-4000-8000-000000000004',
      reason: null,
      occurredAt: '2026-07-22T12:00:00Z',
    },
  ],
  allowedActions: ['RECORD_PAYMENT', 'REVERSE_PAYMENT'],
  commercialAmountVisible: true,
};
const currentUser = {
  userId: '11200000-0000-4000-8000-000000000004',
  displayName: 'North Finance',
  partnerId: null,
  tenant: {
    id: '10000000-0000-4000-8000-000000000001',
    name: 'North Cellars',
    status: 'ACTIVE',
  },
  roles: ['Finance Specialist'],
  permissions: [
    'settlement:read',
    'settlement:read-commercial-sensitive',
    'settlement:record-payment',
    'settlement:reverse-payment',
  ],
} as const;
const session: AuthSession = {
  isLoading: false,
  isAuthenticated: true,
  accessToken: 'test-access-token',
  sessionIdentity: '11000000-0000-4000-8000-000000000004',
  signIn: vi.fn(),
  signOut: vi.fn(),
  clearSession: vi.fn(),
};

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json',
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

describe('settlement workspace', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('exposes the live receivables queue and commercial status evidence', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(incoming(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return Promise.resolve(
        response({
          items: [summary],
          pageInfo: { nextCursor: null, hasNext: false, pageSize: 50 },
        }),
      );
    });
    await router.navigate('/app/receivables');
    renderApplication();

    expect(await screen.findByRole('heading', { name: 'Receivables' })).toBeVisible();
    expect(screen.getByRole('link', { name: summary.number })).toHaveAttribute(
      'href',
      `/app/receivables/${receivableId}`,
    );
    expect(screen.getByText('USD 60.0000')).toBeVisible();
    expect(screen.getByText('PARTIALLY PAID')).toBeVisible();
    const navigation = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(within(navigation).getByText('Receivables')).toBeVisible();
  });

  it('records external payment evidence with the current version', async () => {
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = incoming(input);
      const path = new URL(request.url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      if (request.method === 'POST') {
        requests.push(request);
        return Promise.resolve(response({ ...detail, version: 2, status: 'PAID' }));
      }
      return Promise.resolve(response(detail));
    });
    await router.navigate(`/app/receivables/${receivableId}`);
    renderApplication();

    fireEvent.click(await screen.findByRole('button', { name: 'Record payment' }));
    expect(screen.getByRole('dialog', { name: 'Record external payment' })).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox', { name: 'External reference' }), {
      target: { value: 'BANK-2026-002' },
    });
    fireEvent.click(
      within(screen.getByRole('dialog', { name: 'Record external payment' })).getByRole('button', {
        name: 'Record payment',
      }),
    );

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].headers.get('if-match')).toBe('"1"');
    expect(await requests[0].clone().json()).toEqual(
      expect.objectContaining({
        amount: { amount: '60.0000', currency: 'USD' },
        externalReference: 'BANK-2026-002',
      }),
    );
  });

  it('requires explicit second confirmation before appending a reversal', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(incoming(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return Promise.resolve(response(detail));
    });
    await router.navigate(`/app/receivables/${receivableId}`);
    renderApplication();

    fireEvent.click(await screen.findByRole('button', { name: 'Reverse' }));
    const dialog = screen.getByRole('dialog', { name: 'Reverse payment evidence' });
    const confirm = within(dialog).getByRole('button', { name: 'Confirm reversal' });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Reversal reason' }), {
      target: { value: 'Duplicate remittance correction' },
    });
    expect(confirm).toBeDisabled();
    fireEvent.click(
      within(dialog).getByRole('checkbox', {
        name: /I reviewed the payment, amount, and reason/,
      }),
    );
    expect(confirm).toBeEnabled();
  }, 30_000);

  it('masks commercial evidence and actions for an auditor', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(incoming(input).url).pathname;
      if (path.endsWith('/me')) {
        return Promise.resolve(
          response({
            ...currentUser,
            displayName: 'North Auditor',
            roles: ['Auditor'],
            permissions: ['settlement:read'],
          }),
        );
      }
      return Promise.resolve(
        response({
          ...detail,
          originalAmount: null,
          outstandingAmount: null,
          commercialAmountVisible: false,
          allowedActions: [],
          payments: [
            {
              ...detail.payments[0],
              amount: null,
              externalReference: 'Restricted',
              actorId: null,
              reversibleAmount: null,
            },
          ],
          history: [{ ...detail.history[0], amount: null, actorId: null, reason: null }],
        }),
      );
    });
    await router.navigate(`/app/receivables/${receivableId}`);
    renderApplication();

    expect(await screen.findByText('Commercial amounts are restricted')).toBeVisible();
    expect(screen.getAllByText('Restricted')).not.toHaveLength(0);
    expect(screen.queryByRole('button', { name: 'Record payment' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reverse' })).not.toBeInTheDocument();
    expect(screen.getByText(/Status evidence/)).toBeVisible();
  });

  it('shows a recoverable access error for a forbidden receivables queue', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(incoming(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return Promise.resolve(
        response(
          {
            status: 403,
            code: 'ACCESS_DENIED',
            detail: 'Settlement access is not granted',
            retryable: false,
          },
          403,
        ),
      );
    });
    await router.navigate('/app/receivables');
    renderApplication();

    expect(await screen.findByText('Receivable access denied')).toBeVisible();
    expect(screen.getByText(/Settlement access is not granted/)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeVisible();
  });
});
