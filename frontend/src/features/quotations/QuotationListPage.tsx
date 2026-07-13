import { useQuery } from '@tanstack/react-query';
import { Button, Checkbox, Select, Space, Table, Tag, Typography, type TableProps } from 'antd';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  listQuotations,
  QuotationApiError,
  type QuotationStatus,
  type QuotationSummary,
} from '../../api/quotations';
import { useCurrentUser } from '../../api/currentUser';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';

const statuses: QuotationStatus[] = [
  'DRAFT',
  'PENDING_APPROVAL',
  'CHANGES_REQUESTED',
  'APPROVED',
  'SENT',
  'REJECTED',
  'EXPIRED',
];

const statusColors: Record<string, string> = {
  DRAFT: 'default',
  PENDING_APPROVAL: 'processing',
  CHANGES_REQUESTED: 'warning',
  APPROVED: 'success',
  SENT: 'cyan',
  REJECTED: 'error',
  EXPIRED: 'error',
};

const columns: TableProps<QuotationSummary>['columns'] = [
  {
    title: 'Quotation',
    render: (_, quotation) => (
      <Space orientation="vertical" size={0}>
        <Link to={`/app/quotations/${quotation.id}`}>{quotation.number}</Link>
        <Typography.Text type="secondary">Revision {quotation.revision}</Typography.Text>
      </Space>
    ),
  },
  { title: 'Customer', dataIndex: 'partnerName' },
  {
    title: 'Status',
    dataIndex: 'status',
    render: (status: QuotationStatus) => (
      <Tag color={statusColors[status]}>{status.replaceAll('_', ' ')}</Tag>
    ),
  },
  {
    title: 'Total',
    dataIndex: 'total',
    render: (total: QuotationSummary['total']) => `${total.currency} ${total.amount}`,
  },
  {
    title: 'Route',
    dataIndex: 'selectedRouteCode',
    render: (route: string | null | undefined) => route?.replaceAll('_', ' ') ?? 'Not evaluated',
  },
  {
    title: 'Expires',
    dataIndex: 'expiresAt',
    render: (value: string) => new Date(value).toLocaleString(),
  },
];

export function QuotationListPage() {
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const currentUser = useCurrentUser(accessToken, session.sessionIdentity);
  const [selectedStatuses, setSelectedStatuses] = useState<QuotationStatus[]>([]);
  const [ownedByMe, setOwnedByMe] = useState(false);
  const quotations = useQuery({
    queryKey: ['quotations', 'list', selectedStatuses, ownedByMe, currentUser.data?.userId],
    queryFn: ({ signal }) =>
      listQuotations(
        accessToken,
        {
          status: selectedStatuses.length === 0 ? undefined : selectedStatuses,
          ownerId: ownedByMe ? currentUser.data?.userId : undefined,
          pageSize: 50,
        },
        signal,
      ),
    enabled: accessToken !== '' && (!ownedByMe || currentUser.data !== undefined),
  });

  if (quotations.isPending) return <LoadingState message="Loading quotations…" />;
  if (quotations.isError) {
    const problem = quotations.error instanceof QuotationApiError ? quotations.error : undefined;
    return (
      <ErrorState
        title={problem?.status === 403 ? 'Quotation access denied' : 'Quotations are unavailable'}
        description={
          problem === undefined
            ? 'The quotation list could not be loaded.'
            : `${problem.message} (${problem.code})`
        }
        actionLabel="Try again"
        onRetry={() => void quotations.refetch()}
      />
    );
  }

  return (
    <section className="quotation-page" aria-labelledby="quotation-list-title">
      <Space orientation="vertical" size="large" className="quotation-page-stack">
        <div className="quotation-page-heading">
          <div>
            <Typography.Title id="quotation-list-title" level={2}>
              Quotations
            </Typography.Title>
            <Typography.Paragraph type="secondary">
              Build revisioned commercial offers, explain route decisions, and manage approval
              before issue.
            </Typography.Paragraph>
          </div>
          {currentUser.data?.permissions.includes('quotation:create') && (
            <Link to="/app/quotations/new">
              <Button type="primary">Create quotation</Button>
            </Link>
          )}
        </div>
        <div className="quotation-filters" role="search" aria-label="Quotation filters">
          <Select
            aria-label="Filter by quotation status"
            mode="multiple"
            placeholder="All statuses"
            value={selectedStatuses}
            onChange={setSelectedStatuses}
            options={statuses.map((status) => ({
              value: status,
              label: status.replaceAll('_', ' '),
            }))}
          />
          <Checkbox checked={ownedByMe} onChange={(event) => setOwnedByMe(event.target.checked)}>
            Owned by me
          </Checkbox>
        </div>
        <Table
          rowKey="id"
          columns={columns}
          dataSource={quotations.data.items}
          pagination={false}
          locale={{ emptyText: 'No quotations match the current filters.' }}
        />
      </Space>
    </section>
  );
}
