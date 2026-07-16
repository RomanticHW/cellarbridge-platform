import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Card,
  Descriptions,
  List,
  Space,
  Table,
  Tag,
  Typography,
  type TableProps,
} from 'antd';
import { Link, useParams } from 'react-router-dom';
import { useCurrentUser } from '../../api/currentUser';
import {
  getOrder,
  OrderApiError,
  type BuyerOrderDetail,
  type OrderDetail,
  type OrderDetailView,
  type OrderStatus,
} from '../../api/orders';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';

type OrderLine =
  | OrderDetail['commercialSnapshot']['lines'][number]
  | BuyerOrderDetail['commercialSnapshot']['lines'][number];
type TimelineEntry = OrderDetail['timeline'][number] | BuyerOrderDetail['timeline'][number];
type SupplyDecisionLine = NonNullable<OrderDetail['supplyDecision']>['lineDecisions'][number];

const lineColumns: TableProps<OrderLine>['columns'] = [
  {
    title: 'Product',
    render: (_, line) => (
      <Space orientation="vertical" size={0}>
        <Typography.Text strong>{line.description}</Typography.Text>
        <Typography.Text type="secondary">{line.skuCode}</Typography.Text>
      </Space>
    ),
  },
  {
    title: 'Quantity',
    render: (_, line) => `${line.quantity.value} ${line.quantity.unit.toLowerCase()}`,
  },
  {
    title: 'Net unit price',
    render: (_, line) => `${line.netUnitPrice.currency} ${line.netUnitPrice.amount}`,
  },
  {
    title: 'Line total',
    render: (_, line) => `${line.lineTotal.currency} ${line.lineTotal.amount}`,
  },
];
const supplyDecisionColumns: TableProps<SupplyDecisionLine>['columns'] = [
  { title: 'Quotation line', dataIndex: 'quotationLineId' },
  {
    title: 'Allocation',
    render: (_, line) => line.allocationMode.replaceAll('_', ' '),
  },
  {
    title: 'Supply type',
    render: (_, line) => line.supplyType.replaceAll('_', ' '),
  },
  {
    title: 'Fixed pool',
    render: (_, line) => line.supplyPoolId ?? 'Route-eligible pool at reservation time',
  },
];

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
const responsiveColumns = { xs: 1 as const, md: 2 as const };
const orderLineTableScroll = { x: 720 };
const timelineLocale = { emptyText: 'No order milestones are available.' };

function isInternalOrder(detail: OrderDetailView): detail is OrderDetail {
  return 'partnerId' in detail;
}

function timelineKey(entry: TimelineEntry): string {
  return 'id' in entry ? entry.id : `${entry.occurredAt}-${entry.action}-${entry.newState}`;
}

function orderLineKey(line: OrderLine): string {
  return 'orderLineId' in line
    ? line.orderLineId
    : `${line.skuCode}-${line.quantity.value}-${line.netUnitPrice.amount}`;
}

