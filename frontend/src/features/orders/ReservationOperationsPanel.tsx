import { useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Descriptions, Popconfirm, Space, Table, Tag, Typography } from 'antd';
import type { TableProps } from 'antd';
import {
  consumeReservation,
  getReservationByOrder,
  releaseReservation,
  ReservationApiError,
  type InventoryReservationDetail,
  type InventoryReservationOperationRequest,
} from '../../api/reservations';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';

interface ReservationOperationsPanelProps {
  accessToken: string;
  orderId: string;
  permissions: readonly string[];
}

type Allocation = InventoryReservationDetail['allocations'][number];
type Operation = 'RELEASE' | 'CONSUME';

const statusColors: Record<InventoryReservationDetail['status'], string> = {
  PENDING: 'processing',
  CONFIRMED: 'cyan',
  FAILED: 'error',
  RELEASED: 'blue',
  CONSUMED: 'success',
};

const allocationColumns: TableProps<Allocation>['columns'] = [
  {
    title: 'Order line',
    dataIndex: 'orderLineId',
    render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
  },
  { title: 'Supply type', render: (_, item) => item.supplyType.replaceAll('_', ' ') },
  {
    title: 'Allocated',
    render: (_, item) => `${item.allocatedQuantity} ${item.quantityUnit.toLowerCase()}`,
  },
  {
    title: 'Remaining',
    render: (_, item) => `${item.remainingReservedQuantity} ${item.quantityUnit.toLowerCase()}`,
  },
  {
    title: 'Released / consumed',
    render: (_, item) => `${item.releasedQuantity} / ${item.consumedQuantity}`,
  },
  {
    title: 'Inventory evidence',
    render: (_, item) =>
      item.lotId === null ? (
        <Typography.Text type="secondary">Restricted</Typography.Text>
      ) : (
        <Space orientation="vertical" size={0}>
          <Typography.Text code>{item.lotId}</Typography.Text>
          <Typography.Text type="secondary">{item.warehouseLabel}</Typography.Text>
          <Typography.Text type="secondary">Pool {item.supplyPoolId}</Typography.Text>
        </Space>
      ),
  },
];

const failureLabels: Record<string, string> = {
  SUPPLY_DECISION_MISSING: 'Legacy order has no verified Supply Decision.',
  MANUAL_CONFIRMATION_REQUIRED: 'This supply requires manual confirmation.',
  SUPPLY_NOT_AUTOMATICALLY_RESERVABLE: 'This supply cannot be reserved automatically.',
  INVENTORY_INSUFFICIENT: 'Available inventory was insufficient for the full order.',
  INVENTORY_FIXED_POOL_INELIGIBLE: 'The fixed supply pool was not eligible.',
  INVENTORY_ALLOCATION_CONFLICT: 'Inventory changed while allocation was being committed.',
};

