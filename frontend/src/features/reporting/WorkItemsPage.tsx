import { useQuery } from '@tanstack/react-query';
import { Card, Input, Segmented, Select, Space, Table, Tag, Typography } from 'antd';
import { useState } from 'react';
import {
  listWorkItems,
  ReportingApiError,
  type WorkItem,
  type WorkItemQuery,
} from '../../api/reporting';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';

const statuses: NonNullable<WorkItemQuery['status']> = [
  'OPEN',
  'CLAIMED',
  'COMPLETED',
  'CANCELLED',
];
const priorities: NonNullable<WorkItemQuery['priority']> = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

function label(value: string) {
  return value.replaceAll('_', ' ');
}

export function WorkItemsPage() {
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const [scope, setScope] = useState<'personal' | 'team'>('personal');
  const [selectedStatuses, setSelectedStatuses] = useState<WorkItemQuery['status']>([
    'OPEN',
    'CLAIMED',
  ]);
  const [selectedPriorities, setSelectedPriorities] = useState<WorkItemQuery['priority']>([]);
  const [subjectNumber, setSubjectNumber] = useState('');
  const work = useQuery({
    queryKey: [
      'reporting',
      'work-items',
      scope,
      selectedStatuses,
      selectedPriorities,
      subjectNumber,
    ],
    queryFn: ({ signal }) =>
      listWorkItems(
        accessToken,
        {
          scope,
          status: selectedStatuses?.length ? selectedStatuses : undefined,
          priority: selectedPriorities?.length ? selectedPriorities : undefined,
          subjectNumber: subjectNumber.trim() || undefined,
          pageSize: 100,
        },
        signal,
      ),
    enabled: accessToken !== '',
  });

  if (work.isPending) return <LoadingState message="Loading work queue…" />;
  if (work.isError) {
    const problem = work.error instanceof ReportingApiError ? work.error : undefined;
    return (
      <ErrorState
        title={problem?.status === 403 ? 'Team queue access denied' : 'Work queue is unavailable'}
        description={problem ? `${problem.message} (${problem.code})` : 'Try the request again.'}
        actionLabel="Try again"
        onRetry={() => void work.refetch()}
      />
    );
  }

  return (
    <main className="reporting-page" aria-labelledby="work-items-title">
      <Space orientation="vertical" size="large" className="reporting-page-stack">
        <header className="reporting-heading">
          <div>
            <Typography.Text className="reporting-kicker">Actionable projection</Typography.Text>
            <Typography.Title id="work-items-title" level={2}>
              Work queue
            </Typography.Title>
            <Typography.Text type="secondary">
              Deduplicated review, fulfillment, exception and finance actions.
            </Typography.Text>
          </div>
          <Segmented
            aria-label="Work queue scope"
            value={scope}
            onChange={(value) => setScope(value as 'personal' | 'team')}
            options={[
              { value: 'personal', label: 'My work' },
              { value: 'team', label: 'Team work' },
            ]}
          />
        </header>
        <Card>
          <div className="reporting-filters" role="search" aria-label="Work item filters">
            <Select
              mode="multiple"
              aria-label="Work item status"
              placeholder="All statuses"
              value={selectedStatuses}
              onChange={setSelectedStatuses}
              options={statuses.map((value) => ({ value, label: label(value) }))}
            />
            <Select
              mode="multiple"
              aria-label="Work item priority"
              placeholder="All priorities"
              value={selectedPriorities}
              onChange={setSelectedPriorities}
              options={priorities.map((value) => ({ value, label: value }))}
            />
            <Input
              allowClear
              aria-label="Subject number"
              placeholder="Subject number"
              value={subjectNumber}
              onChange={(event) => setSubjectNumber(event.target.value)}
            />
          </div>
        </Card>
        <Card>
          <Table<WorkItem>
            rowKey="id"
            pagination={false}
            dataSource={work.data.items}
            locale={{ emptyText: 'No work items match this authorized view.' }}
            scroll={{ x: 980 }}
            columns={[
              {
                title: 'Work item',
                render: (_, item) => (
                  <Space orientation="vertical" size={0}>
                    <Typography.Text strong>{item.title}</Typography.Text>
                    <Typography.Text type="secondary">
                      {item.safeSummary ?? label(item.type)}
                    </Typography.Text>
                  </Space>
                ),
              },
              {
                title: 'Subject',
                render: (_, item) => (
                  <Space orientation="vertical" size={0}>
                    <Typography.Text>{item.subjectNumber}</Typography.Text>
                    <Typography.Text type="secondary">{label(item.subjectType)}</Typography.Text>
                  </Space>
                ),
              },
              {
                title: 'Priority',
                dataIndex: 'priority',
                render: (value: string) => (
                  <Tag
                    color={value === 'CRITICAL' ? 'error' : value === 'HIGH' ? 'warning' : 'blue'}
                  >
                    {value}
                  </Tag>
                ),
              },
              {
                title: 'Status',
                dataIndex: 'status',
                render: (value: string) => <Tag>{label(value)}</Tag>,
              },
              {
                title: 'Candidate role',
                dataIndex: 'candidateRole',
                render: (value: string) => label(value),
              },
              {
                title: 'Due',
                dataIndex: 'dueAt',
                render: (value?: string | null) =>
                  value ? new Date(value).toLocaleString() : 'No due date',
              },
            ]}
          />
        </Card>
      </Space>
    </main>
  );
}
