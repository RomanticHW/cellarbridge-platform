import { useQuery } from '@tanstack/react-query';
import { Badge, Button, Card, Checkbox, Select, Space, Table, Tag, Typography } from 'antd';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  FulfillmentApiError,
  listFulfillmentPlans,
  type FulfillmentPlanSummary,
  type FulfillmentStatus,
} from '../../api/fulfillment';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';

const statuses: FulfillmentStatus[] = [
  'PLANNED',
  'READY',
  'IN_PROGRESS',
  'ON_HOLD',
  'COMPLETED',
  'CANCELLING',
  'CANCELLATION_FAILED',
  'CANCELLED',
];

const statusColors: Record<FulfillmentStatus, string> = {
  PLANNED: 'default',
  READY: 'blue',
  IN_PROGRESS: 'processing',
  ON_HOLD: 'warning',
  COMPLETED: 'success',
  CANCELLING: 'warning',
  CANCELLATION_FAILED: 'error',
  CANCELLED: 'default',
};

export function FulfillmentBoardPage() {
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const [searchParams] = useSearchParams();
  const orderId = searchParams.get('orderId');
  const [selectedStatuses, setSelectedStatuses] = useState<FulfillmentStatus[]>([]);
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [ownerRole, setOwnerRole] = useState<string>();
  const [cursor, setCursor] = useState<string>();
  const plans = useQuery({
    queryKey: ['fulfillment', 'plans', selectedStatuses, overdueOnly, ownerRole, cursor],
    queryFn: ({ signal }) =>
      listFulfillmentPlans(
        accessToken,
        {
          pageSize: 50,
          cursor,
          status: selectedStatuses.length === 0 ? undefined : selectedStatuses,
          overdue: overdueOnly ? true : undefined,
          ownerRole,
          orderId: orderId ?? undefined,
        },
        signal,
      ),
    enabled: accessToken !== '',
  });

  if (plans.isPending) return <LoadingState message="Loading fulfillment work…" />;
  if (plans.isError) {
    const problem = plans.error instanceof FulfillmentApiError ? plans.error : undefined;
    return (
      <ErrorState
        title={problem?.status === 403 ? 'Fulfillment access denied' : 'Fulfillment is unavailable'}
        description={
          problem
            ? `${problem.message} (${problem.code})`
            : 'The fulfillment board could not be loaded.'
        }
        actionLabel="Try again"
        onRetry={() => void plans.refetch()}
      />
    );
  }

  const visible = plans.data.items;
  const active = visible.filter(
    (plan) => plan.status !== 'COMPLETED' && plan.status !== 'CANCELLED',
  );
  const overdueCount = visible.filter((plan) => plan.overdue).length;

  return (
    <section className="fulfillment-page" aria-labelledby="fulfillment-board-title">
      <Space orientation="vertical" size="large" className="fulfillment-page-stack">
        <header className="fulfillment-heading">
          <div>
            <Typography.Title id="fulfillment-board-title" level={2}>
              Fulfillment control board
            </Typography.Title>
            <Typography.Paragraph type="secondary">
              Coordinate route-bound work, dependencies and service-level deadlines.
            </Typography.Paragraph>
          </div>
          <Space size="large">
            <Badge count={active.length} showZero color="#315c45" overflowCount={999}>
              <Card size="small" className="fulfillment-stat">
                Active plans
              </Card>
            </Badge>
            <Badge count={overdueCount} showZero color={overdueCount > 0 ? '#b14b3b' : '#7f8c82'}>
              <Card size="small" className="fulfillment-stat">
                Overdue
              </Card>
            </Badge>
          </Space>
        </header>

        {orderId ? (
          <Card size="small">
            Showing fulfillment for order <Typography.Text code>{orderId}</Typography.Text>.{' '}
            <Link to="/app/fulfillment">Clear order filter</Link>
          </Card>
        ) : null}

        <div className="fulfillment-filters" role="search" aria-label="Fulfillment filters">
          <Select
            aria-label="Filter by fulfillment status"
            mode="multiple"
            placeholder="All plan statuses"
            value={selectedStatuses}
            onChange={(value) => {
              setSelectedStatuses(value);
              setCursor(undefined);
            }}
            options={statuses.map((status) => ({
              value: status,
              label: status.replaceAll('_', ' '),
            }))}
          />
          <Checkbox
            checked={overdueOnly}
            onChange={(event) => {
              setOverdueOnly(event.target.checked);
              setCursor(undefined);
            }}
          >
            Overdue only
          </Checkbox>
          <Select
            aria-label="Filter by fulfillment owner"
            allowClear
            placeholder="All owners"
            value={ownerRole}
            onChange={(value) => {
              setOwnerRole(value);
              setCursor(undefined);
            }}
            options={[
              { value: 'TRADE_OPERATOR', label: 'Trade operator' },
              { value: 'WAREHOUSE_OPERATOR', label: 'Warehouse operator' },
            ]}
          />
        </div>

        <Table<FulfillmentPlanSummary>
          rowKey="id"
          pagination={false}
          dataSource={visible}
          locale={{ emptyText: 'No fulfillment plans match the current view.' }}
          scroll={{ x: 900 }}
          columns={[
            {
              title: 'Plan',
              render: (_, plan) => (
                <Space orientation="vertical" size={0}>
                  <Link to={`/app/fulfillment/${plan.id}`}>{plan.number}</Link>
                  <Typography.Text type="secondary">{plan.orderNumber}</Typography.Text>
                </Space>
              ),
            },
            {
              title: 'Route',
              dataIndex: 'routeCode',
              render: (value: string) => value.replaceAll('_', ' '),
            },
            {
              title: 'Status',
              dataIndex: 'status',
              render: (value: FulfillmentStatus) => (
                <Tag color={statusColors[value]}>{value.replaceAll('_', ' ')}</Tag>
              ),
            },
            {
              title: 'Current work',
              dataIndex: 'currentStep',
              render: (value) => value ?? 'Complete',
            },
            {
              title: 'Due',
              render: (_, plan) => (
                <Typography.Text type={plan.overdue ? 'danger' : undefined}>
                  {plan.dueAt ? new Date(plan.dueAt).toLocaleString() : 'Not scheduled'}
                </Typography.Text>
              ),
            },
          ]}
        />
        {plans.data.pageInfo.hasNext && plans.data.pageInfo.nextCursor ? (
          <Button onClick={() => setCursor(plans.data.pageInfo.nextCursor ?? undefined)}>
            Load next page
          </Button>
        ) : null}
      </Space>
    </section>
  );
}
