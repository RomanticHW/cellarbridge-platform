import type { components } from './generated/schema';
import { apiClient } from './client';

export type InventoryReservationDetail = components['schemas']['InventoryReservationDetail'];
export type InventoryReservationOperationRequest =
  components['schemas']['InventoryReservationOperationRequest'];
export type InventoryReservationOperationResult =
  components['schemas']['InventoryReservationOperationResult'];

type Problem = components['schemas']['Problem'];

export class ReservationApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

function headers(accessToken: string, idempotencyKey?: string) {
  return {
    Authorization: `Bearer ${accessToken}`,
    ...(idempotencyKey === undefined ? {} : { 'Idempotency-Key': idempotencyKey }),
  };
}

function apiError(response: Response, problem?: Problem): ReservationApiError {
  return new ReservationApiError(
    response.status,
    problem?.code ?? 'INTERNAL_ERROR',
    problem?.detail ?? 'The Reservation request could not be completed.',
  );
}

export async function getReservationByOrder(
  accessToken: string,
  orderId: string,
  signal?: AbortSignal,
): Promise<InventoryReservationDetail> {
  const { data, error, response } = await apiClient.GET(
    '/inventory/reservations/by-order/{orderId}',
    {
      headers: headers(accessToken),
      params: { path: { orderId } },
      signal,
    },
  );
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function releaseReservation(
  accessToken: string,
  reservationId: string,
  idempotencyKey: string,
  body: InventoryReservationOperationRequest,
): Promise<InventoryReservationOperationResult> {
  const { data, error, response } = await apiClient.POST(
    '/inventory/reservations/{reservationId}/release',
    {
      headers: headers(accessToken, idempotencyKey),
      params: {
        path: { reservationId },
        header: { 'Idempotency-Key': idempotencyKey },
      },
      body,
    },
  );
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function consumeReservation(
  accessToken: string,
  reservationId: string,
  idempotencyKey: string,
  body: InventoryReservationOperationRequest,
): Promise<InventoryReservationOperationResult> {
  const { data, error, response } = await apiClient.POST(
    '/inventory/reservations/{reservationId}/consume',
    {
      headers: headers(accessToken, idempotencyKey),
      params: {
        path: { reservationId },
        header: { 'Idempotency-Key': idempotencyKey },
      },
      body,
    },
  );
  if (data !== undefined) return data;
  throw apiError(response, error);
}
