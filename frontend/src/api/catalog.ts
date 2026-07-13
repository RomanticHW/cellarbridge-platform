import type { components, operations } from './generated/schema';
import { apiClient } from './client';

export type AvailabilityClass = components['schemas']['AvailabilityClass'];
export type CatalogSearchItem = components['schemas']['SkuSearchItem'];
export type CatalogSearchPage = components['schemas']['SkuSearchPage'];
export type CatalogSearchQuery = NonNullable<operations['searchSkus']['parameters']['query']>;
export type CatalogSku = components['schemas']['SkuSnapshot'];
export type SupplySummary = components['schemas']['SupplySummary'];
export type SupplyType = components['schemas']['SupplyType'];

type Problem = components['schemas']['Problem'];

export class CatalogApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

function apiError(response: Response, problem?: Problem): CatalogApiError {
  return new CatalogApiError(
    response.status,
    problem?.code ?? 'INTERNAL_ERROR',
    problem?.detail ?? 'The catalog search could not be completed.',
  );
}

export async function searchCatalog(
  accessToken: string,
  query: CatalogSearchQuery,
  signal?: AbortSignal,
): Promise<CatalogSearchPage> {
  const { data, error, response } = await apiClient.GET('/catalog/skus', {
    headers: { Authorization: `Bearer ${accessToken}` },
    params: { query },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}
