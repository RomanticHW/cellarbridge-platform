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
  Space,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import { z } from 'zod';
import {
  getPartner,
  PartnerApiError,
  requestPartnerReactivation,
  submitPartner,
  suspendPartner,
  type PartnerCommandResult,
  type PartnerReasonRequest,
  type Versioned,
} from '../../api/partners';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';
import { PartnerReviewPanel } from './PartnerReviewPanel';

const reasonSchema = z.object({ reason: z.string().trim().min(5).max(500) });
type ReasonValues = z.infer<typeof reasonSchema>;

const statusColors: Record<string, string> = {
  DRAFT: 'default',
  PENDING_REVIEW: 'processing',
  CHANGES_REQUESTED: 'warning',
  ACTIVE: 'success',
  SUSPENDED: 'error',
  REJECTED: 'error',
};

interface ReasonActionProps {
  label: string;
  title: string;
  danger?: boolean;
  action: (request: PartnerReasonRequest) => Promise<Versioned<PartnerCommandResult>>;
  onCompleted: (result: Versioned<PartnerCommandResult>) => void;
}

function ReasonAction({ label, title, danger, action, onCompleted }: ReasonActionProps) {
  const [open, setOpen] = useState(false);
  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<ReasonValues>({ resolver: zodResolver(reasonSchema), defaultValues: { reason: '' } });
  const mutation = useMutation({
    mutationFn: action,
    onSuccess: (result) => {
      setOpen(false);
      reset();
      onCompleted(result);
    },
  });
  const problem = mutation.error instanceof PartnerApiError ? mutation.error : undefined;

  return (
    <>
      <Button danger={danger} onClick={() => setOpen(true)}>
        {label}
      </Button>
      <Modal
        open={open}
        title={title}
        okText={label}
        okButtonProps={{ danger, loading: mutation.isPending }}
        onOk={() => void handleSubmit((values) => mutation.mutate(values))()}
        onCancel={() => setOpen(false)}
      >
        <form onSubmit={(event) => void handleSubmit((values) => mutation.mutate(values))(event)}>
          {problem !== undefined && (
            <Alert
              showIcon
              type="error"
              title="Action could not be completed"
              description={`${problem.message} (${problem.code})`}
            />
          )}
          <Form.Item
            label="Reason"
            htmlFor="partner-action-reason"
            required
            validateStatus={errors.reason ? 'error' : undefined}
            help={errors.reason?.message}
          >
            <Controller
              control={control}
              name="reason"
              render={({ field }) => (
                <Input.TextArea {...field} id="partner-action-reason" rows={3} />
              )}
            />
          </Form.Item>
        </form>
      </Modal>
    </>
  );
}

