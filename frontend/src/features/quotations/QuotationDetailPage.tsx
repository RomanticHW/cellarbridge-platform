import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Timeline,
  Typography,
  type TableProps,
} from 'antd';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import { z } from 'zod';
import {
  decideQuotationApproval,
  evaluateQuotationRoutes,
  getQuotation,
  issueQuotation,
  QuotationApiError,
  submitQuotation,
  type QuotationDetail,
  type TradeRouteCode,
} from '../../api/quotations';
import { useCurrentUser } from '../../api/currentUser';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';
import { TimelinePanel } from '../reporting/TimelinePanel';

const statusColors: Record<string, string> = {
  DRAFT: 'default',
  PENDING_APPROVAL: 'processing',
  CHANGES_REQUESTED: 'warning',
  APPROVED: 'success',
  SENT: 'cyan',
  REJECTED: 'error',
  EXPIRED: 'error',
};
const routeCodes: TradeRouteCode[] = ['SH_GENERAL_TRADE', 'NB_BONDED_B2B', 'HK_FREE_TRADE'];
const overrideSchema = z.object({
  routeCode: z.enum(routeCodes),
  reason: z.string().trim().min(5).max(500),
});
const decisionSchema = z.object({
  decision: z.enum(['APPROVE', 'REQUEST_CHANGES', 'REJECT']),
  reason: z.string().trim().min(5).max(500),
});
type OverrideValues = z.infer<typeof overrideSchema>;
type DecisionValues = z.infer<typeof decisionSchema>;
type SupplyDecisionLine = NonNullable<QuotationDetail['supplyDecision']>['lineDecisions'][number];

const lineColumns: TableProps<QuotationDetail['lines'][number]>['columns'] = [
  {
    title: 'SKU',
    render: (_, line) => (
      <Space orientation="vertical" size={0}>
        <Typography.Text strong>{line.sku.displayName}</Typography.Text>
        <Typography.Text type="secondary">
          {line.sku.skuCode} · snapshot v{line.sku.sourceVersion}
        </Typography.Text>
      </Space>
    ),
  },
  {
    title: 'Quantity',
    render: (_, line) => `${line.quantity.value} ${line.quantity.unit.toLowerCase()}`,
  },
  {
    title: 'List',
    render: (_, line) => `${line.listUnitPrice.currency} ${line.listUnitPrice.amount}`,
  },
  { title: 'Discount', dataIndex: 'discountRate', render: (value?: string) => value ?? '0' },
  {
    title: 'Net',
    render: (_, line) => `${line.netUnitPrice.currency} ${line.netUnitPrice.amount}`,
  },
  {
    title: 'Line total',
    render: (_, line) => `${line.lineTotal.currency} ${line.lineTotal.amount}`,
  },
  {
    title: 'Supply allocation',
    render: (_, line) =>
      line.allocationMode === 'FIXED_POOL'
        ? 'Specific pool'
        : line.allocationMode === 'ROUTE_ELIGIBLE_AUTO'
          ? 'Automatic'
          : 'Not decided',
  },
];

const supplyDecisionColumns: TableProps<SupplyDecisionLine>['columns'] = [
  {
    title: 'SKU',
    dataIndex: 'skuId',
    render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
  },
  {
    title: 'Allocation',
    dataIndex: 'allocationMode',
    render: (value: SupplyDecisionLine['allocationMode']) =>
      value === 'FIXED_POOL' ? 'Specific pool' : 'Automatic (route eligible)',
  },
  {
    title: 'Supply type',
    dataIndex: 'supplyType',
    render: (value: string) => value.replaceAll('_', ' '),
  },
  {
    title: 'Supply pool',
    dataIndex: 'supplyPoolId',
    render: (value?: string | null) =>
      value ? <Typography.Text code>{value}</Typography.Text> : 'Selected at reservation time',
  },
];

