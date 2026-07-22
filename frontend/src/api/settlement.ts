import type { components } from './generated/schema';
import { apiClient } from './client';

export type ReceivableStatus = components['schemas']['ReceivableStatus'];
export type ReceivableSummary = components['schemas']['ReceivableSummary'];
export type ReceivablePage = components['schemas']['ReceivablePage'];
export type ReceivableDetail = components['schemas']['ReceivableDetail'];
export type PaymentRecord = components['schemas']['PaymentRecord'];
export type RecordPaymentRequest = components['schemas']['RecordPaymentRequest'];
export type ReversePaymentRequest = components['schemas']['ReversePaymentRequest'];

type Problem = components['schemas']['Problem'];

export interface ReceivableListQuery {
  pageSize?: number;
  cursor?: string;
  status?: ReceivableStatus[];
}

export class SettlementApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly currentVersion: number | undefined,
    message: string,
  ) {
    super(message);
  }
}

function authorization(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

function apiError(response: Response, problem?: Problem): SettlementApiError {
  return new SettlementApiError(
    response.status,
    problem?.code ?? 'INTERNAL_ERROR',
    problem?.currentVersion,
    problem?.detail ?? 'The settlement request could not be completed.',
  );
}

export async function listReceivables(
  accessToken: string,
  query: ReceivableListQuery,
  signal?: AbortSignal,
): Promise<ReceivablePage> {
  const { data, error, response } = await apiClient.GET('/receivables', {
    headers: authorization(accessToken),
    params: { query },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function getReceivable(
  accessToken: string,
  receivableId: string,
  signal?: AbortSignal,
): Promise<ReceivableDetail> {
  const { data, error, response } = await apiClient.GET('/receivables/{receivableId}', {
    headers: authorization(accessToken),
    params: { path: { receivableId } },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function recordPayment(
  accessToken: string,
  receivableId: string,
  version: number,
  body: RecordPaymentRequest,
  idempotencyKey = `payment-ui-${crypto.randomUUID()}`,
): Promise<ReceivableDetail> {
  const ifMatch = `"${version}"`;
  const { data, error, response } = await apiClient.POST('/receivables/{receivableId}/payments', {
    headers: {
      ...authorization(accessToken),
      'If-Match': ifMatch,
      'Idempotency-Key': idempotencyKey,
    },
    params: {
      path: { receivableId },
      header: { 'If-Match': ifMatch, 'Idempotency-Key': idempotencyKey },
    },
    body,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function reversePayment(
  accessToken: string,
  receivableId: string,
  paymentId: string,
  version: number,
  body: ReversePaymentRequest,
  idempotencyKey = `reversal-ui-${crypto.randomUUID()}`,
): Promise<ReceivableDetail> {
  const ifMatch = `"${version}"`;
  const { data, error, response } = await apiClient.POST(
    '/receivables/{receivableId}/payments/{paymentId}/reversal',
    {
      headers: {
        ...authorization(accessToken),
        'If-Match': ifMatch,
        'Idempotency-Key': idempotencyKey,
      },
      params: {
        path: { receivableId, paymentId },
        header: { 'If-Match': ifMatch, 'Idempotency-Key': idempotencyKey },
      },
      body,
    },
  );
  if (data !== undefined) return data;
  throw apiError(response, error);
}
