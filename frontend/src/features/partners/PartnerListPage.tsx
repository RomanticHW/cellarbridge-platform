import { useQuery } from '@tanstack/react-query';
import {
  Button,
  Checkbox,
  DatePicker,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  type TableProps,
} from 'antd';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  listPartners,
  PartnerApiError,
  type PartnerStatus,
  type PartnerSummary,
} from '../../api/partners';
import { useCurrentUser } from '../../api/currentUser';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';
import { routeCodes } from './partnerForm';

const statuses: PartnerStatus[] = [
  'DRAFT',
  'PENDING_REVIEW',
  'CHANGES_REQUESTED',
  'ACTIVE',
  'SUSPENDED',
  'REJECTED',
];

const statusColors: Record<PartnerStatus, string> = {
  DRAFT: 'default',
  PENDING_REVIEW: 'processing',
  CHANGES_REQUESTED: 'warning',
  ACTIVE: 'success',
  SUSPENDED: 'error',
  REJECTED: 'error',
};

const columns: TableProps<PartnerSummary>['columns'] = [
  {
    title: 'Partner',
    dataIndex: 'displayName',
    render: (_, partner) => (
      <Space orientation="vertical" size={0}>
        <Link to={`/app/partners/${partner.id}`}>{partner.displayName ?? partner.number}</Link>
        <Typography.Text type="secondary">{partner.number}</Typography.Text>
      </Space>
    ),
  },
  {
    title: 'Legal name',
    dataIndex: 'legalName',
    render: (value: string | null) => value ?? '—',
  },
  {
    title: 'Status',
    dataIndex: 'status',
    render: (status: PartnerStatus) => (
      <Tag color={statusColors[status]}>{status.replaceAll('_', ' ')}</Tag>
    ),
  },
  {
    title: 'Routes',
    dataIndex: 'routeEligibility',
    render: (routes: string[]) => routes.join(', ') || '—',
  },
  {
    title: 'Currency',
    dataIndex: 'defaultCurrency',
    render: (value: string | null) => value ?? '—',
  },
  {
    title: 'Updated',
    dataIndex: 'updatedAt',
    render: (value: string) => new Date(value).toLocaleString(),
  },
];

export function PartnerListPage() {
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const currentUser = useCurrentUser(accessToken, session.sessionIdentity);
  const [keywordDraft, setKeywordDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [selectedStatuses, setSelectedStatuses] = useState<PartnerStatus[]>([]);
  const [selectedRoute, setSelectedRoute] = useState<(typeof routeCodes)[number]>();
  const [ownedByMe, setOwnedByMe] = useState(false);
  const [updatedRange, setUpdatedRange] = useState<{
    from?: string;
    to?: string;
  }>({});
  const [cursor, setCursor] = useState<string | undefined>();
  const [cursorHistory, setCursorHistory] = useState<Array<string | undefined>>([]);
  const partners = useQuery({
    queryKey: [
      'partners',
      'list',
      keyword,
      selectedStatuses,
      selectedRoute,
      ownedByMe,
      updatedRange,
      cursor,
    ],
    queryFn: ({ signal }) =>
      listPartners(
        accessToken,
        {
          keyword: keyword || undefined,
          status: selectedStatuses.length === 0 ? undefined : selectedStatuses,
          routeCode: selectedRoute,
          ownerId: ownedByMe ? currentUser.data?.userId : undefined,
          updatedFrom: updatedRange.from,
          updatedTo: updatedRange.to,
          cursor,
          pageSize: 25,
        },
        signal,
      ),
    enabled: accessToken !== '' && (!ownedByMe || currentUser.data !== undefined),
  });

  const resetCursor = () => {
    setCursor(undefined);
    setCursorHistory([]);
  };
  const applyKeyword = () => {
    setKeyword(keywordDraft.trim());
    resetCursor();
  };
  const nextPage = () => {
    const next = partners.data?.pageInfo.nextCursor;
    if (next === null || next === undefined) return;
    setCursorHistory((history) => [...history, cursor]);
    setCursor(next);
  };
  const previousPage = () => {
    const previous = cursorHistory.at(-1);
    setCursor(previous);
    setCursorHistory((history) => history.slice(0, -1));
  };

  if (partners.isPending) return <LoadingState message="Loading partners…" />;
  if (partners.isError) {
    const problem = partners.error instanceof PartnerApiError ? partners.error : undefined;
    return (
      <ErrorState
        title={problem?.status === 403 ? 'Partner access denied' : 'Partners are unavailable'}
        description={
          problem === undefined
            ? 'The partner list could not be loaded for the current tenant.'
            : `${problem.message} (${problem.code})`
        }
        actionLabel="Try again"
        onRetry={() => void partners.refetch()}
      />
    );
  }

  return (
    <section className="partner-page" aria-labelledby="partner-list-title">
      <Space orientation="vertical" size="large" className="partner-page-stack">
        <div className="partner-page-heading">
          <div>
            <Typography.Title id="partner-list-title" level={2}>
              Partners
            </Typography.Title>
            <Typography.Paragraph type="secondary">
              Onboard, review, activate, and maintain tenant-scoped trading partners.
            </Typography.Paragraph>
          </div>
          {currentUser.data?.permissions.includes('partner:create') && (
            <Link to="/app/partners/new">
              <Button type="primary">Create partner</Button>
            </Link>
          )}
        </div>

        <div className="partner-filters" role="search" aria-label="Partner filters">
          <Input.Search
            aria-label="Search partners"
            placeholder="Search name or partner number"
            value={keywordDraft}
            onChange={(event) => setKeywordDraft(event.target.value)}
            onSearch={applyKeyword}
            allowClear
          />
          <Select
            aria-label="Filter by partner status"
            mode="multiple"
            placeholder="All statuses"
            value={selectedStatuses}
            onChange={(value) => {
              setSelectedStatuses(value);
              resetCursor();
            }}
            options={statuses.map((status) => ({
              value: status,
              label: status.replaceAll('_', ' '),
            }))}
          />
          <Select
            aria-label="Filter by approved trade route"
            placeholder="All approved routes"
            value={selectedRoute}
            allowClear
            onChange={(value) => {
              setSelectedRoute(value);
              resetCursor();
            }}
            options={routeCodes.map((route) => ({
              value: route,
              label: route.replaceAll('_', ' '),
            }))}
          />
          <DatePicker.RangePicker
            showTime
            allowClear
            aria-label="Filter by updated date range"
            onChange={(dates) => {
              setUpdatedRange({
                from: dates?.[0]?.toISOString(),
                to: dates?.[1]?.toISOString(),
              });
              resetCursor();
            }}
          />
          <Checkbox
            checked={ownedByMe}
            onChange={(event) => {
              setOwnedByMe(event.target.checked);
              resetCursor();
            }}
          >
            Owned by me
          </Checkbox>
        </div>

        <Table<PartnerSummary>
          rowKey="id"
          columns={columns}
          dataSource={partners.data.items}
          pagination={false}
          scroll={{ x: 900 }}
          locale={{ emptyText: 'No partners match the current filters.' }}
        />
        <Space>
          <Button disabled={cursorHistory.length === 0} onClick={previousPage}>
            Previous
          </Button>
          <Button disabled={!partners.data.pageInfo.hasNext} onClick={nextPage}>
            Next
          </Button>
        </Space>
      </Space>
    </section>
  );
}
