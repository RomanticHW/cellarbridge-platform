import { useQuery } from '@tanstack/react-query';
import { Card, Col, Descriptions, Flex, Row, Space, Tag, Typography } from 'antd';
import { getBackendReadiness } from '../../api/health';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { plannedModules } from './plannedModules';

export function SystemStatusPage() {
  const readiness = useQuery({
    queryKey: ['foundation', 'backend-readiness'],
    queryFn: ({ signal }) => getBackendReadiness(signal),
    refetchInterval: 30_000,
  });

  return (
    <section aria-labelledby="system-status-title">
      <Flex vertical gap="large">
        <div>
          <Typography.Title id="system-status-title" level={1}>
            System status
          </Typography.Title>
          <Typography.Paragraph type="secondary" className="page-introduction">
            Identity, tenant resolution, and permission-aware access are available. Remaining
            business workflows stay planned until their complete vertical slices are verified.
          </Typography.Paragraph>
        </div>

        <Row gutter={[16, 16]}>
          <Col xs={24} xl={12}>
            <Card title="Design baseline" className="status-card">
              <Descriptions column={1} size="small">
                <Descriptions.Item label="Architecture">
                  Domain-oriented modular monolith
                </Descriptions.Item>
                <Descriptions.Item label="Backend">Java 21 · Spring Boot 4.1</Descriptions.Item>
                <Descriptions.Item label="Frontend">React 19.2 · TypeScript</Descriptions.Item>
                <Descriptions.Item label="Delivery stage">
                  <Tag color="green">Identity access available</Tag>
                </Descriptions.Item>
              </Descriptions>
            </Card>
          </Col>
          <Col xs={24} xl={12}>
            <Card title="Backend readiness" className="status-card">
              {readiness.isPending && <LoadingState />}
              {readiness.isError && <ErrorState onRetry={() => void readiness.refetch()} />}
              {readiness.isSuccess && (
                <Space orientation="vertical">
                  <Tag
                    className={
                      readiness.data.status === 'UP' ? 'health-status-up' : 'health-status-warning'
                    }
                  >
                    {readiness.data.status}
                  </Tag>
                  <Typography.Text type="secondary">
                    Reported by the backend readiness health group.
                  </Typography.Text>
                </Space>
              )}
            </Card>
          </Col>
        </Row>

        <Card title="Business modules">
          <Typography.Paragraph type="secondary">
            Identity and access is available from the navigation. Each module below remains planned
            until its complete vertical slice is implemented and verified.
          </Typography.Paragraph>
          <div className="module-grid" aria-label="Planned business modules">
            {plannedModules.map((module) => (
              <div className="module-item" key={module.key}>
                <Typography.Text>{module.label}</Typography.Text>
                <Tag>Planned</Tag>
              </div>
            ))}
          </div>
        </Card>
      </Flex>
    </section>
  );
}
