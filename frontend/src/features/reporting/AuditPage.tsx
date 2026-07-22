import { useQuery } from '@tanstack/react-query';
import { Button, Card, Input, Select, Space, Table, Tag, Typography } from 'antd';
import { useState } from 'react';
import {
  listAuditEntries,
  ReportingApiError,
  type AuditEntry,
  type AuditQuery,
} from '../../api/reporting';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';

function label(value: string) {
  return value.replaceAll('_', ' ');
}

export function AuditPage() {
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const [subjectType, setSubjectType] = useState<string>();
  const [action, setAction] = useState('');
  const [correlationId, setCorrelationId] = useState('');
  const [cursor, setCursor] = useState<string>();
  const [filters, setFilters] = useState<AuditQuery>({ pageSize: 50 });
  const audit = useQuery({
    queryKey: ['reporting', 'audit', filters, cursor],
    queryFn: ({ signal }) =>
      listAuditEntries(accessToken, { ...filters, cursor, pageSize: 50 }, signal),
    enabled: accessToken !== '',
  });

  if (audit.isPending) return <LoadingState message="Loading audit evidence…" />;
  if (audit.isError) {
    const problem = audit.error instanceof ReportingApiError ? audit.error : undefined;
    return (
      <ErrorState
        title={problem?.status === 403 ? 'Audit access denied' : 'Audit evidence is unavailable'}
        description={problem ? `${problem.message} (${problem.code})` : 'Try the request again.'}
        actionLabel="Try again"
        onRetry={() => void audit.refetch()}
      />
    );
  }

  return (
    <main className="reporting-page" aria-labelledby="audit-title">
      <Space orientation="vertical" size="large" className="reporting-page-stack">
        <header className="reporting-heading">
          <div>
            <Typography.Text className="reporting-kicker">Immutable evidence</Typography.Text>
            <Typography.Title id="audit-title" level={2}>
              Audit search
            </Typography.Title>
            <Typography.Text type="secondary">
              Authorized, tenant-scoped state transitions with correlation and causation evidence.
            </Typography.Text>
          </div>
        </header>
        <Card>
          <form
            className="reporting-filters"
            role="search"
            aria-label="Audit filters"
            onSubmit={(event) => {
              event.preventDefault();
              setCursor(undefined);
              setFilters({
                pageSize: 50,
                subjectType,
                action: action.trim() || undefined,
                correlationId: correlationId.trim() || undefined,
              });
            }}
          >
            <Select
              allowClear
              aria-label="Subject type"
              placeholder="All subjects"
              value={subjectType}
              onChange={setSubjectType}
              options={['PARTNER', 'QUOTATION', 'TRADE_ORDER', 'EXCEPTION_CASE', 'RECEIVABLE'].map(
                (value) => ({ value, label: label(value) }),
              )}
            />
            <Input
              aria-label="Action"
              placeholder="Action"
              value={action}
              onChange={(event) => setAction(event.target.value)}
            />
            <Input
              aria-label="Correlation ID"
              placeholder="Correlation ID"
              value={correlationId}
              onChange={(event) => setCorrelationId(event.target.value)}
            />
            <Button htmlType="submit" type="primary">
              Search
            </Button>
          </form>
        </Card>
        <Card>
          <Table<AuditEntry>
            rowKey="id"
            pagination={false}
            dataSource={audit.data.items}
            locale={{ emptyText: 'No authorized audit entries match these filters.' }}
            scroll={{ x: 1180 }}
            columns={[
              {
                title: 'Occurred',
                dataIndex: 'occurredAt',
                render: (value: string) => new Date(value).toLocaleString(),
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
              { title: 'Module', dataIndex: 'module' },
              { title: 'Action', dataIndex: 'action', render: (value: string) => label(value) },
              {
                title: 'Outcome',
                dataIndex: 'outcome',
                render: (value: string) => (
                  <Tag color={value === 'FAILED' ? 'error' : 'success'}>{value}</Tag>
                ),
              },
              {
                title: 'Actor',
                render: (_, item) => item.actorDisplay ?? item.actorId ?? item.actorType,
              },
              { title: 'State', render: (_, item) => item.newState ?? '—' },
              {
                title: 'Correlation',
                dataIndex: 'correlationId',
                render: (value: string) => <code>{value.slice(0, 8)}</code>,
              },
            ]}
          />
          {audit.data.pageInfo.hasNext && audit.data.pageInfo.nextCursor ? (
            <Button onClick={() => setCursor(audit.data.pageInfo.nextCursor ?? undefined)}>
              Load next page
            </Button>
          ) : null}
        </Card>
      </Space>
    </main>
  );
}