export function ReservationOperationsPanel({
  accessToken,
  orderId,
  permissions,
}: ReservationOperationsPanelProps) {
  const queryClient = useQueryClient();
  const operationKeys = useRef(new Map<string, string>());
  const reservation = useQuery({
    queryKey: ['inventory', 'reservation', 'order', orderId],
    queryFn: ({ signal }) => getReservationByOrder(accessToken, orderId, signal),
    enabled: accessToken !== '' && orderId !== '' && permissions.includes('inventory:read'),
  });
  const operation = useMutation({
    mutationFn: async ({
      type,
      detail,
    }: {
      type: Operation;
      detail: InventoryReservationDetail;
    }) => {
      const allocations: InventoryReservationOperationRequest['allocations'] = detail.allocations
        .filter((item) => Number(item.remainingReservedQuantity) > 0)
        .map((item) => ({
          allocationId: item.id,
          quantity: item.remainingReservedQuantity,
          quantityUnit: item.quantityUnit,
        }));
      const operationIdentity = `${detail.id}:${detail.version}:${type}`;
      const idempotencyKey = operationKeys.current.get(operationIdentity) ?? crypto.randomUUID();
      operationKeys.current.set(operationIdentity, idempotencyKey);
      return type === 'RELEASE'
        ? releaseReservation(accessToken, detail.id, idempotencyKey, { allocations })
        : consumeReservation(accessToken, detail.id, idempotencyKey, { allocations });
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['inventory', 'reservation', 'order', orderId] }),
        queryClient.invalidateQueries({ queryKey: ['orders', 'detail'] }),
      ]);
    },
  });

  if (!permissions.includes('inventory:read')) return null;
  if (reservation.isPending) return <LoadingState message="Loading Inventory Reservation…" />;
  if (reservation.isError) {
    const problem =
      reservation.error instanceof ReservationApiError ? reservation.error : undefined;
    return (
      <ErrorState
        title={problem?.status === 404 ? 'Reservation not available' : 'Reservation is unavailable'}
        description={
          problem === undefined
            ? 'The Reservation could not be loaded.'
            : `${problem.message} (${problem.code})`
        }
        actionLabel="Try again"
        onRetry={() => void reservation.refetch()}
      />
    );
  }

  const detail = reservation.data;
  const canOperate = permissions.includes('inventory:reserve');
  const canRelease = canOperate && detail.allowedActions.includes('RELEASE');
  const canConsume = canOperate && detail.allowedActions.includes('CONSUME');
  const operationProblem =
    operation.error instanceof ReservationApiError ? operation.error : undefined;

  return (
    <Card
      title="Inventory Reservation"
      extra={<Tag color={statusColors[detail.status]}>{detail.status}</Tag>}
    >
      <Space orientation="vertical" size="middle" className="order-page-stack">
        {detail.failureCode === null ? null : (
          <Alert
            type="warning"
            showIcon
            title={failureLabels[detail.failureCode] ?? 'Reservation failed.'}
            description={detail.failureCode}
          />
        )}
        {operationProblem === undefined ? null : (
          <Alert
            type="error"
            showIcon
            title="Reservation operation was rejected"
            description={`${operationProblem.message} (${operationProblem.code})`}
          />
        )}
        {operation.data === undefined ? null : (
          <Alert
            type="success"
            showIcon
            title={`${operation.data.action === 'RELEASE' ? 'Release' : 'Consumption'} completed`}
            description={
              operation.data.replayed
                ? 'The original idempotent result was replayed; inventory was not changed twice.'
                : operation.data.message
            }
          />
        )}
        <Descriptions column={{ xs: 1, md: 3 }}>
          <Descriptions.Item label="Reservation ID">{detail.id}</Descriptions.Item>
          <Descriptions.Item label="Version">{detail.version}</Descriptions.Item>
          <Descriptions.Item label="Updated">
            {new Date(detail.updatedAt).toLocaleString()}
          </Descriptions.Item>
        </Descriptions>
        <Table
          rowKey="id"
          columns={allocationColumns}
          dataSource={detail.allocations}
          pagination={false}
          locale={{ emptyText: 'No allocation was committed for this Reservation.' }}
          scroll={{ x: 960 }}
        />
        <section>
          <Typography.Text strong>Requested lines</Typography.Text>
          {detail.requestedLines.length === 0 ? (
            <Typography.Paragraph type="secondary">
              No requested line evidence is available.
            </Typography.Paragraph>
          ) : (
            <ul className="order-evidence-list">
              {detail.requestedLines.map((line) => (
                <li key={line.orderLineId}>
                  <Typography.Text>
                    {line.quantity} {line.quantityUnit.toLowerCase()}
                  </Typography.Text>
                  <Typography.Text type="secondary">
                    Order line {line.orderLineId} · SKU {line.skuId}
                  </Typography.Text>
                </li>
              ))}
            </ul>
          )}
        </section>
        {detail.shortages.map((shortage) => (
          <Alert
            key={shortage.orderLineId}
            type="warning"
            title={`${shortage.failureCode}: shortage ${shortage.shortageQuantity} ${shortage.quantityUnit.toLowerCase()}`}
            description={`Requested ${shortage.requestedQuantity}; observed available ${shortage.availableQuantity}.`}
          />
        ))}
        <section>
          <Typography.Text strong>Reservation attempts</Typography.Text>
          {detail.attempts.length === 0 ? (
            <Typography.Paragraph type="secondary">
              No completed Reservation attempt is available yet.
            </Typography.Paragraph>
          ) : (
            <ul className="order-evidence-list">
              {detail.attempts.map((attempt) => (
                <li key={`${attempt.attemptNumber}-${attempt.completedAt}`}>
                  <Typography.Text>
                    Attempt {attempt.attemptNumber} · {attempt.outcome}
                  </Typography.Text>
                  <Typography.Text type="secondary">
                    {attempt.failureCode ?? 'No failure'} ·{' '}
                    {new Date(attempt.completedAt).toLocaleString()}
                  </Typography.Text>
                </li>
              ))}
            </ul>
          )}
        </section>
        <section>
          <Typography.Text strong>Release and consumption audit</Typography.Text>
          {detail.operations.length === 0 ? (
            <Typography.Paragraph type="secondary">
              No release or consumption command has been recorded.
            </Typography.Paragraph>
          ) : (
            <ul className="order-evidence-list">
              {detail.operations.map((audit) => (
                <li key={audit.commandId}>
                  <Typography.Text>
                    {audit.action} · {audit.outcome}
                  </Typography.Text>
                  <Typography.Text type="secondary">
                    {audit.reasonCode} · {audit.previousState} → {audit.newState} ·{' '}
                    {new Date(audit.occurredAt).toLocaleString()}
                  </Typography.Text>
                </li>
              ))}
            </ul>
          )}
        </section>
        {canRelease || canConsume ? (
          <Space wrap>
            {canRelease ? (
              <Popconfirm
                title="Release all remaining reserved quantities?"
                onConfirm={() => operation.mutate({ type: 'RELEASE', detail })}
              >
                <Button disabled={operation.isPending}>Release remaining</Button>
              </Popconfirm>
            ) : null}
            {canConsume ? (
              <Popconfirm
                title="Consume all remaining reserved quantities?"
                onConfirm={() => operation.mutate({ type: 'CONSUME', detail })}
              >
                <Button type="primary" disabled={operation.isPending}>
                  Consume remaining
                </Button>
              </Popconfirm>
            ) : null}
            {operation.isPending ? <Typography.Text>Operation in progress…</Typography.Text> : null}
          </Space>
        ) : null}
      </Space>
    </Card>
  );
}
