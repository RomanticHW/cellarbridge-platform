import { useQuery } from '@tanstack/react-query';
import { Button, Select, Space, Table, Tag, Typography, type TableProps } from 'antd';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useCurrentUser } from '../../api/currentUser';
import { listOrders, OrderApiError, type OrderListItem, type OrderStatus } from '../../api/orders';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';

const statuses: OrderStatus[] = [
  'PENDING_RESERVATION',
  'RESERVED',
  'RESERVATION_FAILED',
  'READY_FOR_FULFILLMENT',
  'IN_FULFILLMENT',
  'FULFILLED',
  'CANCELLATION_PENDING',
  'CANCELLATION_FAILED',
  'CANCELLED',
];

const statusOptions = statuses.map((status) => ({
  value: status,
  label: status.replaceAll('_', ' '),
}));

const statusColors: Record<OrderStatus, string> = {
  PENDING_RESERVATION: 'processing',
  RESERVED: 'cyan',
  RESERVATION_FAILED: 'error',
  READY_FOR_FULFILLMENT: 'blue',
  IN_FULFILLMENT: 'geekblue',
  FULFILLED: 'success',
  CANCELLATION_PENDING: 'warning',
  CANCELLATION_FAILED: 'error',
  CANCELLED: 'default',
};
const orderTableLocale = { emptyText: 'No orders match the current filters.' };
const orderTableScroll = { x: 880 };

const columns: TableProps<OrderListItem>['columns'] = [
  {
    title: 'Order',
    render: (_, order) => (
      <Space orientation="vertical" size={0}>
        <Link to={`/app/orders/${order.id}`}>{order.number}</Link>
        <Typography.Text type="secondary">From {order.sourceQuotationNumber}</Typography.Text>
      </Space>
    ),
  },
  { title: 'Customer', dataIndex: 'partnerName' },
  {
    title: 'Status',
    dataIndex: 'status',
    render: (status: OrderStatus) => (
      <Tag color={statusColors[status]}>{status.replaceAll('_', ' ')}</Tag>
    ),
  },
  {
    title: 'Total',
    dataIndex: 'total',
    render: (total: OrderListItem['total']) => `${total.currency} ${total.amount}`,
  },
  {
    title: 'Route',
    dataIndex: 'routeCode',
    render: (route: string) => route.replaceAll('_', ' '),
  },
  {
    title: 'Created',
    dataIndex: 'createdAt',
    render: (value: string) => new Date(value).toLocaleString(),
  },
];

export function OrderListPage() {
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const currentUser = useCurrentUser(accessToken, session.sessionIdentity);
  const partnerId = currentUser.data?.partnerId ?? null;
  const [selectedStatuses, setSelectedStatuses] = useState<OrderStatus[]>([]);
  const [cursor, setCursor] = useState<string>();
  const orders = useQuery({
    queryKey: ['orders', 'list', partnerId, selectedStatuses, cursor],
    queryFn: ({ signal }) =>
      listOrders(
        accessToken,
        partnerId,
        {
          cursor,
          pageSize: 50,
          status: selectedStatuses.length === 0 ? undefined : selectedStatuses,
        },
        signal,
      ),
    enabled: accessToken !== '' && currentUser.data !== undefined,
  });

  if (orders.isPending) return <LoadingState message="Loading orders…" />;
  if (orders.isError) {
    const problem = orders.error instanceof OrderApiError ? orders.error : undefined;
    return (
      <ErrorState
        title={problem?.status === 403 ? 'Order access denied' : 'Orders are unavailable'}
        description={
          problem === undefined
            ? 'The order list could not be loaded.'
            : `${problem.message} (${problem.code})`
        }
        actionLabel="Try again"
        onRetry={() => void orders.refetch()}
      />
    );
  }

  const updateStatuses = (values: OrderStatus[]) => {
    setSelectedStatuses(values);
    setCursor(undefined);
  };

  return (
    <section className="order-page" aria-labelledby="order-list-title">
      <Space orientation="vertical" size="large" className="order-page-stack">
        <header className="order-page-heading">
          <div>
            <Typography.Title id="order-list-title" level={2}>
              Trade orders
            </Typography.Title>
            <Typography.Paragraph type="secondary">
              Review immutable accepted-quotation snapshots and downstream process readiness.
            </Typography.Paragraph>
          </div>
          {partnerId === null ? null : <Tag color="blue">Buyer-safe view</Tag>}
        </header>
        <div className="order-filters" role="search" aria-label="Order filters">
          <Select
            aria-label="Filter by order status"
            mode="multiple"
            placeholder="All statuses"
            value={selectedStatuses}
            onChange={updateStatuses}
            options={statusOptions}
          />
        </div>
        <Table
          rowKey="id"
          columns={columns}
          dataSource={orders.data.items}
          pagination={false}
          locale={orderTableLocale}
          scroll={orderTableScroll}
        />
        {orders.data.pageInfo.hasNext && orders.data.pageInfo.nextCursor ? (
          <Button onClick={() => setCursor(orders.data.pageInfo.nextCursor ?? undefined)}>
            Load next page
          </Button>
        ) : null}
      </Space>
    </section>
  );
}
