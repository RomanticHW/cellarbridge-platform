import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PartnerDetail } from '../../api/partners';
import { Application } from '../../app/App';
import { router } from '../../app/router';
import { AuthSessionProvider } from '../identity-access/AuthSessionProvider';
import type { AuthSession } from '../identity-access/authSession';

const partnerId = '30000000-0000-4000-8000-000000000001';
const currentUser = {
  userId: '11200000-0000-4000-8000-000000000003',
  displayName: 'North Manager',
  tenant: {
    id: '10000000-0000-4000-8000-000000000001',
    name: 'North Cellars',
    status: 'ACTIVE',
  },
  roles: ['Sales Manager'],
  permissions: ['partner:read', 'partner:create', 'partner:submit', 'partner:review'],
};

const authenticatedSession: AuthSession = {
  isLoading: false,
  isAuthenticated: true,
  accessToken: 'test-access-token',
  sessionIdentity: '11000000-0000-4000-8000-000000000003',
  signIn: vi.fn(),
  signOut: vi.fn(),
  clearSession: vi.fn(),
};

function detail(status: PartnerDetail['status'], allowedActions: string[]): PartnerDetail {
  return {
    id: partnerId,
    number: 'PAR-202607-000001',
    legalName: 'Cedar Dining Group',
    displayName: 'Cedar Dining',
    status,
    defaultCurrency: 'CNY',
    routeEligibility: ['SH_GENERAL_TRADE'],
    salesOwnerId: '11200000-0000-4000-8000-000000000001',
    version: status === 'DRAFT' ? 0 : 1,
    updatedAt: '2026-07-13T08:00:00Z',
    type: 'RESTAURANT_GROUP',
    registrationIdentifierMasked: '****0301',
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
    allowedActions,
    duplicateWarnings: [],
    timeline: [
      {
        id: '31000000-0000-4000-8000-000000000001',
        occurredAt: '2026-07-13T08:00:00Z',
        action: 'PARTNER_CREATED',
        previousState: null,
        newState: 'DRAFT',
        safeReason: null,
        changedFields: ['legalName', 'contact'],
      },
    ],
  };
}

function response(body: unknown, status = 200, etag?: string) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json',
      ...(etag === undefined ? {} : { ETag: etag }),
    },
  });
}

function request(input: string | URL | Request): Request {
  return input instanceof Request ? input : new Request(input);
}

function renderApplication() {
  return render(
    <AuthSessionProvider value={authenticatedSession}>
      <Application />
    </AuthSessionProvider>,
  );
}

