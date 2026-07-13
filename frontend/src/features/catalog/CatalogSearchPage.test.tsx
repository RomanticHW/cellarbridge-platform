import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CatalogSearchPage } from '../../api/catalog';
import { Application } from '../../app/App';
import { router } from '../../app/router';
import { AuthSessionProvider } from '../identity-access/AuthSessionProvider';
import type { AuthSession } from '../identity-access/authSession';

const currentUser = {
  userId: '11200000-0000-4000-8000-000000000001',
  displayName: 'North Sales',
  tenant: {
    id: '10000000-0000-4000-8000-000000000001',
    name: 'North Cellars',
    status: 'ACTIVE',
  },
  roles: ['Sales'],
  permissions: ['catalog:read', 'inventory:read'],
};

const authenticatedSession: AuthSession = {
  isLoading: false,
  isAuthenticated: true,
  accessToken: 'test-access-token',
  sessionIdentity: '11000000-0000-4000-8000-000000000001',
  signIn: vi.fn(),
  signOut: vi.fn(),
  clearSession: vi.fn(),
};

const page: CatalogSearchPage = {
  items: [
    {
      sku: {
        skuId: '34000000-0000-4000-8000-000000000001',
        skuCode: 'CB-MTV-2019-750X6',
        displayName: 'Moonlit Terrace',
        producerName: 'Silver Vale Estate',
        regionName: 'Lumen Valley',
        countryCode: 'FR',
        category: 'RED',
        vintage: '2019',
        volumeMl: 750,
        unitsPerCase: 6,
        packageType: 'CASE',
        status: 'ACTIVE',
        sourceVersion: 1,
        updatedAt: '2026-07-13T00:00:00Z',
      },
      supplies: [
        {
          supplyPoolId: '36000000-0000-4000-8000-000000000001',
          supplyType: 'DOMESTIC_ON_HAND',
          locationLabel: 'Eastbank Distribution Center',
          availabilityLevel: 'AVAILABLE',
          displayQuantityBand: 'HIGH',
          automaticallyReservable: true,
          exactLots: [],
          updatedAt: '2026-07-13T00:00:00Z',
        },
      ],
    },
  ],
  pageInfo: { nextCursor: null, hasNext: false, pageSize: 25 },
  dataAsOf: '2026-07-13T00:00:00Z',
  availabilityDisclaimer: 'Availability is informational and is not an inventory commitment.',
};

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
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

describe('catalog and supply workspace', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('debounces URL-backed filtering and keeps quote selection in local UI state', async () => {
    const catalogRequests: Request[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const incoming = request(input);
      const path = new URL(incoming.url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      catalogRequests.push(incoming);
      return Promise.resolve(response(page));
    });
    await router.navigate('/app/catalog');
    renderApplication();

    expect(await screen.findByRole('heading', { name: 'Catalog & supply search' })).toBeVisible();
    expect(screen.getByText('Moonlit Terrace')).toBeVisible();
    fireEvent.change(screen.getByRole('textbox', { name: 'Search catalog' }), {
      target: { value: 'Moonlit' },
    });
    await vi.waitFor(
      () =>
        expect(new URL(catalogRequests.at(-1)?.url ?? '').searchParams.get('keyword')).toBe(
          'Moonlit',
        ),
      { timeout: 1500 },
    );
    expect(new URL(window.location.href).searchParams.get('keyword')).toBe('Moonlit');

    fireEvent.click(await screen.findByRole('button', { name: 'Add to quote selection' }));
    expect(screen.getByText('Pending quote selection (1)')).toBeVisible();
    expect(screen.getByText(/No quotation, reservation, or order has been created/)).toBeVisible();
    expect(catalogRequests.every((catalogRequest) => catalogRequest.method === 'GET')).toBe(true);
  }, 10_000);

  it('renders loading and empty search states', async () => {
    let resolveCatalog: ((value: Response) => void) | undefined;
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(request(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return new Promise<Response>((resolve) => {
        resolveCatalog = resolve;
      });
    });
    await router.navigate('/app/catalog');
    renderApplication();
    expect(await screen.findByText('Searching catalog and supply…')).toBeVisible();
    resolveCatalog?.(
      response({
        ...page,
        items: [],
      }),
    );
    expect(await screen.findByText('No active SKUs match the current filters.')).toBeVisible();
  });

  it('hides navigation and renders a stable forbidden state for a buyer', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(request(input).url).pathname;
      if (path.endsWith('/me')) {
        return Promise.resolve(
          response({ ...currentUser, roles: ['Buyer'], permissions: ['catalog:read'] }),
        );
      }
      return Promise.resolve(
        response(
          {
            type: 'about:blank',
            title: 'Access denied',
            status: 403,
            detail: 'Inventory read access is required',
            code: 'ACCESS_DENIED',
            traceId: 'trace-402',
            retryable: false,
          },
          403,
        ),
      );
    });
    await router.navigate('/app/catalog');
    renderApplication();

    expect(await screen.findByText('Catalog supply access denied')).toBeVisible();
    expect(screen.queryByRole('menuitem', { name: 'Catalog & supply' })).not.toBeInTheDocument();
    expect(screen.getByText(/Inventory read access is required/)).toBeVisible();
  });

  it('offers retry for unexpected search failures', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const path = new URL(request(input).url).pathname;
      if (path.endsWith('/me')) return Promise.resolve(response(currentUser));
      return Promise.resolve(
        response(
          {
            type: 'about:blank',
            title: 'Unavailable',
            status: 503,
            detail: 'Search projection is unavailable',
            code: 'DEPENDENCY_UNAVAILABLE',
            traceId: 'trace-403',
            retryable: true,
          },
          503,
        ),
      );
    });
    await router.navigate('/app/catalog');
    renderApplication();

    expect(await screen.findByText('Catalog search is unavailable')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeVisible();
  });
});