export function OrderDetailPage() {
  const { orderId = '' } = useParams();
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const currentUser = useCurrentUser(accessToken, session.sessionIdentity);
  const partnerId = currentUser.data?.partnerId ?? null;
  const order = useQuery({
    queryKey: ['orders', 'detail', partnerId, orderId],
    queryFn: ({ signal }) => getOrder(accessToken, partnerId, orderId, signal),
    enabled: accessToken !== '' && currentUser.data !== undefined && orderId !== '',
  });

  if (order.isPending) return <LoadingState message="Loading order…" />;
  if (order.isError) {
    const problem = order.error instanceof OrderApiError ? order.error : undefined;
    return (
      <ErrorState
        title={problem?.status === 404 ? 'Order not found' : 'Order is unavailable'}
        description={
          problem === undefined
            ? 'The order could not be loaded.'
            : `${problem.message} (${problem.code})`
        }
        actionLabel="Try again"
        onRetry={() => void order.refetch()}
      />
    );
  }

  const detail = order.data;
  const internal = isInternalOrder(detail);
  const snapshot = detail.commercialSnapshot;
  const deliveryAddress = snapshot.deliveryAddress;
  const processProjections = [
    { key: 'reservation', title: 'Inventory reservation', projection: detail.reservation },
    { key: 'fulfillment', title: 'Fulfillment', projection: detail.fulfillment },
    { key: 'settlement', title: 'Settlement', projection: detail.settlement },
  ];

  return (
    <article className="order-page" aria-labelledby="order-detail-title">
      <Space orientation="vertical" size="large" className="order-page-stack">
        <header className="order-page-heading">
          <div>
            <Link to="/app/orders">← Back to orders</Link>
            <Typography.Title id="order-detail-title" level={2}>
              {detail.number}
            </Typography.Title>
            <Typography.Text type="secondary">
              Created from quotation {detail.sourceQuotationNumber}
            </Typography.Text>
          </div>
          <Space>
            {internal ? null : <Tag color="blue">Buyer-safe view</Tag>}
            <Tag color={statusColors[detail.status]}>{detail.status.replaceAll('_', ' ')}</Tag>
          </Space>
        </header>

        <Card title="Overview">
          <Descriptions column={responsiveColumns}>
            <Descriptions.Item label="Customer">{detail.partnerName}</Descriptions.Item>
            <Descriptions.Item label="Total">
              {detail.total.currency} {detail.total.amount}
            </Descriptions.Item>
            <Descriptions.Item label="Route">
              {detail.routeCode.replaceAll('_', ' ')}
            </Descriptions.Item>
            <Descriptions.Item label="Created">
              {new Date(detail.createdAt).toLocaleString()}
            </Descriptions.Item>
            <Descriptions.Item label="Source quotation">
              {detail.sourceQuotation.number} · revision {detail.sourceQuotation.revision}
            </Descriptions.Item>
            <Descriptions.Item label="Customer reference">
              {snapshot.customer.partnerNumber}
            </Descriptions.Item>
          </Descriptions>
        </Card>

        {internal ? (
          <Card
            title="Supply decision"
            extra={
              <Tag color={detail.supplyDecisionStatus === 'FROZEN' ? 'success' : 'warning'}>
                {detail.supplyDecisionStatus.replaceAll('_', ' ')}
              </Tag>
            }
          >
            {detail.supplyDecisionStatus === 'FROZEN' && detail.supplyDecision ? (
              <Space orientation="vertical" size="middle" className="order-page-stack">
                <Alert
                  type="info"
                  showIcon
                  title="Frozen supply decision is an allocation constraint, not an inventory reservation."
                />
                <Descriptions column={responsiveColumns}>
                  <Descriptions.Item label="Policy">
                    {detail.supplyDecision.policyVersion}
                  </Descriptions.Item>
                  <Descriptions.Item label="Route">
                    {detail.supplyDecision.selectedRouteCode.replaceAll('_', ' ')}
                  </Descriptions.Item>
                  <Descriptions.Item label="Decided">
                    {new Date(detail.supplyDecision.decidedAt).toLocaleString()}
                  </Descriptions.Item>
                  <Descriptions.Item label="Inventory data as of">
                    {new Date(detail.supplyDecision.inventoryDataAsOf).toLocaleString()}
                  </Descriptions.Item>
                  <Descriptions.Item label="Evaluation ID" span={2}>
                    <Typography.Text code>
                      {detail.supplyDecision.sourceRouteEvaluationId}
                    </Typography.Text>
                  </Descriptions.Item>
                  <Descriptions.Item label="Decision hash" span={2}>
                    <Typography.Text code>{detail.supplyDecision.decisionHash}</Typography.Text>
                  </Descriptions.Item>
                </Descriptions>
                <Table
                  rowKey="quotationLineId"
                  columns={supplyDecisionColumns}
                  dataSource={detail.supplyDecision.lineDecisions}
                  pagination={false}
                  scroll={orderLineTableScroll}
                />
              </Space>
            ) : (
              <Alert
                type="warning"
                showIcon
                title="Legacy order requires controlled supply-decision remediation before inventory reservation."
              />
            )}
          </Card>
        ) : null}

        <Card title="Immutable commercial snapshot">
          <Table
            rowKey={orderLineKey}
            columns={lineColumns}
            dataSource={snapshot.lines}
            pagination={false}
            scroll={orderLineTableScroll}
          />
          <Descriptions className="order-snapshot-terms" column={responsiveColumns}>
            <Descriptions.Item label="Payment terms">
              {snapshot.paymentTermDays} days
            </Descriptions.Item>
            <Descriptions.Item label="Terms version">
              {snapshot.acceptedTermsVersion ?? 'Not supplied'}
            </Descriptions.Item>
            <Descriptions.Item label="Requested delivery">
              {snapshot.requestedDeliveryDate ?? 'Not supplied'}
            </Descriptions.Item>
            <Descriptions.Item label="Estimated delivery">
              {snapshot.route.estimatedDeliveryDate}
            </Descriptions.Item>
            <Descriptions.Item label="Delivery address" span={2}>
              {deliveryAddress
                ? [
                    deliveryAddress.line1,
                    deliveryAddress.district,
                    deliveryAddress.city,
                    deliveryAddress.province,
                    deliveryAddress.postalCode,
                    deliveryAddress.countryCode,
                  ]
                    .filter(Boolean)
                    .join(', ')
                : 'Not supplied'}
            </Descriptions.Item>
            <Descriptions.Item label="Snapshot captured">
              {new Date(snapshot.capturedAt).toLocaleString()}
            </Descriptions.Item>
          </Descriptions>
        </Card>

        <section aria-labelledby="order-process-title">
          <Typography.Title id="order-process-title" level={3}>
            Downstream processes
          </Typography.Title>
          <div className="order-process-grid">
            {processProjections.map(({ key, title, projection }) => (
              <Card key={key} title={title} size="small">
                <Tag
                  color={
                    projection.status === 'PENDING'
                      ? 'processing'
                      : projection.status === 'BLOCKED'
                        ? 'warning'
                        : 'default'
                  }
                >
                  {projection.status.replaceAll('_', ' ')}
                </Tag>
                <Typography.Paragraph className="order-process-message" type="secondary">
                  {projection.message}
                </Typography.Paragraph>
              </Card>
            ))}
          </div>
        </section>

        <Card title="Timeline">
          <List
            dataSource={detail.timeline}
            rowKey={timelineKey}
            locale={timelineLocale}
            renderItem={(entry) => {
              const safeReason = 'safeReason' in entry ? entry.safeReason : null;
              return (
                <List.Item>
                  <List.Item.Meta
                    title={entry.action.replaceAll('_', ' ')}
                    description={`${new Date(entry.occurredAt).toLocaleString()} · ${entry.newState.replaceAll('_', ' ')}`}
                  />
                  {typeof safeReason === 'string' && safeReason !== '' ? (
                    <Typography.Text type="secondary">{safeReason}</Typography.Text>
                  ) : null}
                </List.Item>
              );
            }}
          />
        </Card>
      </Space>
    </article>
  );
}
