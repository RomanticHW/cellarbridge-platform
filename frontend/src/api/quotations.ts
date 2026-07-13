import type { components, operations } from './generated/schema';
import { apiClient } from './client';

export type QuotationStatus = components['schemas']['QuotationStatus'];
export type QuotationSummary = components['schemas']['QuotationSummary'];
export type QuotationPage = components['schemas']['QuotationPage'];
export type QuotationDetail = components['schemas']['QuotationDetail'];
export type QuotationDraftRequest = components['schemas']['CreateQuotationRequest'];
export type QuotationApprovalRequest = components['schemas']['QuotationApprovalRequest'];
export type QuotationCommandResult = components['schemas']['QuotationCommandResult'];
export type RouteEvaluation = components['schemas']['RouteEvaluation'];
export type PublicQuotation = components['schemas']['PublicQuotation'];
export type TradeRouteCode = components['schemas']['TradeRouteCode'];
export type QuotationListQuery = NonNullable<operations['listQuotations']['parameters']['query']>;
export type IssueQuotationResult =
  operations['issueQuotation']['responses'][200]['content']['application/json'];

type Problem = components['schemas']['Problem'];

export class QuotationApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly currentVersion?: number,
    readonly currentState?: string,
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

function apiError(response: Response, problem?: Problem): QuotationApiError {
  return new QuotationApiError(
    response.status,
    problem?.code ?? 'INTERNAL_ERROR',
    problem?.detail ?? 'The quotation request could not be completed.',
    problem?.currentVersion,
    problem?.currentState,
  );
}

function versioned<T extends { version: number }>(data: T, response: Response): Versioned<T> {
  return { data, etag: response.headers.get('etag') ?? `"${data.version}"` };
}

export async function listQuotations(
  accessToken: string,
  query: QuotationListQuery,
  signal?: AbortSignal,
): Promise<QuotationPage> {
  const { data, error, response } = await apiClient.GET('/quotations', {
    headers: authorization(accessToken),
    params: { query },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function getQuotation(
  accessToken: string,
  quotationId: string,
  signal?: AbortSignal,
): Promise<Versioned<QuotationDetail>> {
  const { data, error, response } = await apiClient.GET('/quotations/{quotationId}', {
    headers: authorization(accessToken),
    params: { path: { quotationId } },
    signal,
  });
  if (data !== undefined) return versioned(data, response);
  throw apiError(response, error);
}

export async function createQuotation(
  accessToken: string,
  request: QuotationDraftRequest,
): Promise<Versioned<QuotationDetail>> {
  const { data, error, response } = await apiClient.POST('/quotations', {
    headers: authorization(accessToken),
    body: request,
  });
  if (data !== undefined) return versioned(data, response);
  throw apiError(response, error);
}

export async function updateQuotation(
  accessToken: string,
  quotationId: string,
  etag: string,
  request: QuotationDraftRequest,
): Promise<Versioned<QuotationDetail>> {
  const { data, error, response } = await apiClient.PUT('/quotations/{quotationId}', {
    headers: authorization(accessToken),
    params: { path: { quotationId }, header: { 'If-Match': etag } },
    body: request,
  });
  if (data !== undefined) return versioned(data, response);
  throw apiError(response, error);
}

export async function evaluateQuotationRoutes(
  accessToken: string,
  quotationId: string,
  etag: string,
  request?: { requestedRouteCode?: TradeRouteCode; overrideReason?: string },
): Promise<RouteEvaluation> {
  const { data, error, response } = await apiClient.POST(
    '/quotations/{quotationId}/route-evaluations',
    {
      headers: authorization(accessToken),
      params: { path: { quotationId }, header: { 'If-Match': etag } },
      body: request,
    },
  );
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function submitQuotation(
  accessToken: string,
  quotationId: string,
  etag: string,
): Promise<Versioned<QuotationCommandResult>> {
  const { data, error, response } = await apiClient.POST('/quotations/{quotationId}/submission', {
    headers: authorization(accessToken),
    params: { path: { quotationId }, header: { 'If-Match': etag } },
  });
  if (data !== undefined) return versioned(data, response);
  throw apiError(response, error);
}

export async function decideQuotationApproval(
  accessToken: string,
  quotationId: string,
  etag: string,
  request: QuotationApprovalRequest,
): Promise<Versioned<QuotationCommandResult>> {
  const { data, error, response } = await apiClient.POST('/quotations/{quotationId}/approval', {
    headers: authorization(accessToken),
    params: { path: { quotationId }, header: { 'If-Match': etag } },
    body: request,
  });
  if (data !== undefined) return versioned(data, response);
  throw apiError(response, error);
}

export async function issueQuotation(
  accessToken: string,
  quotationId: string,
  etag: string,
): Promise<Versioned<IssueQuotationResult>> {
  const { data, error, response } = await apiClient.POST('/quotations/{quotationId}/issue', {
    headers: authorization(accessToken),
    params: { path: { quotationId }, header: { 'If-Match': etag } },
  });
  if (data !== undefined) return versioned(data, response);
  throw apiError(response, error);
}

export async function getPublicQuotation(
  publicToken: string,
  signal?: AbortSignal,
): Promise<PublicQuotation> {
  const { data, error, response } = await apiClient.GET('/portal/quotations/{publicToken}', {
    params: { path: { publicToken } },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}
