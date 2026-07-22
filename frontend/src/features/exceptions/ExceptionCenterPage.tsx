import { useQuery } from '@tanstack/react-query';
import { Badge, Button, Card, Checkbox, Select, Space, Table, Tabs, Tag, Typography } from 'antd';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  ExceptionApiError,
  listExceptions,
  listFailedEventPublications,
  type ExceptionSeverity,
  type ExceptionStatus,
  type ExceptionSummary,
} from '../../api/exceptions';
import { useCurrentUser } from '../../api/currentUser';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';

const statuses: ExceptionStatus[] = [
  'OPEN',
  'ASSIGNED',
  'ACKNOWLEDGED',
  'IN_PROGRESS',
  'RECOVERY_PENDING',
  'RESOLVED',
  'CLOSED',
];
const severities: ExceptionSeverity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
const severityColors: Record<ExceptionSeverity, string> = {
  CRITICAL: 'magenta',
  HIGH: 'error',
  MEDIUM: 'warning',
  LOW: 'default',
};

function label(value: string) {
  return value.replaceAll('_', ' ');
}

export function ExceptionCenterPage() {
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const currentUser = useCurrentUser(session.accessToken, session.sessionIdentity);
  const [activeTab, setActiveTab] = useState('cases');
  const [selectedStatuses, setSelectedStatuses] = useState<ExceptionStatus[]>([]);
  const [severity, setSeverity] = useState<ExceptionSeverity>();
  const [sourceType, setSourceType] = useState<string>();
  const [mineOnly, setMineOnly] = useState(false);
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [cursor, setCursor] = useState<string>();
  const cases = useQuery({
    queryKey: [
      'exceptions',
      'cases',
      selectedStatuses,
      severity,
      sourceType,
      mineOnly,
      currentUser.data?.userId,
      overdueOnly,
      cursor,
    ],
    queryFn: ({ signal }) =>
      listExceptions(
        accessToken,
        {
          pageSize: 50,
          cursor,
          status: selectedStatuses.length === 0 ? undefined : selectedStatuses,
          severity,
          sourceType,
          assigneeId: mineOnly ? currentUser.data?.userId : undefined,
          overdue: overdueOnly ? true : undefined,
        },
        signal,
      ),
    enabled: accessToken !== '',
  });
  const canReadPublications =
    currentUser.data?.permissions.includes('event-publication:read') ?? false;
  const failedDeliveries = useQuery({
    queryKey: ['exceptions', 'failed-publications'],
    queryFn: ({ signal }) => listFailedEventPublications(accessToken, 50, undefined, signal),
    enabled: accessToken !== '' && canReadPublications && activeTab === 'publications',
  });

  if (cases.isPending) return <LoadingState message="Loading exception queue…" />;
  if (cases.isError) {
    const problem = cases.error instanceof ExceptionApiError ? cases.error : undefined;
    return (
      <ErrorState
        title={
          problem?.status === 403 ? 'Exception access denied' : 'Exception Center is unavailable'
        }
        description={
          problem ? `${problem.message} (${problem.code})` : 'The queue could not be loaded.'
        }
        actionLabel="Try again"
        onRetry={() => void cases.refetch()}
      />
    );
  }

  const openCases = cases.data.items.filter((item) => item.status !== 'CLOSED');
  const criticalCases = openCases.filter((item) => item.severity === 'CRITICAL');
  const caseWorkspace = (
    <Space orientation="vertical" size="large" className="exception-page-stack">
      <div className="exception-metrics">
        <Badge count={openCases.length} showZero color="#315c45" overflowCount={999}>
          <Card size="small" className="exception-stat">
            Active cases
          </Card>
        </Badge>
        <Badge count={criticalCases.length} showZero color="#9d3c35" overflowCount={999}>
          <Card size="small" className="exception-stat">
            Critical
          </Card>
        </Badge>
      </div>
      <div className="exception-filters" role="search" aria-label="Exception filters">
        <Select
          aria-label="Filter by exception status"
          mode="multiple"
          placeholder="All statuses"
          value={selectedStatuses}
          onChange={(value) => {
            setSelectedStatuses(value);
            setCursor(undefined);
          }}
          options={statuses.map((status) => ({ value: status, label: label(status) }))}
        />
        <Select
          aria-label="Filter by exception severity"
          allowClear
          placeholder="All severities"
          value={severity}
          onChange={(value) => {
            setSeverity(value);
            setCursor(undefined);
          }}
          options={severities.map((item) => ({ value: item, label: item }))}
        />
        <Select
          aria-label="Filter by source type"
          allowClear
          placeholder="All sources"
          value={sourceType}
          onChange={(value) => {
            setSourceType(value);
            setCursor(undefined);
          }}
          options={[
            { value: 'INVENTORY_RESERVATION', label: 'Inventory Reservation' },
            { value: 'FULFILLMENT_STEP', label: 'Fulfillment step' },
            { value: 'EVENT_PUBLICATION', label: 'Event publication' },
          ]}
        />
        <Checkbox
          checked={mineOnly}
          disabled={!currentUser.data?.userId}
          onChange={(event) => {
            setMineOnly(event.target.checked);
            setCursor(undefined);
          }}
        >
          Assigned to me
        </Checkbox>
        <Checkbox
          checked={overdueOnly}
          onChange={(event) => {
            setOverdueOnly(event.target.checked);
            setCursor(undefined);
          }}
        >
          Overdue only
        </Checkbox>
      </div>
      <Table<ExceptionSummary>
        rowKey="id"
        pagination={false}
        dataSource={cases.data.items}
        locale={{ emptyText: 'No exception cases match the current view.' }}
        scroll={{ x: 980 }}
        columns={[
          {
            title: 'Case',
            render: (_, item) => (
              <Space orientation="vertical" size={0}>
                <Link to={`/app/exceptions/${item.id}`}>{item.number}</Link>
                <Typography.Text type="secondary">{label(item.category)}</Typography.Text>
              </Space>
            ),
          },
          {
            title: 'Severity',
            dataIndex: 'severity',
            render: (value: ExceptionSeverity) => <Tag color={severityColors[value]}>{value}</Tag>,
          },
          { title: 'Status', dataIndex: 'status', render: (value: string) => label(value) },
          {
            title: 'Source',
            render: (_, item) => (
              <Space orientation="vertical" size={0}>
                <Typography.Text>{item.sourceNumber}</Typography.Text>
                <Typography.Text type="secondary">{label(item.sourceType)}</Typography.Text>
              </Space>
            ),
          },
          { title: 'Summary', dataIndex: 'summary' },
          {
            title: 'Due',
            render: (_, item) => (item.dueAt ? new Date(item.dueAt).toLocaleString() : 'Not set'),
          },
        ]}
      />
      {cases.data.hasNext && cases.data.nextCursor ? (
        <Button onClick={() => setCursor(cases.data.nextCursor ?? undefined)}>
          Load next page
        </Button>
      ) : null}
    </Space>
  );

  const publicationWorkspace = failedDeliveries.isPending ? (
    <LoadingState message="Loading failed event deliveries…" />
  ) : failedDeliveries.isError ? (
    <ErrorState
      title="Failed deliveries are unavailable"
      description="The masked technical queue could not be loaded."
      actionLabel="Try again"
      onRetry={() => void failedDeliveries.refetch()}
    />
  ) : (
    <Table
      rowKey={(item) => `${item.eventId}:${item.consumerName}`}
      pagination={false}
      dataSource={failedDeliveries.data?.items ?? []}
      locale={{ emptyText: 'No failed event deliveries require operator review.' }}
      columns={[
        { title: 'Event type', dataIndex: 'eventType' },
        {
          title: 'Event ID',
          dataIndex: 'eventId',
          render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
        },
        { title: 'Consumer', dataIndex: 'consumerName' },
        { title: 'Attempts', dataIndex: 'attempts' },
        { title: 'Error', dataIndex: 'errorCode' },
        {
          title: 'Next retry',
          dataIndex: 'nextRetryAt',
          render: (value?: string | null) =>
            value ? new Date(value).toLocaleString() : 'Operator review',
        },
      ]}
    />
  );

  return (
    <section className="exception-page" aria-labelledby="exception-center-title">
      <header className="exception-heading">
        <div>
          <Typography.Title id="exception-center-title" level={2}>
            Exception Center
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            Review deduplicated operational failures, evidence and verified recovery work.
          </Typography.Paragraph>
        </div>
      </header>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          { key: 'cases', label: 'Case queue', children: caseWorkspace },
          ...(canReadPublications
            ? [
                {
                  key: 'publications',
                  label: 'Failed publications',
                  children: publicationWorkspace,
                },
              ]
            : []),
        ]}
      />
    </section>
  );
}
