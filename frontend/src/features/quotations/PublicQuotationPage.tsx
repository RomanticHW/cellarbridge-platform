import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Result,
  Select,
  Space,
  Table,
  Typography,
  type TableProps,
} from 'antd';
import { useRef, useState, type RefObject } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  acceptPublicQuotation,
  getPublicQuotation,
  QuotationApiError,
  rejectPublicQuotation,
  type PublicQuotation,
  type QuotationAcceptanceRequest,
  type QuotationAcceptanceResult,
  type QuotationRejectionReason,
  type QuotationRejectionRequest,
  type QuotationRejectionResult,
} from '../../api/quotations';
import { LoadingState } from '../../components/LoadingState';

const columns: TableProps<PublicQuotation['lines'][number]>['columns'] = [
  {
    title: 'Wine',
    render: (_, line) => (
      <Space orientation="vertical" size={0}>
        <Typography.Text strong>{line.description}</Typography.Text>
        <Typography.Text type="secondary">
          {[line.skuCode, line.vintage, line.package].filter(Boolean).join(' · ')}
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

const rejectionReasons: Array<{ value: QuotationRejectionReason; label: string }> = [
  { value: 'PRICE', label: 'Price' },
  { value: 'DELIVERY_TIMING', label: 'Delivery timing' },
  { value: 'PAYMENT_TERMS', label: 'Payment terms' },
  { value: 'PRODUCT_SELECTION', label: 'Product selection' },
  { value: 'OTHER', label: 'Other' },
];

interface IntentKey {
  payload: string;
  value: string;
}

function keyForIntent(reference: RefObject<IntentKey | null>, payload: string) {
  if (reference.current?.payload !== payload) {
    reference.current = { payload, value: globalThis.crypto.randomUUID() };
  }
  return reference.current.value;
}

function decisionError(error: unknown) {
  if (!(error instanceof QuotationApiError)) return null;
  return {
    code: error.code,
    message:
      error.code === 'IDEMPOTENCY_KEY_REUSED'
        ? 'This decision changed after it was first submitted. Review the quotation and try again.'
        : error.message,
  };
}

function decisionReceipt(detail: PublicQuotation) {
  const receipt = detail.decisionReceipt;
  if (!receipt) return null;
  return (
    <Descriptions bordered column={{ xs: 1, sm: 2 }} size="small">
      <Descriptions.Item label="Decision reference">
        {receipt.reference ?? receipt.decisionId}
      </Descriptions.Item>
      <Descriptions.Item label="Recorded at">
        {new Date(receipt.decidedAt).toLocaleString()}
      </Descriptions.Item>
      {detail.orderNumber ? (
        <Descriptions.Item label="Order number">{detail.orderNumber}</Descriptions.Item>
      ) : null}
      {detail.orderCreationStatus ? (
        <Descriptions.Item label="Order creation">
          {detail.orderCreationStatus.replaceAll('_', ' ')}
        </Descriptions.Item>
      ) : null}
      {detail.orderId ? (
        <Descriptions.Item label="Order">
          <Link to={`/app/orders/${detail.orderId}`}>
            Sign in to view {detail.orderNumber ?? 'the order'}
          </Link>
        </Descriptions.Item>
      ) : null}
    </Descriptions>
  );
}

function terminalResult(detail: PublicQuotation) {
  if (detail.status === 'ACCEPTED' || detail.status === 'CONVERTED') {
    return (
      <Card>
        <Result
          status="success"
          title="Quotation accepted"
          subTitle={
            detail.orderId
              ? 'Your order is ready to review in the secured operations portal.'
              : detail.orderCreationStatus === 'FAILED_RETRYING'
                ? 'Your decision is recorded. Order creation is retrying safely.'
                : 'Your decision is recorded. Order creation is in progress.'
          }
          extra={decisionReceipt(detail)}
        />
      </Card>
    );
  }
  if (detail.status === 'REJECTED_BY_CUSTOMER') {
    return (
      <Card>
        <Result
          status="info"
          title="Quotation rejected"
          subTitle="Your decision is final for this quotation revision."
          extra={decisionReceipt(detail)}
        />
      </Card>
    );
  }
  if (detail.status === 'EXPIRED') {
    return (
      <Card>
        <Result
          status="warning"
          title="Quotation expired"
          subTitle="This quotation can no longer be accepted or rejected."
        />
      </Card>
    );
  }
  if (detail.status === 'WITHDRAWN') {
    return (
      <Card>
        <Result
          status="warning"
          title="Quotation withdrawn"
          subTitle="The supplier withdrew this quotation. Contact your account representative for an updated offer."
        />
      </Card>
    );
  }
  return null;
}

export function PublicQuotationPage() {
  const { publicToken = '' } = useParams();
  const queryClient = useQueryClient();
  const queryKey = ['public-quotation', publicToken] as const;
  const acceptanceKey = useRef<IntentKey | null>(null);
  const rejectionKey = useRef<IntentKey | null>(null);
  const acceptanceInFlight = useRef(false);
  const rejectionInFlight = useRef(false);
  const [termsConfirmed, setTermsConfirmed] = useState(false);
  const [rejectionReason, setRejectionReason] = useState<QuotationRejectionReason>();

  const quotation = useQuery({
    queryKey,
    queryFn: ({ signal }) => getPublicQuotation(publicToken, signal),
    enabled: publicToken !== '',
    retry: false,
    refetchInterval: (query) => (shouldPollForOrder(query.state.data) ? 2_000 : false),
  });

  const refreshQuotation = () => queryClient.invalidateQueries({ queryKey, refetchType: 'active' });

  const acceptance = useMutation({
    mutationFn: ({ request, idempotencyKey }: DecisionCommand<QuotationAcceptanceRequest>) =>
      acceptPublicQuotation(publicToken, idempotencyKey, request),
    onSuccess: (result) => {
      updateAcceptedQuotation(queryClient, queryKey, result);
      void refreshQuotation();
    },
  });

  const rejection = useMutation({
    mutationFn: ({ request, idempotencyKey }: DecisionCommand<QuotationRejectionRequest>) =>
      rejectPublicQuotation(publicToken, idempotencyKey, request),
    onSuccess: (result) => {
      updateRejectedQuotation(queryClient, queryKey, result);
      void refreshQuotation();
    },
  });

  if (quotation.isPending)
    return (
      <main className="public-quotation-page">
        <LoadingState message="Loading quotation…" />
      </main>
    );
  if (quotation.isError) {
    const problem = quotation.error instanceof QuotationApiError ? quotation.error : undefined;
    const expired = problem?.code === 'QUOTE_EXPIRED';
    const withdrawn = problem?.code === 'QUOTE_WITHDRAWN';
    return (
      <main className="public-quotation-page">
        <Result
          status={expired || withdrawn ? 'warning' : problem?.status === 404 ? '404' : 'error'}
          title={
            expired
              ? 'Quotation expired'
              : withdrawn
                ? 'Quotation withdrawn'
                : 'Quotation unavailable or revoked'
          }
          subTitle={
            expired
              ? 'The validity period ended and this quotation can no longer be decided.'
              : withdrawn
                ? 'Contact your account representative for an updated offer.'
                : problem?.status === 404
                  ? 'This secure quotation link is invalid, revoked, or no longer available.'
                  : 'The quotation could not be loaded. Please try again later.'
          }
          extra={
            problem && problem.status !== 404 && !expired && !withdrawn ? (
              <Button onClick={() => void quotation.refetch()}>Try again</Button>
            ) : undefined
          }
        />
      </main>
    );
  }

  const detail = quotation.data;
  const finalResult = terminalResult(detail);
  const error = decisionError(acceptance.error ?? rejection.error);
  const canAccept = detail.allowedActions.includes('ACCEPT');
  const canReject = detail.allowedActions.includes('REJECT');
  const decisionPending = acceptance.isPending || rejection.isPending;

  const accept = () => {
    if (!termsConfirmed || acceptanceInFlight.current) return;
    rejection.reset();
    const request = { acceptedTermsVersion: detail.termsVersion };
    const payload = JSON.stringify(request);
    acceptanceInFlight.current = true;
    acceptance.mutate(
      { request, idempotencyKey: keyForIntent(acceptanceKey, payload) },
      {
        onSettled: () => {
          acceptanceInFlight.current = false;
        },
      },
    );
  };

  const reject = () => {
    if (rejectionInFlight.current) return;
    acceptance.reset();
    const request = rejectionReason ? { reasonCategory: rejectionReason } : {};
    const payload = JSON.stringify(request);
    rejectionInFlight.current = true;
    rejection.mutate(
      { request, idempotencyKey: keyForIntent(rejectionKey, payload) },
      {
        onSettled: () => {
          rejectionInFlight.current = false;
        },
      },
    );
  };

  return (
    <main className="public-quotation-page">
      <Space orientation="vertical" size="large" className="public-quotation-stack">
        <header className="public-quotation-header">
          <Typography.Text className="auth-brand">{detail.supplierDisplayName}</Typography.Text>
          <Typography.Title level={1}>Quotation {detail.number}</Typography.Title>
          <Typography.Text type="secondary">
            Revision {detail.revision} · prepared for {detail.customerDisplayName}
          </Typography.Text>
        </header>
        <Alert
          type="info"
          showIcon
          title={`Valid until ${new Date(detail.expiresAt).toLocaleString()}`}
          description="This secure page contains customer-visible commercial terms only."
        />
        {finalResult}
        <Card title="Commercial offer">
          <Table
            rowKey={(line) =>
              `${line.skuCode}-${line.description}-${line.quantity.value}-${line.unitPrice.amount}`
            }
            columns={columns}
            dataSource={detail.lines}
            pagination={false}
            scroll={{ x: 680 }}
          />
          <Descriptions className="public-quotation-totals" column={1} size="small">
            <Descriptions.Item label="Subtotal">
              {detail.subtotal.currency} {detail.subtotal.amount}
            </Descriptions.Item>
            <Descriptions.Item label="Delivery and handling fees">
              {detail.fees.currency} {detail.fees.amount}
            </Descriptions.Item>
          </Descriptions>
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
        <Card title="Quotation terms">
          <ul className="public-terms-list">
            {detail.termsSummary.map((term, index) => (
              <li key={`${index}-${term}`}>{term}</li>
            ))}
          </ul>
        </Card>
        {detail.status === 'SENT' && (canAccept || canReject) ? (
          <Card title="Your decision">
            <Space orientation="vertical" size="middle" className="public-decision-form">
              {error ? (
                <Alert
                  type="error"
                  showIcon
                  title={`Decision not recorded · ${error.code}`}
                  description={error.message}
                  action={<Button onClick={() => void quotation.refetch()}>Refresh status</Button>}
                />
              ) : null}
              {canAccept ? (
                <Checkbox
                  checked={termsConfirmed}
                  disabled={decisionPending}
                  onChange={(event) => setTermsConfirmed(event.target.checked)}
                >
                  I have reviewed and agree to quotation terms version {detail.termsVersion}.
                </Checkbox>
              ) : null}
              {canReject ? (
                <label className="public-rejection-reason">
                  <Typography.Text>Optional rejection reason</Typography.Text>
                  <Select<QuotationRejectionReason>
                    aria-label="Optional rejection reason"
                    allowClear
                    disabled={decisionPending}
                    options={rejectionReasons}
                    placeholder="Choose a category"
                    value={rejectionReason}
                    onChange={setRejectionReason}
                  />
                </label>
              ) : null}
              <Space wrap>
                {canAccept ? (
                  <Button
                    type="primary"
                    disabled={!termsConfirmed || decisionPending}
                    loading={acceptance.isPending}
                    onClick={accept}
                  >
                    Accept quotation
                  </Button>
                ) : null}
                {canReject ? (
                  <Button
                    danger
                    disabled={decisionPending}
                    loading={rejection.isPending}
                    onClick={reject}
                  >
                    Reject quotation
                  </Button>
                ) : null}
              </Space>
            </Space>
          </Card>
        ) : null}
        <Typography.Text type="secondary">
          Issued by {detail.supplierDisplayName}. Contact your account representative to request
          changes.
        </Typography.Text>
      </Space>
    </main>
  );
}

interface DecisionCommand<T> {
  request: T;
  idempotencyKey: string;
}

type PublicQueryKey = readonly ['public-quotation', string];

function updateAcceptedQuotation(
  queryClient: QueryClient,
  queryKey: PublicQueryKey,
  result: QuotationAcceptanceResult,
) {
  queryClient.setQueryData<PublicQuotation>(queryKey, (current) =>
    current
      ? {
          ...current,
          status: 'ACCEPTED',
          allowedActions: [],
          decisionReceipt: {
            decisionId: result.acceptanceId,
            decision: 'ACCEPTED',
            decidedAt: result.acceptedAt,
            reference: result.acceptanceId,
          },
          orderId: result.orderId,
          orderNumber: result.orderNumber,
          orderCreationStatus: result.orderCreationStatus,
        }
      : current,
  );
}

export function shouldPollForOrder(detail: PublicQuotation | undefined): boolean {
  return (
    detail !== undefined &&
    (detail.status === 'ACCEPTED' || detail.status === 'CONVERTED') &&
    !detail.orderId
  );
}

function updateRejectedQuotation(
  queryClient: QueryClient,
  queryKey: PublicQueryKey,
  result: QuotationRejectionResult,
) {
  queryClient.setQueryData<PublicQuotation>(queryKey, (current) =>
    current
      ? {
          ...current,
          status: 'REJECTED_BY_CUSTOMER',
          allowedActions: [],
          decisionReceipt: {
            decisionId: result.rejectionId,
            decision: 'REJECTED_BY_CUSTOMER',
            decidedAt: result.rejectedAt,
            reference: result.rejectionId,
          },
        }
      : current,
  );
}