export function QuotationDetailPage() {
  const { quotationId = '' } = useParams();
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const currentUser = useCurrentUser(accessToken, session.sessionIdentity);
  const queryClient = useQueryClient();
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [decisionOpen, setDecisionOpen] = useState(false);
  const [portalUrl, setPortalUrl] = useState<string>();
  const quotationQuery = useQuery({
    queryKey: ['quotations', quotationId],
    queryFn: ({ signal }) => getQuotation(accessToken, quotationId, signal),
    enabled: accessToken !== '' && quotationId !== '',
  });
  const overrideForm = useForm<OverrideValues>({
    resolver: zodResolver(overrideSchema),
    defaultValues: { routeCode: 'SH_GENERAL_TRADE', reason: '' },
  });
  const decisionForm = useForm<DecisionValues>({
    resolver: zodResolver(decisionSchema),
    defaultValues: { decision: 'APPROVE', reason: '' },
  });

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: ['quotations'] });
    await quotationQuery.refetch();
  }
  const evaluateMutation = useMutation({
    mutationFn: (request?: { requestedRouteCode?: TradeRouteCode; overrideReason?: string }) => {
      if (!quotationQuery.data) throw new Error('Quotation detail is unavailable');
      return evaluateQuotationRoutes(accessToken, quotationId, quotationQuery.data.etag, request);
    },
    onSuccess: async () => {
      setOverrideOpen(false);
      overrideForm.reset();
      await refresh();
    },
  });
  const submitMutation = useMutation({
    mutationFn: () => {
      if (!quotationQuery.data) throw new Error('Quotation detail is unavailable');
      return submitQuotation(accessToken, quotationId, quotationQuery.data.etag);
    },
    onSuccess: refresh,
  });
  const decisionMutation = useMutation({
    mutationFn: (values: DecisionValues) => {
      if (!quotationQuery.data) throw new Error('Quotation detail is unavailable');
      return decideQuotationApproval(accessToken, quotationId, quotationQuery.data.etag, values);
    },
    onSuccess: async () => {
      setDecisionOpen(false);
      decisionForm.reset();
      await refresh();
    },
  });
  const issueMutation = useMutation({
    mutationFn: () => {
      if (!quotationQuery.data) throw new Error('Quotation detail is unavailable');
      return issueQuotation(accessToken, quotationId, quotationQuery.data.etag);
    },
    onSuccess: async (result) => {
      setPortalUrl(result.data.portalUrl);
      await refresh();
    },
  });

  if (quotationQuery.isPending) return <LoadingState message="Loading quotation detail…" />;
  if (quotationQuery.isError) {
    const problem =
      quotationQuery.error instanceof QuotationApiError ? quotationQuery.error : undefined;
    return (
      <ErrorState
        title={problem?.status === 404 ? 'Quotation not found' : 'Quotation detail unavailable'}
        description={
          problem
            ? `${problem.message} (${problem.code})`
            : 'The quotation is not visible or unavailable.'
        }
        actionLabel="Try again"
        onRetry={() => void quotationQuery.refetch()}
      />
    );
  }

  const quotation = quotationQuery.data.data;
  const actionError = [
    evaluateMutation.error,
    submitMutation.error,
    decisionMutation.error,
    issueMutation.error,
  ].find((error) => error instanceof QuotationApiError) as QuotationApiError | undefined;
  const route = quotation.routeEvaluation;
  return (
    <section className="quotation-page" aria-labelledby="quotation-detail-title">
      <Space orientation="vertical" size="large" className="quotation-page-stack">
        <div className="quotation-page-heading">
          <div>
            <Space align="center" wrap>
              <Typography.Title id="quotation-detail-title" level={2}>
                {quotation.number}
              </Typography.Title>
              <Tag color={statusColors[quotation.status]}>
                {quotation.status.replaceAll('_', ' ')}
              </Tag>
            </Space>
            <Typography.Text type="secondary">
              Revision {quotation.revision} · customer snapshot {quotation.partnerSnapshot.number}
            </Typography.Text>
          </div>
          <Space wrap>
            <Link to="/app/quotations">Back to quotations</Link>
            {quotation.allowedActions.includes('EDIT') && (
              <Link to={`/app/quotations/${quotation.id}/edit`}>Edit draft</Link>
            )}
            {quotation.allowedActions.includes('EVALUATE_ROUTE') && (
              <Button
                loading={evaluateMutation.isPending}
                onClick={() => evaluateMutation.mutate(undefined)}
              >
                Evaluate routes
              </Button>
            )}
            {quotation.allowedActions.includes('EVALUATE_ROUTE') &&
              currentUser.data?.permissions.includes('quotation:approve') && (
                <Button onClick={() => setOverrideOpen(true)}>Manager route override</Button>
              )}
            {quotation.allowedActions.includes('SUBMIT') && (
              <Button
                type="primary"
                loading={submitMutation.isPending}
                disabled={!route?.selectedRouteCode}
                onClick={() => submitMutation.mutate()}
              >
                Submit for approval
              </Button>
            )}
            {quotation.allowedActions.includes('APPROVE') && (
              <Button type="primary" onClick={() => setDecisionOpen(true)}>
                Review quotation
              </Button>
            )}
            {quotation.allowedActions.includes('ISSUE') && (
              <Button
                type="primary"
                loading={issueMutation.isPending}
                onClick={() => issueMutation.mutate()}
              >
                Issue quotation
              </Button>
            )}
          </Space>
        </div>
        {actionError && (
          <Alert
            showIcon
            type={actionError.status === 412 ? 'warning' : 'error'}
            title={
              actionError.status === 412
                ? 'Quotation changed during this action'
                : 'Action could not be completed'
            }
            description={`${actionError.message} (${actionError.code})`}
            action={
              actionError.status === 412 ? (
                <Button size="small" onClick={() => void quotationQuery.refetch()}>
                  Reload current version
                </Button>
              ) : undefined
            }
          />
        )}
        {portalUrl && (
          <Alert
            showIcon
            type="success"
            title="Quotation issued"
            description={
              <a href={portalUrl} target="_blank" rel="noreferrer">
                Open the customer-safe quotation preview
              </a>
            }
          />
        )}
        <Card title="Commercial summary">
          <Descriptions column={{ xs: 1, md: 2, lg: 4 }}>
            <Descriptions.Item label="Customer">
              {quotation.partnerSnapshot.displayName}
            </Descriptions.Item>
            <Descriptions.Item label="Total">
              {quotation.total.currency} {quotation.total.amount}
            </Descriptions.Item>
            <Descriptions.Item label="Requested delivery">
              {quotation.requestedDeliveryDate}
            </Descriptions.Item>
            <Descriptions.Item label="Expires">
              {new Date(quotation.expiresAt).toLocaleString()}
            </Descriptions.Item>
            <Descriptions.Item label="Payment terms">
              {quotation.paymentTermDays} days
            </Descriptions.Item>
            <Descriptions.Item label="Delivery address">
              {[
                quotation.deliveryAddress.line1,
                quotation.deliveryAddress.city,
                quotation.deliveryAddress.province,
                quotation.deliveryAddress.countryCode,
              ].join(', ')}
            </Descriptions.Item>
            {quotation.estimatedMarginRate != null && (
              <Descriptions.Item label="Estimated margin">
                {quotation.estimatedMarginRate}
              </Descriptions.Item>
            )}
          </Descriptions>
        </Card>
        <Card title="Priced revision snapshot">
          <Table
            rowKey="lineId"
            columns={lineColumns}
            dataSource={quotation.lines}
            pagination={false}
            scroll={{ x: 800 }}
          />
        </Card>
        <Card
          title="Supply decision"
          extra={
            <Tag color={quotation.supplyDecisionStatus === 'FROZEN' ? 'success' : 'warning'}>
              {quotation.supplyDecisionStatus.replaceAll('_', ' ')}
            </Tag>
          }
        >
          {quotation.supplyDecisionStatus === 'LEGACY_REEVALUATION_REQUIRED' ? (
            <Alert
              showIcon
              type="warning"
              title="Historical revision requires a verified supply decision"
              description="This route evidence predates decision freezing. The revision is view-only and cannot be submitted, issued, accepted, or rejected."
            />
          ) : quotation.supplyDecisionStatus === 'UNDECIDED' || !quotation.supplyDecision ? (
            <Alert
              showIcon
              type="info"
              title="Supply has not been decided"
              description="Evaluate a route to freeze a line-level supply type. Inventory is not reserved by this step."
            />
          ) : (
            <Space orientation="vertical" size="middle" className="quotation-page-stack">
              <Alert
                showIcon
                type="success"
                title="Route-bound supply decision frozen"
                description="This evidence constrains later reservation; it does not reserve inventory or a Lot."
              />
              <Descriptions column={{ xs: 1, md: 2, lg: 3 }} size="small">
                <Descriptions.Item label="Policy">
                  {quotation.supplyDecision.policyVersion}
                </Descriptions.Item>
                <Descriptions.Item label="Selected route">
                  {quotation.supplyDecision.selectedRouteCode.replaceAll('_', ' ')}
                </Descriptions.Item>
                <Descriptions.Item label="Decided">
                  {new Date(quotation.supplyDecision.decidedAt).toLocaleString()}
                </Descriptions.Item>
                <Descriptions.Item label="Inventory evidence as of">
                  {new Date(quotation.supplyDecision.inventoryDataAsOf).toLocaleString()}
                </Descriptions.Item>
                <Descriptions.Item label="Evaluation ID">
                  <Typography.Text code>
                    {quotation.supplyDecision.sourceRouteEvaluationId}
                  </Typography.Text>
                </Descriptions.Item>
                <Descriptions.Item label="Decision hash">
                  <Typography.Text code>{quotation.supplyDecision.decisionHash}</Typography.Text>
                </Descriptions.Item>
              </Descriptions>
              <Table
                rowKey="quotationLineId"
                columns={supplyDecisionColumns}
                dataSource={quotation.supplyDecision.lineDecisions}
                pagination={false}
                scroll={{ x: 760 }}
              />
            </Space>
          )}
        </Card>
        <Card title="Trade route explanation" extra={route && <Tag>{route.policyVersion}</Tag>}>
          {!route ? (
            <Typography.Text type="secondary">
              No route evaluation has been recorded for this revision.
            </Typography.Text>
          ) : (
            <Space orientation="vertical" size="middle" className="quotation-page-stack">
              <Alert
                type="info"
                showIcon
                title={`Selected route: ${route.selectedRouteCode?.replaceAll('_', ' ') ?? 'None'}`}
                description={`Recommended: ${route.recommendedRouteCode?.replaceAll('_', ' ') ?? 'No eligible route'} · evaluated ${new Date(route.evaluatedAt).toLocaleString()}`}
              />
              {route.override && (
                <Alert
                  type="warning"
                  showIcon
                  title="Manager override recorded"
                  description={`${route.override.reason ?? 'Reason unavailable'} · original recommendation ${route.override.originalRecommendation?.replaceAll('_', ' ') ?? route.recommendedRouteCode?.replaceAll('_', ' ')}`}
                />
              )}
              <div className="route-candidate-grid">
                {route.candidates.map((candidate) => (
                  <Card
                    key={candidate.routeCode}
                    size="small"
                    title={candidate.routeCode.replaceAll('_', ' ')}
                    extra={
                      <Tag color={candidate.eligibility === 'ELIGIBLE' ? 'success' : 'error'}>
                        {candidate.eligibility}
                      </Tag>
                    }
                  >
                    {candidate.score ? (
                      <Descriptions size="small" column={1}>
                        <Descriptions.Item label="Weighted score">
                          {candidate.score.total}
                        </Descriptions.Item>
                        <Descriptions.Item label="Estimated delivery">
                          {candidate.estimatedDeliveryDate ?? '—'}
                        </Descriptions.Item>
                        <Descriptions.Item label="Estimated charges">
                          {candidate.estimatedCharges
                            ? `${candidate.estimatedCharges.currency} ${candidate.estimatedCharges.amount}`
                            : '—'}
                        </Descriptions.Item>
                      </Descriptions>
                    ) : (
                      <ul>
                        {candidate.rejections?.map((rejection) => (
                          <li key={rejection.ruleId}>{rejection.message}</li>
                        ))}
                      </ul>
                    )}
                  </Card>
                ))}
              </div>
            </Space>
          )}
        </Card>
        <Card title="Approval requirements and decisions">
          <Space orientation="vertical" className="quotation-page-stack">
            {quotation.approvalRequirements.length === 0 ? (
              <Typography.Text type="secondary">
                No approval threshold is currently triggered.
              </Typography.Text>
            ) : (
              quotation.approvalRequirements.map((requirement) => (
                <Alert
                  key={requirement.ruleId}
                  type="warning"
                  showIcon
                  title={requirement.message}
                  description={`${requirement.code}: ${requirement.actualValue} (threshold ${requirement.threshold})`}
                />
              ))
            )}
            {quotation.approvals?.map((approval, index) => (
              <Alert
                key={`${approval.occurredAt}-${index}`}
                type={approval.decision === 'APPROVE' ? 'success' : 'warning'}
                title={approval.decision?.replaceAll('_', ' ')}
                description={`${approval.reason ?? ''} · ${approval.occurredAt ? new Date(approval.occurredAt).toLocaleString() : ''}`}
              />
            ))}
          </Space>
        </Card>
        <Card title="Revision timeline">
          <Timeline
            items={quotation.timeline.map((entry) => ({
              color: entry.newState === 'REJECTED' ? 'red' : 'blue',
              content: (
                <div>
                  <Typography.Text strong>{entry.action.replaceAll('_', ' ')}</Typography.Text>
                  <br />
                  <Typography.Text type="secondary">
                    {new Date(entry.occurredAt).toLocaleString()} · {entry.previousState ?? 'NEW'} →{' '}
                    {entry.newState ?? 'UNCHANGED'}
                    {entry.safeReason ? ` · ${entry.safeReason}` : ''}
                  </Typography.Text>
                </div>
              ),
            }))}
          />
        </Card>
        <TimelinePanel subjectType="QUOTATION" subjectId={quotation.id} />
      </Space>
      <Modal
        open={overrideOpen}
        title="Select a non-recommended trade route"
        okText="Record override"
        okButtonProps={{ loading: evaluateMutation.isPending }}
        onOk={() =>
          void overrideForm.handleSubmit((values) =>
            evaluateMutation.mutate({
              requestedRouteCode: values.routeCode,
              overrideReason: values.reason,
            }),
          )()
        }
        onCancel={() => setOverrideOpen(false)}
      >
        <form
          onSubmit={(event) =>
            void overrideForm.handleSubmit((values) =>
              evaluateMutation.mutate({
                requestedRouteCode: values.routeCode,
                overrideReason: values.reason,
              }),
            )(event)
          }
        >
          <Form.Item
            label="Route"
            required
            validateStatus={overrideForm.formState.errors.routeCode ? 'error' : undefined}
            help={overrideForm.formState.errors.routeCode?.message}
          >
            <Controller
              control={overrideForm.control}
              name="routeCode"
              render={({ field }) => (
                <Select
                  {...field}
                  aria-label="Override route"
                  options={routeCodes.map((code) => ({
                    value: code,
                    label: code.replaceAll('_', ' '),
                  }))}
                />
              )}
            />
          </Form.Item>
          <Form.Item
            label="Reason"
            required
            validateStatus={overrideForm.formState.errors.reason ? 'error' : undefined}
            help={overrideForm.formState.errors.reason?.message}
          >
            <Controller
              control={overrideForm.control}
              name="reason"
              render={({ field }) => (
                <Input.TextArea {...field} aria-label="Override reason" rows={3} />
              )}
            />
          </Form.Item>
        </form>
      </Modal>
      <Modal
        open={decisionOpen}
        title="Review quotation revision"
        okText="Record decision"
        okButtonProps={{ loading: decisionMutation.isPending }}
        onOk={() => void decisionForm.handleSubmit((values) => decisionMutation.mutate(values))()}
        onCancel={() => setDecisionOpen(false)}
      >
        <form
          onSubmit={(event) =>
            void decisionForm.handleSubmit((values) => decisionMutation.mutate(values))(event)
          }
        >
          <Form.Item label="Decision" required>
            <Controller
              control={decisionForm.control}
              name="decision"
              render={({ field }) => (
                <Select
                  {...field}
                  aria-label="Approval decision"
                  options={[
                    { value: 'APPROVE', label: 'Approve' },
                    { value: 'REQUEST_CHANGES', label: 'Request changes' },
                    { value: 'REJECT', label: 'Reject' },
                  ]}
                />
              )}
            />
          </Form.Item>
          <Form.Item
            label="Reason"
            required
            validateStatus={decisionForm.formState.errors.reason ? 'error' : undefined}
            help={decisionForm.formState.errors.reason?.message}
          >
            <Controller
              control={decisionForm.control}
              name="reason"
              render={({ field }) => (
                <Input.TextArea {...field} aria-label="Approval reason" rows={3} />
              )}
            />
          </Form.Item>
        </form>
      </Modal>
    </section>
  );
}
