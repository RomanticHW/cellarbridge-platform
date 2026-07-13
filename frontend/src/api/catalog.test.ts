import { beforeEach, describe, expect, it, vi } from 'vitest';
import { searchCatalog, type CatalogApiError, type CatalogSearchPage } from './catalog';

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

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

describe('catalog API client', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('serializes supported search filters and authorization', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(page));

    await expect(
      searchCatalog('access-token', {
        keyword: 'Moonlit Terrace',
        producer: 'Silver Vale Estate',
        region: 'Lumen Valley',
        countryCode: 'FR',
        category: 'RED',
        vintage: '2019',
        volumeMl: 750,
        supplyType: ['DOMESTIC_ON_HAND', 'BONDED_ON_HAND'],
        availabilityClass: ['AVAILABLE'],
        automaticallyReservable: true,
        sort: 'name',
        pageSize: 25,
      }),
    ).resolves.toEqual(page);

    const request = fetchMock.mock.calls[0]?.[0] as Request;
    const url = new URL(request.url);
    expect(request.headers.get('authorization')).toBe('Bearer access-token');
    expect(url.searchParams.get('keyword')).toBe('Moonlit Terrace');
    expect(url.searchParams.get('producer')).toBe('Silver Vale Estate');
    expect(url.searchParams.get('sort')).toBe('name');
    expect(
      url.searchParams
        .getAll('supplyType')
        .flatMap((value) => value.split(','))
        .sort(),
    ).toEqual(['BONDED_ON_HAND', 'DOMESTIC_ON_HAND']);
  });

  it('preserves stable problem details for forbidden search', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      json(
        {
          type: 'about:blank',
          title: 'Access denied',
          status: 403,
          detail: 'Inventory read access is required',
          code: 'ACCESS_DENIED',
          traceId: 'trace-401',
          retryable: false,
        },
        403,
      ),
    );

    await expect(searchCatalog('access-token', {})).rejects.toEqual(
      expect.objectContaining<Partial<CatalogApiError>>({
        status: 403,
        code: 'ACCESS_DENIED',
        message: 'Inventory read access is required',
      }),
    );
  });
});