describe('partner workspace', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('lists tenant partners and opens a traceable detail page', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const incoming = request(input);
      const path = new URL(incoming.url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      if (path.endsWith('/partners')) {
        return Promise.resolve(
          response({
            items: [detail('DRAFT', ['EDIT', 'SUBMIT'])],
            pageInfo: { nextCursor: null, hasNext: false, pageSize: 25 },
          }),
        );
      }
      return Promise.resolve(response(detail('DRAFT', ['EDIT', 'SUBMIT']), 200, '"0"'));
    });
    await router.navigate('/app/partners');
    const user = userEvent.setup();
    renderApplication();

    expect(await screen.findByRole('heading', { name: 'Partners' })).toBeVisible();
    await user.click(screen.getByRole('link', { name: 'Cedar Dining' }));

    expect(await screen.findByText('Partner profile')).toBeVisible();
    expect(screen.getByText('****0301')).toBeVisible();
    expect(screen.getByText('PARTNER CREATED')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Submit for review' })).toBeVisible();
  });

  it('creates a partner draft through the validated editor', async () => {
    let created = false;
    let submittedBody: Record<string, unknown> | undefined;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const incoming = request(input);
      const path = new URL(incoming.url).pathname;
      if (path.endsWith('/me')) return response(currentUser);
      if (incoming.method === 'POST' && path.endsWith('/partners')) {
        created = true;
        submittedBody = (await incoming.json()) as Record<string, unknown>;
        return response(detail('DRAFT', ['EDIT', 'SUBMIT']), 201, '"0"');
      }
      return response(detail('DRAFT', ['EDIT', 'SUBMIT']), 200, '"0"');
    });
    await router.navigate('/app/partners/new');
    const user = userEvent.setup();
    renderApplication();
    expect(await screen.findByRole('heading', { name: 'Create partner draft' })).toBeVisible();

    const fill = (name: string, value: string) => {
      const input = document.querySelector<HTMLInputElement>(`input[name="${name}"]`);
      expect(input).not.toBeNull();
      fireEvent.change(input as HTMLInputElement, { target: { value } });
    };
    fill('legalName', 'Cedar Dining Group');
    fill('displayName', 'Cedar Dining');
    fill('contactName', 'Lin Wen');
    fill('contactEmail', 'lin.wen@example.test');
    fill('province', 'Shanghai');
    fill('city', 'Shanghai');
    fill('line1', '301 Huaihai Road');
    await user.click(screen.getByRole('button', { name: 'Save partner draft' }));

    await vi.waitFor(() => expect(created).toBe(true));
    expect(submittedBody).toMatchObject({
      legalName: 'Cedar Dining Group',
      displayName: 'Cedar Dining',
      contact: { name: 'Lin Wen', email: 'lin.wen@example.test' },
    });
    expect(await screen.findByText('Partner profile')).toBeVisible();
  });

  it('saves an incomplete draft without forcing submission-only fields', async () => {
    let created = false;
    let submittedBody: Record<string, unknown> | undefined;
    const incompleteDetail = {
      ...detail('DRAFT', ['EDIT', 'SUBMIT']),
      legalName: null,
      displayName: null,
      contacts: [],
      billingAddress: null,
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const incoming = request(input);
      const path = new URL(incoming.url).pathname;
      if (path.endsWith('/me')) return response(currentUser);
      if (incoming.method === 'POST' && path.endsWith('/partners')) {
        created = true;
        submittedBody = (await incoming.json()) as Record<string, unknown>;
        return response(incompleteDetail, 201, '"0"');
      }
      if (created && path.endsWith(`/partners/${partnerId}`)) {
        return response(incompleteDetail, 200, '"0"');
      }
      return response(detail('DRAFT', ['EDIT', 'SUBMIT']), 200, '"0"');
    });
    await router.navigate('/app/partners/new');
    const user = userEvent.setup();
    renderApplication();

    expect(await screen.findByText(/Save an incomplete draft at any stage/)).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Save partner draft' }));

    await vi.waitFor(() => expect(submittedBody).toBeDefined());
    expect(submittedBody).toMatchObject({
      legalName: '',
      displayName: '',
      requestedRouteCodes: [],
      requestedServiceRegions: [],
      contact: { name: '', email: '' },
    });
    expect(await screen.findByText('Partner profile')).toBeVisible();
    expect(screen.getByText(/Legal name not provided/)).toBeVisible();
  });

  it('loads an editable draft and surfaces a version conflict', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const incoming = request(input);
      const path = new URL(incoming.url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      if (incoming.method === 'PATCH') {
        return Promise.resolve(
          response(
            {
              type: 'about:blank',
              title: 'Partner changed',
              status: 412,
              detail: 'The partner changed after it was loaded',
              code: 'RESOURCE_VERSION_CONFLICT',
              traceId: 'trace-305',
              retryable: false,
              currentVersion: 1,
              currentState: 'DRAFT',
            },
            412,
          ),
        );
      }
      return Promise.resolve(response(detail('DRAFT', ['EDIT', 'SUBMIT']), 200, '"0"'));
    });
    await router.navigate(`/app/partners/${partnerId}/edit`);
    const user = userEvent.setup();
    renderApplication();

    expect(await screen.findByRole('heading', { name: 'Edit partner draft' })).toBeVisible();
    expect(screen.getByText('Protected registration identifier')).toBeVisible();
    const displayName = document.querySelector<HTMLInputElement>('input[name="displayName"]');
    expect(displayName).not.toBeNull();
    await user.clear(displayName as HTMLInputElement);
    await user.type(displayName as HTMLInputElement, 'Cedar Group');
    await user.click(screen.getByRole('button', { name: 'Save partner draft' }));

    expect(await screen.findByText('This partner changed after you opened it')).toBeVisible();
  });

  it('renders empty and forbidden list states without leaking another tenant', async () => {
    let forbidden = false;
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(request(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      if (forbidden) {
        return Promise.resolve(
          response(
            {
              type: 'about:blank',
              title: 'Access denied',
              status: 403,
              code: 'ACCESS_DENIED',
              traceId: 'trace-302',
              retryable: false,
            },
            403,
          ),
        );
      }
      return Promise.resolve(
        response({ items: [], pageInfo: { nextCursor: null, hasNext: false, pageSize: 25 } }),
      );
    });
    await router.navigate('/app/partners');
    const first = renderApplication();
    expect(await screen.findByText('No partners match the current filters.')).toBeVisible();
    first.unmount();

    forbidden = true;
    renderApplication();
    expect(await screen.findByText('Partner access denied')).toBeVisible();
  });

  it('shows complete-field guidance when draft submission is unprocessable', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const incoming = request(input);
      const path = new URL(incoming.url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      if (incoming.method === 'POST') {
        return Promise.resolve(
          response(
            {
              type: 'about:blank',
              title: 'Partner profile is incomplete',
              status: 422,
              detail: 'Partner profile is incomplete',
              code: 'PARTNER_PROFILE_INCOMPLETE',
              traceId: 'trace-303',
              retryable: false,
              errors: [
                {
                  field: 'registrationIdentifier',
                  code: 'REQUIRED',
                  message: 'Required before submission',
                },
              ],
            },
            422,
          ),
        );
      }
      return Promise.resolve(response(detail('DRAFT', ['EDIT', 'SUBMIT']), 200, '"0"'));
    });
    await router.navigate(`/app/partners/${partnerId}`);
    const user = userEvent.setup();
    renderApplication();

    await user.click(await screen.findByRole('button', { name: 'Submit for review' }));
    expect(await screen.findByText('Complete the partner profile before submission')).toBeVisible();
    expect(screen.getByText(/Required fields: registrationIdentifier/)).toBeVisible();
  });

  it('explains the independent-review conflict and records a successful review', async () => {
    let conflict = true;
    let active = false;
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const incoming = request(input);
      const path = new URL(incoming.url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      if (incoming.method === 'POST') {
        if (conflict) {
          conflict = false;
          return Promise.resolve(
            response(
              {
                type: 'about:blank',
                title: 'Independent review required',
                status: 409,
                detail: 'The submitter cannot review this partner',
                code: 'PARTNER_REVIEWER_CONFLICT',
                traceId: 'trace-304',
                retryable: false,
              },
              409,
            ),
          );
        }
        active = true;
        return Promise.resolve(
          response(
            {
              partnerId,
              number: 'PAR-202607-000001',
              status: 'ACTIVE',
              version: 2,
              allowedActions: ['SUSPEND'],
            },
            200,
            '"2"',
          ),
        );
      }
      return Promise.resolve(
        response(
          active ? detail('ACTIVE', ['SUSPEND']) : detail('PENDING_REVIEW', ['REVIEW']),
          200,
          active ? '"2"' : '"1"',
        ),
      );
    });
    await router.navigate(`/app/partners/${partnerId}`);
    const user = userEvent.setup();
    renderApplication();

    const reason = await screen.findByRole('textbox', { name: 'Reason' });
    await user.type(reason, 'Commercial profile verified');
    await user.click(screen.getByRole('button', { name: 'Record review decision' }));
    await vi.waitFor(() => expect(conflict).toBe(false));
    expect(
      (await screen.findAllByText('Submitter cannot review this partner', { exact: false })).length,
    ).toBeGreaterThan(0);

    await user.click(screen.getByRole('button', { name: 'Record review decision' }));
    expect(await screen.findByText('ACTIVE')).toBeVisible();
  });
});
