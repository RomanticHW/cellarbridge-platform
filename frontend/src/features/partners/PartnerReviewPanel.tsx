import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { Alert, Button, Card, Col, Form, Input, Row, Select, Space } from 'antd';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import {
  PartnerApiError,
  reviewPartner,
  type PartnerReviewRequest,
  type Versioned,
  type PartnerCommandResult,
} from '../../api/partners';
import { useAuthSession } from '../identity-access/authSession';
import { routeCodes } from './partnerForm';

const reviewSchema = z.object({
  decision: z.enum(['APPROVE', 'REQUEST_CHANGES', 'REJECT']),
  reason: z.string().trim().min(5, 'Explain the review decision').max(500),
  approvedPaymentTermDays: z
    .string()
    .trim()
    .refine(
      (value) => value === '' || (/^[0-9]+$/.test(value) && Number(value) <= 180),
      'Enter a whole number from 0 to 180',
    ),
  approvedCreditLimitAmount: z
    .string()
    .trim()
    .refine(
      (value) => value === '' || /^[0-9]+(\.[0-9]{1,4})?$/.test(value),
      'Enter a valid amount',
    ),
  approvedCreditLimitCurrency: z.string().trim(),
  approvedRouteCodes: z.array(z.enum(routeCodes)),
  approvedServiceRegions: z.array(z.string().trim().min(1).max(80)),
  approvedCurrencies: z.array(z.string().regex(/^[A-Z]{3}$/)),
});

type ReviewValues = z.infer<typeof reviewSchema>;

function toRequest(values: ReviewValues): PartnerReviewRequest {
  const credit = values.approvedCreditLimitAmount;
  return {
    decision: values.decision,
    reason: values.reason,
    approvedPaymentTermDays:
      values.approvedPaymentTermDays === '' ? undefined : Number(values.approvedPaymentTermDays),
    approvedCreditLimit:
      credit === ''
        ? undefined
        : { amount: credit, currency: values.approvedCreditLimitCurrency || 'CNY' },
    approvedRouteCodes: values.approvedRouteCodes,
    approvedServiceRegions: values.approvedServiceRegions,
    approvedCurrencies: values.approvedCurrencies,
  };
}

interface PartnerReviewPanelProps {
  partnerId: string;
  etag: string;
  onCompleted: (result: Versioned<PartnerCommandResult>) => void;
  onReload: () => void;
}

export function PartnerReviewPanel({
  partnerId,
  etag,
  onCompleted,
  onReload,
}: PartnerReviewPanelProps) {
  const session = useAuthSession();
  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<ReviewValues>({
    resolver: zodResolver(reviewSchema),
    defaultValues: {
      decision: 'APPROVE',
      reason: '',
      approvedPaymentTermDays: '',
      approvedCreditLimitAmount: '',
      approvedCreditLimitCurrency: 'CNY',
      approvedRouteCodes: [],
      approvedServiceRegions: [],
      approvedCurrencies: [],
    },
  });
  const decision = useWatch({ control, name: 'decision' });
  const mutation = useMutation({
    mutationFn: (values: ReviewValues) =>
      reviewPartner(session.accessToken ?? '', partnerId, etag, toRequest(values)),
    onSuccess: onCompleted,
  });
  const problem = mutation.error instanceof PartnerApiError ? mutation.error : undefined;

  return (
    <Card title="Independent review" className="partner-review-card">
      <Space orientation="vertical" size="middle" className="partner-page-stack">
        {problem !== undefined && (
          <Alert
            showIcon
            type="error"
            title={
              problem.code === 'PARTNER_REVIEWER_CONFLICT'
                ? 'Submitter cannot review this partner'
                : problem.status === 412
                  ? 'Partner version changed'
                  : 'Review was not recorded'
            }
            description={`${problem.message} (${problem.code})`}
            action={
              problem.status === 412 ? (
                <Button size="small" onClick={onReload}>
                  Reload
                </Button>
              ) : undefined
            }
          />
        )}
        <form onSubmit={(event) => void handleSubmit((values) => mutation.mutate(values))(event)}>
          <Row gutter={16}>
            <Col xs={24} md={8}>
              <Form.Item label="Decision" required>
                <Controller
                  control={control}
                  name="decision"
                  render={({ field }) => (
                    <Select
                      {...field}
                      options={[
                        { value: 'APPROVE', label: 'Approve' },
                        { value: 'REQUEST_CHANGES', label: 'Request changes' },
                        { value: 'REJECT', label: 'Reject' },
                      ]}
                    />
                  )}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={16}>
              <Form.Item
                label="Reason"
                htmlFor="partner-review-reason"
                required
                validateStatus={errors.reason ? 'error' : undefined}
                help={errors.reason?.message}
              >
                <Controller
                  control={control}
                  name="reason"
                  render={({ field }) => <Input id="partner-review-reason" {...field} />}
                />
              </Form.Item>
            </Col>
            {decision === 'APPROVE' && (
              <>
                <Col xs={24} md={8}>
                  <Form.Item
                    label="Approved payment term"
                    htmlFor="partner-approved-payment-term"
                    validateStatus={errors.approvedPaymentTermDays ? 'error' : undefined}
                    help={errors.approvedPaymentTermDays?.message}
                  >
                    <Controller
                      control={control}
                      name="approvedPaymentTermDays"
                      render={({ field }) => (
                        <Input {...field} id="partner-approved-payment-term" inputMode="numeric" />
                      )}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} md={8}>
                  <Form.Item
                    label="Credit limit"
                    htmlFor="partner-approved-credit-limit"
                    validateStatus={errors.approvedCreditLimitAmount ? 'error' : undefined}
                    help={errors.approvedCreditLimitAmount?.message}
                  >
                    <Controller
                      control={control}
                      name="approvedCreditLimitAmount"
                      render={({ field }) => (
                        <Input {...field} id="partner-approved-credit-limit" inputMode="decimal" />
                      )}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} md={8}>
                  <Form.Item label="Credit currency" htmlFor="partner-approved-credit-currency">
                    <Controller
                      control={control}
                      name="approvedCreditLimitCurrency"
                      render={({ field }) => (
                        <Input {...field} id="partner-approved-credit-currency" maxLength={3} />
                      )}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24}>
                  <Form.Item
                    label="Approved routes"
                    extra="Leave blank to approve the requested routes."
                  >
                    <Controller
                      control={control}
                      name="approvedRouteCodes"
                      render={({ field }) => (
                        <Select
                          {...field}
                          mode="multiple"
                          options={routeCodes.map((value) => ({ value, label: value }))}
                        />
                      )}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item
                    label="Approved regions"
                    extra="Leave blank to approve requested regions."
                  >
                    <Controller
                      control={control}
                      name="approvedServiceRegions"
                      render={({ field }) => (
                        <Select {...field} mode="tags" tokenSeparators={[',']} />
                      )}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item
                    label="Approved currencies"
                    extra="Leave blank to approve requested currencies."
                  >
                    <Controller
                      control={control}
                      name="approvedCurrencies"
                      render={({ field }) => (
                        <Select {...field} mode="tags" tokenSeparators={[',']} />
                      )}
                    />
                  </Form.Item>
                </Col>
              </>
            )}
          </Row>
          <Button type="primary" htmlType="submit" loading={mutation.isPending}>
            Record review decision
          </Button>
        </form>
      </Space>
    </Card>
  );
}
