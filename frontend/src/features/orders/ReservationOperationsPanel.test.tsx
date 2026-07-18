import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { InventoryReservationDetail } from '../../api/reservations';
import { ReservationOperationsPanel } from './ReservationOperationsPanel';

const orderId = '41000000-0000-4000-8000-000000000001';
const reservationId = '42000000-0000-4000-8000-000000000001';
const allocationId = '43000000-0000-4000-8000-000000000001';
const lotId = '44000000-0000-4000-8000-000000000001';
const poolId = '36000000-0000-4000-8000-000000000001';

const confirmed: InventoryReservationDetail = {
  id: reservationId,
  orderId,
  status: 'CONFIRMED',
  failureCode: null,
  version: 3,
  createdAt: '2026-07-18T07:00:00Z',
  updatedAt: '2026-07-18T07:01:00Z',
  requestedLines: [
    {
      orderLineId: '45000000-0000-4000-8000-000000000001',
      skuId: '34000000-0000-4000-8000-000000000001',
      quantity: '2.000000',
      quantityUnit: 'CASE',
    },
  ],
  allocations: [
    {
      id: allocationId,
      orderLineId: '45000000-0000-4000-8000-000000000001',
      skuId: '34000000-0000-4000-8000-000000000001',
      allocatedQuantity: '2.000000',
      releasedQuantity: '0.000000',
      consumedQuantity: '0.000000',
      remainingReservedQuantity: '2.000000',
      quantityUnit: 'CASE',
      supplyType: 'DOMESTIC_ON_HAND',
      supplyPoolId: poolId,
      lotId,
      warehouseLabel: 'Eastbank Distribution Center',
      warehousePriority: 10,
      warehouseVersion: 0,
    },
  ],
  shortages: [],
  attempts: [
    {
      attemptNumber: 1,
      outcome: 'CONFIRMED',
      failureCode: null,
      startedAt: '2026-07-18T07:00:00Z',
      completedAt: '2026-07-18T07:01:00Z',
    },
  ],
  operations: [],
  allowedActions: ['RELEASE', 'CONSUME'],
};

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json',
    },
  });
}

function requestOf(input: string | URL | Request): Request {
  return input instanceof Request ? input : new Request(input);
}

function renderPanel(
  permissions: readonly string[],
  element: ReactElement = (
    <ReservationOperationsPanel
      accessToken="test-token"
      orderId={orderId}
      permissions={permissions}
    />
  ),
) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  return render(<QueryClientProvider client={client}>{element}</QueryClientProvider>);
}

describe('ReservationOperationsPanel', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('shows confirmed evidence and only enables server-authorized operations', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(response(confirmed));

    renderPanel(['inventory:read', 'inventory:read-exact', 'inventory:reserve']);

    expect(await screen.findByText('CONFIRMED')).toBeVisible();
    expect(screen.getByText(lotId)).toBeVisible();
    expect(screen.getByText('Eastbank Distribution Center')).toBeVisible();
    expect(screen.getByText(`Pool ${poolId}`)).toBeVisible();
    expect(screen.getByText('Attempt 1 · CONFIRMED')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Release remaining' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Consume remaining' })).toBeEnabled();
  });

  it('keeps exact inventory fields and operation controls redacted without permission', async () => {
    const redacted: InventoryReservationDetail = {
      ...confirmed,
      allocations: confirmed.allocations.map((allocation) => ({
        ...allocation,
        supplyPoolId: null,
        lotId: null,
        warehouseLabel: null,
        warehousePriority: null,
        warehouseVersion: null,
      })),
      allowedActions: [],
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(response(redacted));

    renderPanel(['inventory:read']);

    expect(await screen.findByText('Restricted')).toBeVisible();
    expect(screen.queryByText(lotId)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Release remaining' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Consume remaining' })).not.toBeInTheDocument();
  });

  it('reuses the same idempotency key when a release response must be retried', async () => {
    const idempotencyKeys: string[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const request = requestOf(input);
      if (request.method === 'GET') return Promise.resolve(response(confirmed));
      idempotencyKeys.push(request.headers.get('Idempotency-Key') ?? '');
      return Promise.resolve(
        response(
          {
            code: 'INVENTORY_ALLOCATION_CONFLICT',
            detail: 'Inventory changed while the operation was being committed.',
          },
          409,
        ),
      );
    });
    const user = userEvent.setup();
    renderPanel(['inventory:read', 'inventory:reserve']);

    const release = await screen.findByRole('button', { name: 'Release remaining' });
    await user.click(release);
    await user.click(await screen.findByRole('button', { name: 'OK' }));
    expect(await screen.findByText(/INVENTORY_ALLOCATION_CONFLICT/)).toBeVisible();

    await user.click(release);
    await user.click(await screen.findByRole('button', { name: 'OK' }));
    await waitFor(() => expect(idempotencyKeys).toHaveLength(2));
    expect(idempotencyKeys[0]).not.toBe('');
    expect(idempotencyKeys[1]).toBe(idempotencyKeys[0]);
  });

  it('renders a failed attempt and shortage without exposing operation controls', async () => {
    const failed: InventoryReservationDetail = {
      ...confirmed,
      status: 'FAILED',
      failureCode: 'INVENTORY_INSUFFICIENT',
      allocations: [],
      shortages: [
        {
          orderLineId: confirmed.requestedLines[0].orderLineId,
          skuId: confirmed.requestedLines[0].skuId,
          requestedQuantity: '2.000000',
          availableQuantity: '1.000000',
          shortageQuantity: '1.000000',
          quantityUnit: 'CASE',
          failureCode: 'INVENTORY_INSUFFICIENT',
        },
      ],
      attempts: [
        {
          attemptNumber: 1,
          outcome: 'FAILED',
          failureCode: 'INVENTORY_INSUFFICIENT',
          startedAt: '2026-07-18T07:00:00Z',
          completedAt: '2026-07-18T07:01:00Z',
        },
      ],
      allowedActions: [],
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(response(failed));

    renderPanel(['inventory:read', 'inventory:reserve']);

    expect(await screen.findByText('FAILED')).toBeVisible();
    expect(
      screen.getByText('Available inventory was insufficient for the full order.'),
    ).toBeVisible();
    expect(screen.getByText(/shortage 1\.000000 case/)).toBeVisible();
    expect(screen.getByText('Attempt 1 · FAILED')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Release remaining' })).not.toBeInTheDocument();
  });
});