export function PartnerDetailPage() {
  const { partnerId = '' } = useParams();
  const session = useAuthSession();
  const queryClient = useQueryClient();
  const accessToken = session.accessToken ?? '';
  const partnerQuery = useQuery({
    queryKey: ['partners', partnerId],
    queryFn: ({ signal }) => getPartner(accessToken, partnerId, signal),
    enabled: accessToken !== '' && partnerId !== '',
  });
  const submitMutation = useMutation({
    mutationFn: () => {
      if (partnerQuery.data === undefined) throw new Error('Partner detail is unavailable');
      return submitPartner(accessToken, partnerId, partnerQuery.data.etag);
    },
    onSuccess: () => void refresh(),
  });

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: ['partners'] });
    await partnerQuery.refetch();
  }

  if (partnerQuery.isPending) return <LoadingState message="Loading partner detail…" />;
  if (partnerQuery.isError) {
    const problem = partnerQuery.error instanceof PartnerApiError ? partnerQuery.error : undefined;
    return (
      <ErrorState
        title={
          problem?.status === 404
            ? 'Partner not found'
            : problem?.status === 403
              ? 'Partner access denied'
              : 'Partner detail unavailable'
        }
        description={
          problem === undefined
            ? 'The partner is not visible in the current tenant scope, or the service is unavailable.'
            : `${problem.message} (${problem.code})`
        }
        actionLabel="Try again"
        onRetry={() => void partnerQuery.refetch()}
      />
    );
  }

  const { data: partner, etag } = partnerQuery.data;
  const primaryContact = partner.contacts[0];
  const primaryContactLabel =
    [primaryContact?.name, primaryContact?.email]
      .filter((value): value is string => typeof value === 'string' && value !== '')
      .join(' · ') || 'Not provided';
  const billingAddressLabel =
    [
      partner.billingAddress?.line1,
      partner.billingAddress?.city,
      partner.billingAddress?.province,
      partner.billingAddress?.countryCode,
    ]
      .filter((value): value is string => typeof value === 'string' && value !== '')
      .join(', ') || 'Not provided';
  const submitProblem =
    submitMutation.error instanceof PartnerApiError ? submitMutation.error : undefined;
  const actionCompleted = () => void refresh();

  return (
    <section className="partner-page" aria-labelledby="partner-detail-title">
      <Space orientation="vertical" size="large" className="partner-page-stack">
        <div className="partner-page-heading">
          <div>
            <Space align="center">
              <Typography.Title id="partner-detail-title" level={2}>
                {partner.displayName ?? partner.number}
              </Typography.Title>
              <Tag color={statusColors[partner.status]}>{partner.status.replaceAll('_', ' ')}</Tag>
            </Space>
            <Typography.Text type="secondary">
              {partner.number} · {partner.legalName ?? 'Legal name not provided'}
            </Typography.Text>
          </div>
          <Space wrap>
            <Link to="/app/partners">Back to partners</Link>
            {partner.allowedActions.includes('EDIT') && (
              <Link to={`/app/partners/${partner.id}/edit`}>Edit draft</Link>
            )}
            {partner.allowedActions.includes('SUBMIT') && (
              <Button
                type="primary"
                loading={submitMutation.isPending}
                onClick={() => submitMutation.mutate()}
              >
                Submit for review
              </Button>
            )}
            {partner.allowedActions.includes('SUSPEND') && (
              <ReasonAction
                danger
                label="Suspend partner"
                title="Suspend active partner"
                action={(request) => suspendPartner(accessToken, partner.id, etag, request)}
                onCompleted={actionCompleted}
              />
            )}
            {partner.allowedActions.includes('REQUEST_REACTIVATION') && (
              <ReasonAction
                label="Request reactivation"
                title="Request partner reactivation"
                action={(request) =>
                  requestPartnerReactivation(accessToken, partner.id, etag, request)
                }
                onCompleted={actionCompleted}
              />
            )}
          </Space>
        </div>

        {submitProblem !== undefined && (
          <Alert
            showIcon
            type={submitProblem.status === 412 ? 'warning' : 'error'}
            title={
              submitProblem.code === 'PARTNER_PROFILE_INCOMPLETE'
                ? 'Complete the partner profile before submission'
                : submitProblem.code === 'PARTNER_POTENTIAL_DUPLICATE'
                  ? 'Document how this partner differs'
                  : 'Partner was not submitted'
            }
            description={
              submitProblem.errors.length > 0
                ? `Required fields: ${submitProblem.errors.map((error) => error.field).join(', ')} (${submitProblem.code})`
                : `${submitProblem.message} (${submitProblem.code})`
            }
            action={
              submitProblem.status === 412 ? (
                <Button size="small" onClick={() => void partnerQuery.refetch()}>
                  Reload
                </Button>
              ) : undefined
            }
          />
        )}
        {partner.duplicateWarnings.map((warning) => (
          <Alert
            key={warning}
            showIcon
            type="warning"
            title="Potential duplicate"
            description={warning}
          />
        ))}

        <Card title="Partner profile">
          <Descriptions
            column={{ xs: 1, md: 2, lg: 3 }}
            items={[
              {
                key: 'type',
                label: 'Partner type',
                children: partner.type?.replaceAll('_', ' ') ?? 'Not provided',
              },
              {
                key: 'registration',
                label: 'Registration identifier',
                children: partner.registrationIdentifierMasked ?? 'Not provided',
              },
              {
                key: 'currency',
                label: 'Default currency',
                children: partner.defaultCurrency ?? 'Not provided',
              },
              {
                key: 'term',
                label: 'Payment term',
                children:
                  partner.paymentTermDays === null || partner.paymentTermDays === undefined
                    ? 'Not provided'
                    : `${partner.paymentTermDays} days`,
              },
              {
                key: 'routes',
                label: 'Routes',
                children: partner.routeEligibility.join(', ') || 'Not provided',
              },
              {
                key: 'regions',
                label: 'Service regions',
                children: partner.requestedServiceRegions.join(', ') || 'Not provided',
              },
              {
                key: 'currencies',
                label: 'Requested currencies',
                children: partner.requestedCurrencies.join(', ') || 'Not provided',
              },
              {
                key: 'contact',
                label: 'Primary contact',
                children: primaryContactLabel,
              },
              {
                key: 'address',
                label: 'Billing address',
                children: billingAddressLabel,
              },
              {
                key: 'updated',
                label: 'Last updated',
                children: new Date(partner.updatedAt).toLocaleString(),
              },
            ]}
          />
        </Card>

        {partner.eligibility !== null && partner.eligibility !== undefined && (
          <Card title={`Approved eligibility · version ${partner.eligibility.version}`}>
            <Descriptions
              column={{ xs: 1, md: 2 }}
              items={[
                {
                  key: 'routes',
                  label: 'Approved routes',
                  children: partner.eligibility.routeCodes.join(', '),
                },
                {
                  key: 'regions',
                  label: 'Approved regions',
                  children: partner.eligibility.serviceRegions.join(', '),
                },
                {
                  key: 'currencies',
                  label: 'Approved currencies',
                  children: partner.eligibility.currencies.join(', '),
                },
                {
                  key: 'credit',
                  label: 'Credit limit',
                  children: partner.eligibility.creditLimit
                    ? `${partner.eligibility.creditLimit.amount} ${partner.eligibility.creditLimit.currency}`
                    : 'Not set',
                },
              ]}
            />
          </Card>
        )}

        {partner.allowedActions.includes('REVIEW') && (
          <PartnerReviewPanel
            partnerId={partner.id}
            etag={etag}
            onCompleted={actionCompleted}
            onReload={() => void partnerQuery.refetch()}
          />
        )}

        <Card title="Audit timeline">
          {partner.timeline.length === 0 ? (
            <Typography.Text type="secondary">
              No partner changes have been recorded.
            </Typography.Text>
          ) : (
            <Timeline
              items={partner.timeline.map((entry) => ({
                content: (
                  <div>
                    <Typography.Text strong>{entry.action.replaceAll('_', ' ')}</Typography.Text>
                    <div>
                      <Typography.Text type="secondary">
                        {new Date(entry.occurredAt).toLocaleString()}
                        {entry.previousState && entry.newState
                          ? ` · ${entry.previousState} → ${entry.newState}`
                          : ''}
                      </Typography.Text>
                    </div>
                    {entry.safeReason && (
                      <Typography.Paragraph>{entry.safeReason}</Typography.Paragraph>
                    )}
                    {entry.changedFields.length > 0 && (
                      <Typography.Text type="secondary">
                        Changed: {entry.changedFields.join(', ')}
                      </Typography.Text>
                    )}
                  </div>
                ),
              }))}
            />
          )}
        </Card>
      </Space>
    </section>
  );
}
