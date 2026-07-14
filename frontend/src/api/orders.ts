import type { components } from './generated/schema';
import { apiClient } from './client';

export type OrderStatus = components['schemas']['OrderStatus'];
export type OrderSummary = components['schemas']['OrderSummary'];
export type BuyerOrderSummary = components['schemas']['BuyerOrderSummary'];
export type OrderPage = components['schemas']['OrderPage'];
export type BuyerOrderPage = components['schemas']['BuyerOrderPage'];
export type OrderDetail = components['schemas']['OrderDetail'];
export type BuyerOrderDetail = components['schemas']['BuyerOrderDetail'];
export type OrderListItem = OrderSummary | BuyerOrderSummary;
export type OrderListPage = OrderPage | BuyerOrderPage;
export type OrderDetailView = OrderDetail | BuyerOrderDetail;

type Problem = components['schemas']['Problem'];

export interface OrderListQuery {
  pageSize?: number;
  cursor?: string;
  status?: OrderStatus[];
}

export class OrderApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

function authorization(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

function apiError(response: Response, problem?: Problem): OrderApiError {
  return new OrderApiError(
    response.status,
    problem?.code ?? 'INTERNAL_ERROR',
    problem?.detail ?? 'The order request could not be completed.',
  );
}

export async function listOrders(
  accessToken: string,
  partnerId: string | null,
  query: OrderListQuery,
  signal?: AbortSignal,
): Promise<OrderListPage> {
  if (partnerId !== null) {
    const { data, error, response } = await apiClient.GET('/buyer/orders', {
      headers: authorization(accessToken),
      params: { query },
      signal,
    });
    if (data !== undefined) return data;
    throw apiError(response, error);
  }

  const { data, error, response } = await apiClient.GET('/orders', {
    headers: authorization(accessToken),
    params: { query },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function getOrder(
  accessToken: string,
  partnerId: string | null,
  orderId: string,
  signal?: AbortSignal,
): Promise<OrderDetailView> {
  if (partnerId !== null) {
    const { data, error, response } = await apiClient.GET('/buyer/orders/{orderId}', {
      headers: authorization(accessToken),
      params: { path: { orderId } },
      signal,
    });
    if (data !== undefined) return data;
    throw apiError(response, error);
  }

  const { data, error, response } = await apiClient.GET('/orders/{orderId}', {
    headers: authorization(accessToken),
    params: { path: { orderId } },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}
