import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Input,
  Modal,
  Select,
  Space,
  Tag,
  Timeline,
  Typography,
  message,
} from 'antd';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  actOnFulfillmentStep,
  FulfillmentApiError,
  getFulfillmentPlan,
  type FulfillmentAction,
  type FulfillmentPlanDetail,
  type FulfillmentStepStatus,
} from '../../api/fulfillment';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';

type Step = FulfillmentPlanDetail['steps'][number];

const stepColors: Record<FulfillmentStepStatus, string> = {
  BLOCKED: 'default',
  READY: 'blue',
  IN_PROGRESS: 'processing',
  COMPLETED: 'success',
  FAILED: 'error',
  OVERDUE: 'warning',
  CANCELLED: 'default',
  SKIPPED: 'default',
};

function actionsFor(step: Step): FulfillmentAction[] {
  const allowed = step.allowedActions;
  if (step.status === 'READY' || (step.status === 'OVERDUE' && !step.startedAt)) {
    return allowed.includes('START') ? ['START'] : [];
  }
  if (step.status === 'IN_PROGRESS' || (step.status === 'OVERDUE' && step.startedAt)) {
    return (['COMPLETE', 'FAIL'] as FulfillmentAction[]).filter((action) =>
      allowed.includes(action),
    );
  }
  if (step.status === 'FAILED' && allowed.includes('RETRY')) return ['RETRY'];
  if (step.skippable && allowed.includes('SKIP')) return ['SKIP'];
  return [];
}

