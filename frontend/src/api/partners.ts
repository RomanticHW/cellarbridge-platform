import type { components, operations } from './generated/schema';
import { apiClient } from './client';

export type PartnerStatus = components['schemas']['PartnerStatus'];
export type PartnerSummary = components['schemas']['PartnerSummary'];
export type PartnerPage = components['schemas']['PartnerPage'];
export type PartnerDetail = components['schemas']['PartnerDetail'];
export type PartnerCommandResult = components['schemas']['PartnerCommandResult'];
export type CreatePartnerRequest = components['schemas']['CreatePartnerRequest'];
export type UpdatePartnerRequest = components['schemas']['UpdatePartnerRequest'];
export type PartnerReviewRequest = components['schemas']['PartnerReviewRequest'];
export type PartnerReasonRequest = components['schemas']['PartnerReasonRequest'];
export type PartnerListQuery = operations['listPartners']['parameters']['query'];

type Problem = components['schemas']['Problem'];

export class PartnerApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly currentVersion?: number,
    readonly currentState?: string,
    readonly errors: ReadonlyArray<{ field: string; code: string; message: string }> = [],
  ) {
    super(message);
  }
}

export interface Versioned<T> {
  data: T;
  etag: string;
}

function authorization(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

function apiError(response: Response, problem?: Problem): PartnerApiError {
  return new PartnerApiError(
    response.status,
    problem?.code ?? 'INTERNAL_ERROR',
    problem?.detail ?? 'The partner request could not be completed.',
    problem?.currentVersion,
    problem?.currentState,
    problem?.errors ?? [],
  );
}

function versioned<T extends { version: number }>(data: T, response: Response): Versioned<T> {
  return { data, etag: response.headers.get('etag') ?? `"${data.version}"` };
}

export async function listPartners(
  accessToken: string,
  query: PartnerListQuery,
  signal?: AbortSignal,
): Promise<PartnerPage> {
  const { data, error, response } = await apiClient.GET('/partners', {
    headers: authorization(accessToken),
    params: { query },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function getPartner(
  accessToken: string,
  partnerId: string,
  signal?: AbortSignal,
): Promise<Versioned<PartnerDetail>> {
  const { data, error, response } = await apiClient.GET('/partners/{partnerId}', {
    headers: authorization(accessToken),
    params: { path: { partnerId } },
    signal,
  });
  if (data !== undefined) return versioned(data, response);
  throw apiError(response, error);
}

export async function createPartner(
  accessToken: string,
  request: CreatePartnerRequest,
): Promise<Versioned<PartnerDetail>> {
  const { data, error, response } = await apiClient.POST('/partners', {
    headers: authorization(accessToken),
    body: request,
  });
  if (data !== undefined) return versioned(data, response);
  throw apiError(response, error);
}

export async function updatePartner(
  accessToken: string,
  partnerId: string,
  etag: string,
  request: UpdatePartnerRequest,
): Promise<Versioned<PartnerDetail>> {
  const { data, error, response } = await apiClient.PATCH('/partners/{partnerId}', {
    headers: {
      ...authorization(accessToken),
      'Content-Type': 'application/merge-patch+json',
    },
    params: { path: { partnerId }, header: { 'If-Match': etag } },
    body: request,
  });
  if (data !== undefined) return versioned(data, response);
  throw apiError(response, error);
}

export async function submitPartner(
  accessToken: string,
  partnerId: string,
  etag: string,
): Promise<Versioned<PartnerCommandResult>> {
  const { data, error, response } = await apiClient.POST('/partners/{partnerId}/submission', {
    headers: authorization(accessToken),
    params: { path: { partnerId }, header: { 'If-Match': etag } },
  });
  if (data !== undefined) return versioned(data, response);
  throw apiError(response, error);
}

export async function reviewPartner(
  accessToken: string,
  partnerId: string,
  etag: string,
  request: PartnerReviewRequest,
): Promise<Versioned<PartnerCommandResult>> {
  const { data, error, response } = await apiClient.POST('/partners/{partnerId}/review', {
    headers: authorization(accessToken),
    params: { path: { partnerId }, header: { 'If-Match': etag } },
    body: request,
  });
  if (data !== undefined) return versioned(data, response);
  throw apiError(response, error);
}

export async function suspendPartner(
  accessToken: string,
  partnerId: string,
  etag: string,
  request: PartnerReasonRequest,
): Promise<Versioned<PartnerCommandResult>> {
  const { data, error, response } = await apiClient.POST('/partners/{partnerId}/suspension', {
    headers: authorization(accessToken),
    params: { path: { partnerId }, header: { 'If-Match': etag } },
    body: request,
  });
  if (data !== undefined) return versioned(data, response);
  throw apiError(response, error);
}

export async function requestPartnerReactivation(
  accessToken: string,
  partnerId: string,
  etag: string,
  request: PartnerReasonRequest,
): Promise<Versioned<PartnerCommandResult>> {
  const { data, error, response } = await apiClient.POST('/partners/{partnerId}/reactivation', {
    headers: authorization(accessToken),
    params: { path: { partnerId }, header: { 'If-Match': etag } },
    body: request,
  });
  if (data !== undefined) return versioned(data, response);
  throw apiError(response, error);
}
