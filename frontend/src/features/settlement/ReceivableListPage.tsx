import { useQuery } from '@tanstack/react-query';
import { Badge, Button, Card, Select, Space, Table, Tag, Typography } from 'antd';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  listReceivables,
  SettlementApiError,
  type ReceivableStatus,
  type ReceivableSummary,
} from '../../api/settlement';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';

const statuses: ReceivableStatus[] = ['OPEN', 'PARTIALLY_PAID', 'PAID', 'OVERDUE'];

function label(value: string) {
  return value.replaceAll('_', ' ');
}

function money(value: ReceivableSummary['originalAmount']) {
  if (!value) return 'Restricted';
  return `${value.currency} ${value.amount}`;
}

export function ReceivableListPage() {
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const [selectedStatuses, setSelectedStatuses] = useState<ReceivableStatus[]>([]);
  const [cursor, setCursor] = useState<string>();
  const receivables = useQuery({
    queryKey: ['settlement', 'receivables', selectedStatuses, cursor],
    queryFn: ({ signal }) =>
      listReceivables(
        accessToken,
        {
          pageSize: 50,
          cursor,
          status: selectedStatuses.length === 0 ? undefined : selectedStatuses,
        },
        signal,
      ),
    enabled: accessToken !== '',
  });

  if (receivables.isPending) return <LoadingState message="Loading receivables…" />;
  if (receivables.isError) {
    const problem = receivables.error instanceof SettlementApiError ? receivables.error : undefined;
    return (
      <ErrorState
        title={problem?.status === 403 ? 'Receivable access denied' : 'Receivables are unavailable'}
        description={
          problem ? `${problem.message} (${problem.code})` : 'The queue could not be loaded.'
        }
        actionLabel="Try again"
        onRetry={() => void receivables.refetch()}
      />
    );
  }

  const overdue = receivables.data.items.filter((item) => item.status === 'OVERDUE');
  const open = receivables.data.items.filter((item) => item.status !== 'PAID');
  return (
    <main className="settlement-page" aria-labelledby="receivable-list-title">
      <Space orientation="vertical" size="large" className="settlement-page-stack">
        <header className="settlement-heading">
          <div>
            <Typography.Text className="settlement-kicker">Order to cash</Typography.Text>
            <Typography.Title id="receivable-list-title" level={2}>
              Receivables
            </Typography.Title>
            <Typography.Text type="secondary">
              Track due balances and immutable payment evidence without connecting a payment
              gateway.
            </Typography.Text>
          </div>
          <Tag color="gold">Financial demonstration</Tag>
        </header>

        <div className="settlement-metrics">
          <Badge count={open.length} showZero color="#315c45" overflowCount={999}>
            <Card size="small" className="settlement-stat">
              Open balances
            </Card>
          </Badge>
          <Badge count={overdue.length} showZero color="#9d3c35" overflowCount={999}>
            <Card size="small" className="settlement-stat">
              Overdue
            </Card>
          </Badge>
        </div>

        <Card>
          <div className="settlement-filters" role="search" aria-label="Receivable filters">
            <Select
              aria-label="Filter by receivable status"
              mode="multiple"
              placeholder="All statuses"
              value={selectedStatuses}
              onChange={(value) => {
                setSelectedStatuses(value);
                setCursor(undefined);
              }}
              options={statuses.map((status) => ({ value: status, label: label(status) }))}
            />
          </div>
          <Table<ReceivableSummary>
            rowKey="id"
            pagination={false}
            dataSource={receivables.data.items}
            locale={{ emptyText: 'No receivables match the current view.' }}
            scroll={{ x: 960 }}
            columns={[
              {
                title: 'Receivable',
                render: (_, item) => (
                  <Space orientation="vertical" size={0}>
                    <Link to={`/app/receivables/${item.id}`}>{item.number}</Link>
                    <Typography.Text type="secondary">{item.orderNumber}</Typography.Text>
                  </Space>
                ),
              },
              {
                title: 'Customer',
                render: (_, item) => (
                  <Space orientation="vertical" size={0}>
                    <Typography.Text>{item.partnerName}</Typography.Text>
                    <Typography.Text type="secondary">{item.partnerNumber}</Typography.Text>
                  </Space>
                ),
              },
              { title: 'Original', render: (_, item) => money(item.originalAmount) },
              { title: 'Outstanding', render: (_, item) => money(item.outstandingAmount) },
              { title: 'Due', dataIndex: 'dueDate' },
              {
                title: 'Status',
                dataIndex: 'status',
                render: (value: ReceivableStatus) => (
                  <Tag
                    color={value === 'OVERDUE' ? 'error' : value === 'PAID' ? 'success' : 'blue'}
                  >
                    {label(value)}
                  </Tag>
                ),
              },
            ]}
          />
          {receivables.data.pageInfo.hasNext && receivables.data.pageInfo.nextCursor ? (
            <Button onClick={() => setCursor(receivables.data.pageInfo.nextCursor ?? undefined)}>
              Load next page
            </Button>
          ) : null}
        </Card>
      </Space>
    </main>
  );
}