export function FulfillmentDetailPage() {
  const { planId = '' } = useParams();
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<{ step: Step; action: FulfillmentAction }>();
  const [reason, setReason] = useState('');
  const [scenario, setScenario] = useState<'SUCCESS' | 'FAILURE' | 'DELAY' | 'TIMEOUT'>('SUCCESS');
  const plan = useQuery({
    queryKey: ['fulfillment', 'plan', planId],
    queryFn: ({ signal }) => getFulfillmentPlan(accessToken, planId, signal),
    enabled: accessToken !== '' && planId !== '',
  });
  const action = useMutation({
    mutationFn: (request: { step: Step; action: FulfillmentAction }) =>
      actOnFulfillmentStep(
        accessToken,
        planId,
        request.step.id,
        plan.data?.version ?? 0,
        request.action,
        reason || undefined,
        request.action === 'COMPLETE' ? scenario : undefined,
      ),
    onSuccess: async () => {
      setDialog(undefined);
      setReason('');
      setScenario('SUCCESS');
      await queryClient.invalidateQueries({ queryKey: ['fulfillment'] });
      void message.success('Fulfillment step updated');
    },
    onError: async (error) => {
      if (error instanceof FulfillmentApiError && (error.status === 409 || error.status === 412)) {
        await queryClient.invalidateQueries({ queryKey: ['fulfillment', 'plan', planId] });
        void message.warning(
          'The plan changed. The latest state has been loaded; review and retry.',
        );
      }
    },
  });

  if (plan.isPending) return <LoadingState message="Loading fulfillment plan…" />;
  if (plan.isError) {
    const problem = plan.error instanceof FulfillmentApiError ? plan.error : undefined;
    return (
      <ErrorState
        title={
          problem?.status === 404 ? 'Fulfillment plan not found' : 'Fulfillment plan is unavailable'
        }
        description={
          problem ? `${problem.message} (${problem.code})` : 'The plan could not be loaded.'
        }
        actionLabel="Try again"
        onRetry={() => void plan.refetch()}
      />
    );
  }

  const detail = plan.data;
  return (
    <article className="fulfillment-page" aria-labelledby="fulfillment-detail-title">
      <Space orientation="vertical" size="large" className="fulfillment-page-stack">
        <header className="fulfillment-heading">
          <div>
            <Link to="/app/fulfillment">← Back to fulfillment board</Link>
            <Typography.Title id="fulfillment-detail-title" level={2}>
              {detail.number}
            </Typography.Title>
            <Typography.Text type="secondary">
              {detail.templateCode} · template {detail.templateVersion}
            </Typography.Text>
          </div>
          <Tag
            color={
              detail.overdue ? 'error' : detail.status === 'COMPLETED' ? 'success' : 'processing'
            }
          >
            {detail.status.replaceAll('_', ' ')}
          </Tag>
        </header>

        <Card title="Plan snapshot">
          <Descriptions column={{ xs: 1, md: 2 }}>
            <Descriptions.Item label="Order">
              <Link to={`/app/orders/${detail.orderId}`}>{detail.orderNumber}</Link>
            </Descriptions.Item>
            <Descriptions.Item label="Route">
              {detail.routeCode.replaceAll('_', ' ')}
            </Descriptions.Item>
            <Descriptions.Item label="Current work">
              {detail.currentStep ?? 'All required work complete'}
            </Descriptions.Item>
            <Descriptions.Item label="Plan due">
              {detail.dueAt ? new Date(detail.dueAt).toLocaleString() : 'Not scheduled'}
            </Descriptions.Item>
          </Descriptions>
        </Card>

        <section aria-labelledby="fulfillment-steps-title">
          <Typography.Title id="fulfillment-steps-title" level={3}>
            Dependency plan
          </Typography.Title>
          <div className="fulfillment-step-graph">
            {detail.steps.map((step, index) => {
              const available = actionsFor(step);
              return (
                <div className="fulfillment-step-node" key={step.id}>
                  <div className="fulfillment-step-index">{String(index + 1).padStart(2, '0')}</div>
                  <div className="fulfillment-step-body">
                    <Space wrap>
                      <Typography.Text strong>{step.name}</Typography.Text>
                      <Tag color={stepColors[step.status]}>{step.status.replaceAll('_', ' ')}</Tag>
                      {step.customerVisible ? <Tag>Customer milestone</Tag> : null}
                    </Space>
                    <Typography.Text type="secondary">
                      Owner: {step.assigneeRole?.replaceAll('_', ' ')} · Due{' '}
                      {step.dueAt ? new Date(step.dueAt).toLocaleString() : 'not scheduled'}
                    </Typography.Text>
                    {step.dependencies.length > 0 ? (
                      <Typography.Text type="secondary">
                        Depends on {step.dependencies.join(', ')}
                      </Typography.Text>
                    ) : null}
                    {step.safeMessage ? (
                      <Alert type="warning" showIcon title={step.safeMessage} />
                    ) : null}
                    {step.latestAdapterAttempt ? (
                      <Typography.Text type="secondary">
                        Simulator: {step.latestAdapterAttempt.scenario} →{' '}
                        {step.latestAdapterAttempt.outcome} ({step.latestAdapterAttempt.reference})
                      </Typography.Text>
                    ) : null}
                    {available.length > 0 ? (
                      <Space wrap>
                        {available.map((item) => (
                          <Button
                            key={item}
                            type={item === 'START' || item === 'COMPLETE' ? 'primary' : 'default'}
                            danger={item === 'FAIL'}
                            onClick={() => setDialog({ step, action: item })}
                          >
                            {item.charAt(0) + item.slice(1).toLowerCase()}
                          </Button>
                        ))}
                      </Space>
                    ) : null}
                  </div>
                </div>
              );
            })}
          </div>
        </section>

        <Card title="Customer-visible milestones">
          {detail.milestones.length === 0 ? (
            <Typography.Text type="secondary">
              No customer-visible milestones have been reached.
            </Typography.Text>
          ) : (
            <Timeline
              items={detail.milestones
                .filter((milestone) => milestone.customerVisible)
                .map((milestone) => ({
                  color: 'green',
                  content: (
                    <Space orientation="vertical" size={0}>
                      <Typography.Text>{milestone.label}</Typography.Text>
                      <Typography.Text type="secondary">
                        {new Date(milestone.occurredAt).toLocaleString()}
                      </Typography.Text>
                    </Space>
                  ),
                }))}
            />
          )}
        </Card>
      </Space>

      <Modal
        open={dialog !== undefined}
        title={
          dialog
            ? `${dialog.action.charAt(0) + dialog.action.slice(1).toLowerCase()} ${dialog.step.name}`
            : 'Update step'
        }
        okText="Confirm action"
        okButtonProps={{ danger: dialog?.action === 'FAIL', loading: action.isPending }}
        onCancel={() => setDialog(undefined)}
        onOk={() => dialog && action.mutate(dialog)}
      >
        <Space orientation="vertical" size="middle" className="fulfillment-dialog-fields">
          {dialog?.action === 'COMPLETE' ? (
            <label>
              <Typography.Text>Simulation outcome</Typography.Text>
              <Select
                aria-label="Simulation outcome"
                value={scenario}
                onChange={setScenario}
                options={[
                  { value: 'SUCCESS', label: 'Success' },
                  { value: 'FAILURE', label: 'Failure' },
                  { value: 'DELAY', label: 'Delay' },
                  { value: 'TIMEOUT', label: 'Timeout' },
                ]}
              />
            </label>
          ) : null}
          {dialog?.action === 'FAIL' ? (
            <label>
              <Typography.Text>Operational reason</Typography.Text>
              <Input.TextArea
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                maxLength={500}
              />
            </label>
          ) : null}
          {action.isError ? (
            <Alert
              type="error"
              showIcon
              title={
                action.error instanceof FulfillmentApiError
                  ? `${action.error.message} (${action.error.code})`
                  : 'The action could not be completed.'
              }
            />
          ) : null}
        </Space>
      </Modal>
    </article>
  );
}
