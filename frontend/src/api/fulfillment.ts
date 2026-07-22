import type { components } from './generated/schema';
import { apiClient } from './client';

export type FulfillmentStatus = components['schemas']['FulfillmentStatus'];
export type FulfillmentStepStatus = components['schemas']['FulfillmentStepStatus'];
export type FulfillmentPlanSummary = components['schemas']['FulfillmentPlanSummary'];
export type FulfillmentPlanPage = components['schemas']['FulfillmentPlanPage'];
export type FulfillmentPlanDetail = components['schemas']['FulfillmentPlanDetail'];
export type FulfillmentAction = components['schemas']['FulfillmentStepActionRequest']['action'];
export type FulfillmentActionResult = components['schemas']['FulfillmentStepActionResult'];

type Problem = components['schemas']['Problem'];

export interface FulfillmentListQuery {
  pageSize?: number;
  cursor?: string;
  status?: FulfillmentStatus[];
  overdue?: boolean;
  ownerRole?: string;
  orderId?: string;
}

export class FulfillmentApiError extends Error {
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

function apiError(response: Response, problem?: Problem): FulfillmentApiError {
  return new FulfillmentApiError(
    response.status,
    problem?.code ?? 'INTERNAL_ERROR',
    problem?.currentVersion,
    problem?.detail ?? 'The fulfillment request could not be completed.',
  );
}

export async function listFulfillmentPlans(
  accessToken: string,
  query: FulfillmentListQuery,
  signal?: AbortSignal,
): Promise<FulfillmentPlanPage> {
  const { data, error, response } = await apiClient.GET('/fulfillment/plans', {
    headers: authorization(accessToken),
    params: { query },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function getFulfillmentPlan(
  accessToken: string,
  planId: string,
  signal?: AbortSignal,
): Promise<FulfillmentPlanDetail> {
  const { data, error, response } = await apiClient.GET('/fulfillment/plans/{planId}', {
    headers: authorization(accessToken),
    params: { path: { planId } },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function actOnFulfillmentStep(
  accessToken: string,
  planId: string,
  stepId: string,
  version: number,
  action: FulfillmentAction,
  reason?: string,
  scenario?: 'SUCCESS' | 'FAILURE' | 'DELAY',
): Promise<FulfillmentActionResult> {
  const idempotencyKey = `fulfillment-ui-${crypto.randomUUID()}`;
  const { data, error, response } = await apiClient.POST(
    '/fulfillment/plans/{planId}/steps/{stepId}/actions',
    {
      headers: {
        ...authorization(accessToken),
        'If-Match': `"${version}"`,
        'Idempotency-Key': idempotencyKey,
      },
      params: {
        path: { planId, stepId },
        header: {
          'If-Match': `"${version}"`,
          'Idempotency-Key': idempotencyKey,
        },
      },
      body: {
        action,
        reason,
        resultData: scenario === undefined ? undefined : { scenario },
      },
    },
  );
  if (data !== undefined) return data;
  throw apiError(response, error);
}
