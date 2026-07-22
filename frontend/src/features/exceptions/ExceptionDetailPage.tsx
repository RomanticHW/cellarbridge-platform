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
  assignException,
  closeException,
  ExceptionApiError,
  getException,
  recoverException,
  transitionException,
  type ClosureReasonCode,
  type ExceptionDetail,
  type RecoveryAction,
} from '../../api/exceptions';
import { useCurrentUser } from '../../api/currentUser';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';

type Dialog = 'ASSIGN' | 'ACKNOWLEDGE' | 'BEGIN_INVESTIGATION' | 'RECOVERY' | 'CLOSE';

function label(value: string) {
  return value.replaceAll('_', ' ');
}

export function ExceptionDetailPage() {
  const { exceptionId = '' } = useParams();
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const currentUser = useCurrentUser(session.accessToken, session.sessionIdentity);
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<Dialog>();
  const [reason, setReason] = useState('');
  const [recoveryAction, setRecoveryAction] = useState<RecoveryAction>();
  const [closeReasonCode, setCloseReasonCode] = useState<ClosureReasonCode>('RECOVERY_VERIFIED');
  const [primaryCaseId, setPrimaryCaseId] = useState('');
  const exception = useQuery({
    queryKey: ['exceptions', 'case', exceptionId],
    queryFn: ({ signal }) => getException(accessToken, exceptionId, signal),
    enabled: accessToken !== '' && exceptionId !== '',
  });
  const command = useMutation({
    mutationFn: async (active: Dialog) => {
      const detail = exception.data;
      if (!detail) throw new Error('Exception detail is unavailable');
      const version = detail.summary.version;
      if (active === 'ASSIGN') {
        const assigneeId = currentUser.data?.userId;
        if (!assigneeId) throw new Error('Current user is unavailable');
        return assignException(accessToken, exceptionId, version, assigneeId, reason);
      }
      if (active === 'ACKNOWLEDGE' || active === 'BEGIN_INVESTIGATION') {
        return transitionException(accessToken, exceptionId, version, active, reason);
      }
      if (active === 'RECOVERY') {
        if (!recoveryAction) throw new Error('Recovery action is required');
        await recoverException(accessToken, exceptionId, version, recoveryAction, reason);
        return getException(accessToken, exceptionId);
      }
      return closeException(
        accessToken,
        exceptionId,
        version,
        closeReasonCode,
        reason,
        closeReasonCode === 'DUPLICATE' ? primaryCaseId : undefined,
      );
    },
    onSuccess: async () => {
      setDialog(undefined);
      setReason('');
      setRecoveryAction(undefined);
      setPrimaryCaseId('');
      await queryClient.invalidateQueries({ queryKey: ['exceptions'] });
      void message.success('Exception case updated');
    },
    onError: async (error) => {
      if (error instanceof ExceptionApiError && [409, 412].includes(error.status)) {
        await queryClient.invalidateQueries({ queryKey: ['exceptions', 'case', exceptionId] });
        void message.warning('The case changed. Review the latest evidence before retrying.');
      }
    },
  });

  if (exception.isPending) return <LoadingState message="Loading exception evidence…" />;
  if (exception.isError) {
    const problem = exception.error instanceof ExceptionApiError ? exception.error : undefined;
    return (
      <ErrorState
        title={
          problem?.status === 404 ? 'Exception case not found' : 'Exception case is unavailable'
        }
        description={
          problem ? `${problem.message} (${problem.code})` : 'The case could not be loaded.'
        }
        actionLabel="Try again"
        onRetry={() => void exception.refetch()}
      />
    );
  }

  const detail: ExceptionDetail = exception.data;
  const can = (action: string) => detail.allowedActions.includes(action);
  const closureOptions: { value: ClosureReasonCode; label: string }[] = [];
  if (can('CLOSE')) {
    closureOptions.push({ value: 'RECOVERY_VERIFIED', label: 'Recovery verified' });
  }
  if (can('CLOSE_FALSE_POSITIVE')) {
    closureOptions.push({ value: 'FALSE_POSITIVE', label: 'False positive' });
  }
  if (can('CLOSE_DUPLICATE')) {
    closureOptions.push({ value: 'DUPLICATE', label: 'Duplicate' });
  }
  const openDialog = (next: Dialog) => {
    setDialog(next);
    setReason('');
    setPrimaryCaseId('');
    if (next === 'RECOVERY') setRecoveryAction(detail.allowedRecoveryActions[0]);
    if (next === 'CLOSE') setCloseReasonCode(closureOptions[0]?.value ?? 'RECOVERY_VERIFIED');
  };

  return (
    <article className="exception-page" aria-labelledby="exception-detail-title">
      <Space orientation="vertical" size="large" className="exception-page-stack">
        <header className="exception-heading">
          <div>
            <Link to="/app/exceptions">← Back to Exception Center</Link>
            <Typography.Title id="exception-detail-title" level={2}>
              {detail.summary.number}
            </Typography.Title>
            <Typography.Text type="secondary">
              {label(detail.summary.category)} · {detail.summary.sourceNumber}
            </Typography.Text>
          </div>
          <Space wrap>
            <Tag
              color={
                detail.summary.severity === 'CRITICAL'
                  ? 'magenta'
                  : detail.summary.severity === 'HIGH'
                    ? 'error'
                    : 'warning'
              }
            >
              {detail.summary.severity}
            </Tag>
            <Tag>{label(detail.summary.status)}</Tag>
          </Space>
        </header>

        <Card title="Case snapshot">
          <Descriptions column={{ xs: 1, md: 2 }}>
            <Descriptions.Item label="Summary">{detail.summary.summary}</Descriptions.Item>
            <Descriptions.Item label="Source">{label(detail.summary.sourceType)}</Descriptions.Item>
            <Descriptions.Item label="Opened">
              {new Date(detail.summary.openedAt).toLocaleString()}
            </Descriptions.Item>
            <Descriptions.Item label="Due">
              {detail.summary.dueAt ? new Date(detail.summary.dueAt).toLocaleString() : 'Not set'}
            </Descriptions.Item>
            <Descriptions.Item label="Assignee">
              {detail.summary.assigneeId ?? 'Unassigned'}
            </Descriptions.Item>
            {detail.summary.primaryCaseId ? (
              <Descriptions.Item label="Primary case">
                <Link to={`/app/exceptions/${detail.summary.primaryCaseId}`}>
                  {detail.summary.primaryCaseId}
                </Link>
              </Descriptions.Item>
            ) : null}
            <Descriptions.Item label="Version">{detail.summary.version}</Descriptions.Item>
            <Descriptions.Item label="Source evidence" span={2}>
              <pre className="safe-evidence">{JSON.stringify(detail.safeDetails, null, 2)}</pre>
            </Descriptions.Item>
          </Descriptions>
          <Space wrap className="exception-actions">
            {can('ASSIGN') ? (
              <Button onClick={() => openDialog('ASSIGN')}>Assign to me</Button>
            ) : null}
            {can('ACKNOWLEDGE') ? (
              <Button type="primary" onClick={() => openDialog('ACKNOWLEDGE')}>
                Acknowledge
              </Button>
            ) : null}
            {can('BEGIN_INVESTIGATION') ? (
              <Button type="primary" onClick={() => openDialog('BEGIN_INVESTIGATION')}>
                Begin investigation
              </Button>
            ) : null}
            {can('REQUEST_RECOVERY') ? (
              <Button type="primary" onClick={() => openDialog('RECOVERY')}>
                Choose recovery
              </Button>
            ) : null}
            {can('CLOSE') || can('CLOSE_FALSE_POSITIVE') || can('CLOSE_DUPLICATE') ? (
              <Button onClick={() => openDialog('CLOSE')}>Close case</Button>
            ) : null}
          </Space>
        </Card>

        <div className="exception-detail-grid">
          <Card title="Evidence timeline">
            <Timeline
              items={detail.occurrences.map((item) => ({
                color: 'blue',
                content: (
                  <Space orientation="vertical" size={2}>
                    <Typography.Text>{label(item.eventType)}</Typography.Text>
                    <Typography.Text type="secondary">
                      {new Date(item.detectedAt).toLocaleString()}
                    </Typography.Text>
                    <pre className="safe-evidence">{JSON.stringify(item.evidence, null, 2)}</pre>
                  </Space>
                ),
              }))}
            />
          </Card>
          <Card title="Case history">
            <Timeline
              items={detail.history.map((item) => ({
                color:
                  item.newStatus === 'RESOLVED' || item.newStatus === 'CLOSED' ? 'green' : 'gray',
                content: (
                  <Space orientation="vertical" size={2}>
                    <Typography.Text>
                      {label(item.action)} → {label(item.newStatus)}
                    </Typography.Text>
                    <Typography.Text type="secondary">
                      {item.safeReason ?? item.reasonCode ?? 'Recorded transition'} ·{' '}
                      {new Date(item.occurredAt).toLocaleString()}
                    </Typography.Text>
                  </Space>
                ),
              }))}
            />
          </Card>
        </div>

        <Card title="Recovery attempts">
          {detail.recoveries.length === 0 ? (
            <Typography.Text type="secondary">No recovery has been requested.</Typography.Text>
          ) : (
            <Timeline
              items={detail.recoveries.map((item) => ({
                color:
                  item.outcome?.status === 'SUCCEEDED'
                    ? 'green'
                    : item.outcome?.status === 'FAILED'
                      ? 'red'
                      : 'blue',
                content: `${label(item.action)} · ${item.outcome?.safeResult ?? 'In progress'}`,
              }))}
            />
          )}
        </Card>
      </Space>

      <Modal
        open={dialog !== undefined}
        title={dialog ? label(dialog) : 'Update exception'}
        okText="Confirm"
        okButtonProps={{
          loading: command.isPending,
          disabled:
            reason.trim().length < 5 ||
            (dialog === 'RECOVERY' && !recoveryAction) ||
            (dialog === 'CLOSE' && closeReasonCode === 'DUPLICATE' && !primaryCaseId.trim()),
        }}
        onCancel={() => setDialog(undefined)}
        onOk={() => dialog && command.mutate(dialog)}
      >
        <Space orientation="vertical" size="middle" className="exception-dialog-fields">
          {dialog === 'RECOVERY' ? (
            <label>
              <Typography.Text>Recovery action</Typography.Text>
              <Select
                aria-label="Recovery action"
                value={recoveryAction}
                onChange={setRecoveryAction}
                options={detail.allowedRecoveryActions.map((item) => ({
                  value: item,
                  label: label(item),
                }))}
              />
            </label>
          ) : null}
          {dialog === 'CLOSE' ? (
            <label>
              <Typography.Text>Review outcome</Typography.Text>
              <Select
                aria-label="Closure outcome"
                value={closeReasonCode}
                onChange={setCloseReasonCode}
                options={closureOptions}
              />
            </label>
          ) : null}
          {dialog === 'CLOSE' && closeReasonCode === 'DUPLICATE' ? (
            <label>
              <Typography.Text>Primary case ID</Typography.Text>
              <Input
                aria-label="Primary case ID"
                value={primaryCaseId}
                onChange={(event) => setPrimaryCaseId(event.target.value)}
                placeholder="UUID of the retained case"
              />
            </label>
          ) : null}
          <label>
            <Typography.Text>Operational reason</Typography.Text>
            <Input.TextArea
              aria-label="Operational reason"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              maxLength={500}
              showCount
            />
          </label>
          {command.isError ? (
            <Alert
              type="error"
              showIcon
              title={
                command.error instanceof Error
                  ? command.error.message
                  : 'The command could not be completed.'
              }
            />
          ) : null}
        </Space>
      </Modal>
    </article>
  );
}
