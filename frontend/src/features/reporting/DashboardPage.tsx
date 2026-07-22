import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Card, Col, Row, Space, Statistic, Tag, Typography } from 'antd';
import { useMemo, useState } from 'react';
import { getDashboard, ReportingApiError, type Dashboard } from '../../api/reporting';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';
import { ReportingChart, type ChartDatum } from './ReportingChart';

function utcDate(offsetDays = 0) {
  const date = new Date();
  date.setUTCDate(date.getUTCDate() + offsetDays);
  return date.toISOString().slice(0, 10);
}

function numberMetric(dashboard: Dashboard, name: string) {
  const value = dashboard.metrics[name];
  return typeof value === 'number' ? value : Number(value ?? 0);
}

function chartData(dashboard: Dashboard, name: string): ChartDatum[] {
  return (dashboard.charts[name] ?? []).flatMap((row) => {
    const label = row.label;
    const value = row.value;
    return typeof label === 'string' && typeof value === 'number' ? [{ label, value }] : [];
  });
}

export function DashboardPage() {
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const [draftFrom, setDraftFrom] = useState(() => utcDate(-29));
  const [draftTo, setDraftTo] = useState(() => utcDate());
  const [range, setRange] = useState({ from: draftFrom, to: draftTo });
  const dashboard = useQuery({
    queryKey: ['reporting', 'dashboard', range],
    queryFn: ({ signal }) => getDashboard(accessToken, range.from, range.to, signal),
    enabled: accessToken !== '',
  });
  const statusColor = useMemo(
    () =>
      dashboard.data?.projectionStatus === 'CURRENT'
        ? 'success'
        : dashboard.data?.projectionStatus === 'EMPTY'
          ? 'default'
          : 'warning',
    [dashboard.data?.projectionStatus],
  );

  if (dashboard.isPending) return <LoadingState message="Loading operational dashboard…" />;
  if (dashboard.isError) {
    const problem = dashboard.error instanceof ReportingApiError ? dashboard.error : undefined;
    return (
      <ErrorState
        title={problem?.status === 403 ? 'Dashboard access denied' : 'Dashboard is unavailable'}
        description={problem ? `${problem.message} (${problem.code})` : 'Try the request again.'}
        actionLabel="Try again"
        onRetry={() => void dashboard.refetch()}
      />
    );
  }

  const data = dashboard.data;
  const empty = data.projectionStatus === 'EMPTY';
  return (
    <main className="reporting-page" aria-labelledby="dashboard-title">
      <Space orientation="vertical" size="large" className="reporting-page-stack">
        <header className="reporting-heading">
          <div>
            <Typography.Text className="reporting-kicker">Operational pulse</Typography.Text>
            <Typography.Title id="dashboard-title" level={2}>
              Business dashboard
            </Typography.Title>
            <Typography.Text type="secondary">
              Event-projected commercial, fulfillment and settlement signals.
            </Typography.Text>
          </div>
          <Space orientation="vertical" align="end" size={2}>
            <Tag color={statusColor}>{data.projectionStatus}</Tag>
            <Typography.Text type="secondary">
              Data as of{' '}
              {data.dataAsOf ? new Date(data.dataAsOf).toLocaleString() : 'not available'}
            </Typography.Text>
          </Space>
        </header>

        {data.projectionStatus === 'STALE' || data.projectionStatus === 'REBUILDING' ? (
          <Alert
            showIcon
            type="warning"
            message="Projection is catching up"
            description={`The current view trails source events by ${data.projectionLagSeconds} seconds.`}
          />
        ) : null}
        {empty ? (
          <Alert
            showIcon
            type="info"
            message="No projected events yet"
            description="Complete a business workflow or adjust the UTC date range."
          />
        ) : null}

        <Card>
          <form
            className="reporting-filters"
            aria-label="Dashboard date filters"
            onSubmit={(event) => {
              event.preventDefault();
              setRange({ from: draftFrom, to: draftTo });
            }}
          >
            <label>
              From (UTC)
              <input
                type="date"
                value={draftFrom}
                onChange={(event) => setDraftFrom(event.target.value)}
              />
            </label>
            <label>
              To (UTC)
              <input
                type="date"
                value={draftTo}
                onChange={(event) => setDraftTo(event.target.value)}
              />
            </label>
            <Button htmlType="submit" type="primary">
              Apply range
            </Button>
          </form>
        </Card>

        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} xl={6}>
            <Card>
              <Statistic title="Quotations" value={numberMetric(data, 'quotationCount')} />
            </Card>
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <Card>
              <Statistic
                title="Average quote cycle"
                value={numberMetric(data, 'quotationCycleSeconds') / 60}
                suffix="min"
                precision={1}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <Card>
              <Statistic title="Approval backlog" value={numberMetric(data, 'approvalBacklog')} />
            </Card>
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <Card>
              <Statistic
                title="Quote-to-order"
                value={numberMetric(data, 'quoteToOrderConversion') * 100}
                suffix="%"
                precision={1}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <Card>
              <Statistic title="Idempotency hits" value={numberMetric(data, 'idempotencyHits')} />
            </Card>
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <Card>
              <Statistic
                title="Reservation events"
                value={numberMetric(data, 'reservationEvents')}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <Card>
              <Statistic title="Open exceptions" value={numberMetric(data, 'openExceptions')} />
            </Card>
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <Card>
              <Statistic
                title="Overdue exceptions"
                value={numberMetric(data, 'overdueExceptions')}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <Card>
              <Statistic title="Overdue work" value={numberMetric(data, 'overdueWorkItems')} />
            </Card>
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <Card>
              <Statistic title="Receivable events" value={numberMetric(data, 'receivableEvents')} />
            </Card>
          </Col>
        </Row>

        <Row gutter={[16, 16]}>
          <Col xs={24} xl={12}>
            <Card title="Route distribution">
              <ReportingChart
                title="Route distribution"
                data={chartData(data, 'routeDistribution')}
              />
            </Card>
          </Col>
          <Col xs={24} xl={12}>
            <Card title="Reservation outcomes">
              <ReportingChart
                title="Reservation outcomes"
                data={chartData(data, 'reservationResults')}
                kind="pie"
              />
            </Card>
          </Col>
          <Col xs={24} xl={12}>
            <Card title="Fulfillment SLA signals">
              <ReportingChart
                title="Fulfillment SLA signals"
                data={chartData(data, 'fulfillmentSla')}
              />
            </Card>
          </Col>
          <Col xs={24} xl={12}>
            <Card title="Exception status">
              <ReportingChart title="Exception status" data={chartData(data, 'exceptionStatus')} />
            </Card>
          </Col>
          <Col xs={24} xl={12}>
            <Card title="Receivable status">
              <ReportingChart
                title="Receivable status"
                data={chartData(data, 'receivableStatus')}
                kind="pie"
              />
            </Card>
          </Col>
        </Row>
      </Space>
    </main>
  );
}
