import { useQuery } from '@tanstack/react-query';
import { Alert, Card, Descriptions, Result, Space, Table, Typography, type TableProps } from 'antd';
import { useParams } from 'react-router-dom';
import { getPublicQuotation, QuotationApiError, type PublicQuotation } from '../../api/quotations';
import { LoadingState } from '../../components/LoadingState';

const columns: TableProps<PublicQuotation['lines'][number]>['columns'] = [
  {
    title: 'Wine',
    render: (_, line) => (
      <Space orientation="vertical" size={0}>
        <Typography.Text strong>{line.description}</Typography.Text>
        <Typography.Text type="secondary">
          {[line.vintage, line.package].filter(Boolean).join(' · ')}
        </Typography.Text>
      </Space>
    ),
  },
  {
    title: 'Quantity',
    render: (_, line) => `${line.quantity.value} ${line.quantity.unit.toLowerCase()}`,
  },
  {
    title: 'Unit price',
    render: (_, line) => `${line.unitPrice.currency} ${line.unitPrice.amount}`,
  },
  {
    title: 'Line total',
    render: (_, line) => `${line.lineTotal.currency} ${line.lineTotal.amount}`,
  },
];

export function PublicQuotationPage() {
  const { publicToken = '' } = useParams();
  const quotation = useQuery({
    queryKey: ['public-quotation', publicToken],
    queryFn: ({ signal }) => getPublicQuotation(publicToken, signal),
    enabled: publicToken !== '',
    retry: false,
  });
  if (quotation.isPending)
    return (
      <main className="public-quotation-page">
        <LoadingState message="Loading quotation…" />
      </main>
    );
  if (quotation.isError) {
    const problem = quotation.error instanceof QuotationApiError ? quotation.error : undefined;
    return (
      <main className="public-quotation-page">
        <Result
          status="404"
          title="Quotation unavailable"
          subTitle={
            problem?.status === 404
              ? 'This secure quotation link is invalid or no longer available.'
              : 'The quotation could not be loaded. Please try again later.'
          }
        />
      </main>
    );
  }
  const detail = quotation.data;
  return (
    <main className="public-quotation-page">
      <Space orientation="vertical" size="large" className="public-quotation-stack">
        <header className="public-quotation-header">
          <Typography.Text className="auth-brand">CellarBridge</Typography.Text>
          <Typography.Title level={1}>Quotation {detail.number}</Typography.Title>
          <Typography.Text type="secondary">
            Revision {detail.revision} · prepared for {detail.customerDisplayName}
          </Typography.Text>
        </header>
        <Alert
          type="info"
          showIcon
          title={`Valid until ${new Date(detail.expiresAt).toLocaleString()}`}
          description="This preview contains customer-safe commercial terms only. Contact your account representative to request changes."
        />
        <Card title="Commercial offer">
          <Table
            rowKey={(line) => `${line.description}-${line.quantity.value}`}
            columns={columns}
            dataSource={detail.lines}
            pagination={false}
            scroll={{ x: 620 }}
          />
          <div className="public-quotation-total">
            <Typography.Title level={3}>
              Total: {detail.total.currency} {detail.total.amount}
            </Typography.Title>
          </div>
        </Card>
        <Card title="Delivery and payment">
          <Descriptions column={{ xs: 1, md: 2 }}>
            <Descriptions.Item label="Delivery option">
              {detail.deliveryOption.label}
            </Descriptions.Item>
            <Descriptions.Item label="Estimated window">
              {detail.deliveryOption.estimatedWindow}
            </Descriptions.Item>
            <Descriptions.Item label="Payment terms">
              {detail.paymentTermDays} days
            </Descriptions.Item>
            <Descriptions.Item label="Terms version">{detail.termsVersion}</Descriptions.Item>
          </Descriptions>
        </Card>
        <Typography.Text type="secondary">
          Issued by {detail.supplierDisplayName}. Quotation acceptance will be available in the next
          workflow stage.
        </Typography.Text>
      </Space>
    </main>
  );
}
