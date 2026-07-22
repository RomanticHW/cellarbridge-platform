import type { components } from './generated/schema';
import { apiClient } from './client';

export type ExceptionStatus = components['schemas']['ExceptionStatus'];
export type ExceptionSeverity = components['schemas']['ExceptionSeverity'];
export type ExceptionCategory = components['schemas']['ExceptionCategory'];
export type ExceptionSummary = components['schemas']['ExceptionSummary'];
export type ExceptionPage = components['schemas']['ExceptionPage'];
export type ExceptionDetail = components['schemas']['ExceptionDetail'];
export type RecoveryAction = components['schemas']['RecoveryAction'];
export type RecoveryResult = components['schemas']['RecoveryResult'];
export type ClosureReasonCode = components['schemas']['ExceptionClosureRequest']['reasonCode'];
export type FailedEventDeliveryPage = components['schemas']['FailedEventDeliveryPage'];

type Problem = components['schemas']['Problem'];

export interface ExceptionListQuery {
  pageSize?: number;
  cursor?: string;
  status?: ExceptionStatus[];
  severity?: ExceptionSeverity;
  assigneeId?: string;
  sourceType?: string;
  overdue?: boolean;
}

export class ExceptionApiError extends Error {
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

function apiError(response: Response, problem?: Problem): ExceptionApiError {
  return new ExceptionApiError(
    response.status,
    problem?.code ?? 'INTERNAL_ERROR',
    problem?.currentVersion,
    problem?.detail ?? 'The exception request could not be completed.',
  );
}

export async function listExceptions(
  accessToken: string,
  query: ExceptionListQuery,
  signal?: AbortSignal,
): Promise<ExceptionPage> {
  const { data, error, response } = await apiClient.GET('/exceptions', {
    headers: authorization(accessToken),
    params: { query },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function getException(
  accessToken: string,
  exceptionId: string,
  signal?: AbortSignal,
): Promise<ExceptionDetail> {
  const { data, error, response } = await apiClient.GET('/exceptions/{exceptionId}', {
    headers: authorization(accessToken),
    params: { path: { exceptionId } },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function assignException(
  accessToken: string,
  exceptionId: string,
  version: number,
  assigneeId: string,
  reason: string,
): Promise<ExceptionDetail> {
  const { data, error, response } = await apiClient.POST('/exceptions/{exceptionId}/assignment', {
    headers: { ...authorization(accessToken), 'If-Match': `"${version}"` },
    params: {
      path: { exceptionId },
      header: { 'If-Match': `"${version}"` },
    },
    body: { assigneeId, reason },
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function transitionException(
  accessToken: string,
  exceptionId: string,
  version: number,
  action: 'ACKNOWLEDGE' | 'BEGIN_INVESTIGATION',
  reason: string,
): Promise<ExceptionDetail> {
  const { data, error, response } = await apiClient.POST('/exceptions/{exceptionId}/actions', {
    headers: { ...authorization(accessToken), 'If-Match': `"${version}"` },
    params: {
      path: { exceptionId },
      header: { 'If-Match': `"${version}"` },
    },
    body: { action, reason },
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function recoverException(
  accessToken: string,
  exceptionId: string,
  version: number,
  action: RecoveryAction,
  reason: string,
): Promise<RecoveryResult> {
  const idempotencyKey = `exception-ui-${crypto.randomUUID()}`;
  const { data, error, response } = await apiClient.POST(
    '/exceptions/{exceptionId}/recovery-attempts',
    {
      headers: {
        ...authorization(accessToken),
        'If-Match': `"${version}"`,
        'Idempotency-Key': idempotencyKey,
      },
      params: {
        path: { exceptionId },
        header: {
          'If-Match': `"${version}"`,
          'Idempotency-Key': idempotencyKey,
        },
      },
      body: {
        action,
        reason,
        parameters: {},
      },
    },
  );
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function closeException(
  accessToken: string,
  exceptionId: string,
  version: number,
  reasonCode: ClosureReasonCode,
  reason: string,
  primaryCaseId?: string,
): Promise<ExceptionDetail> {
  const { data, error, response } = await apiClient.POST('/exceptions/{exceptionId}/closure', {
    headers: { ...authorization(accessToken), 'If-Match': `"${version}"` },
    params: {
      path: { exceptionId },
      header: { 'If-Match': `"${version}"` },
    },
    body: { reasonCode, reason, primaryCaseId },
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function listFailedEventPublications(
  accessToken: string,
  pageSize = 50,
  cursor?: string,
  signal?: AbortSignal,
): Promise<FailedEventDeliveryPage> {
  const { data, error, response } = await apiClient.GET('/event-publications/failed', {
    headers: authorization(accessToken),
    params: { query: { pageSize, cursor } },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}
