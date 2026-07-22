import { useQuery } from '@tanstack/react-query';
import { Alert, Card, Empty, Space, Spin, Tag, Timeline, Typography } from 'antd';
import { getTimeline, ReportingApiError } from '../../api/reporting';
import { useAuthSession } from '../identity-access/authSession';

interface TimelinePanelProps {
  subjectType: 'PARTNER' | 'QUOTATION' | 'TRADE_ORDER' | 'ORDER';
  subjectId: string;
}

function label(value: string) {
  return value.replaceAll('.', ' · ').replaceAll('-', ' ');
}

export function TimelinePanel({ subjectType, subjectId }: TimelinePanelProps) {
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const timeline = useQuery({
    queryKey: ['reporting', 'timeline', subjectType, subjectId],
    queryFn: ({ signal }) => getTimeline(accessToken, subjectType, subjectId, signal),
    enabled: accessToken !== '' && subjectId !== '',
  });
  const items = Array.isArray(timeline.data?.items) ? timeline.data.items : [];

  return (
    <Card
      title="Unified business timeline"
      extra={
        timeline.data ? (
          <Tag color={timeline.data.projectionStatus === 'CURRENT' ? 'success' : 'warning'}>
            {timeline.data.projectionStatus}
          </Tag>
        ) : null
      }
    >
      {timeline.isPending ? <Spin aria-label="Loading unified timeline" /> : null}
      {timeline.isError ? (
        <Alert
          showIcon
          type="warning"
          message={
            timeline.error instanceof ReportingApiError && timeline.error.status === 403
              ? 'Timeline access is restricted'
              : 'Timeline is temporarily unavailable'
          }
        />
      ) : null}
      {timeline.data && items.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No projected events yet." />
      ) : null}
      {items.length > 0 ? (
        <Timeline
          items={items.map((item) => ({
            children: (
              <Space orientation="vertical" size={2}>
                <Typography.Text strong>{item.safeSummary}</Typography.Text>
                <Typography.Text type="secondary">
                  {new Date(item.occurredAt).toLocaleString()} · {item.actorType} ·{' '}
                  {item.sourceModule}
                </Typography.Text>
                <Typography.Text type="secondary">
                  {label(item.eventType)} · correlation {item.correlationId.slice(0, 8)}
                </Typography.Text>
              </Space>
            ),
          }))}
        />
      ) : null}
    </Card>
  );
}
