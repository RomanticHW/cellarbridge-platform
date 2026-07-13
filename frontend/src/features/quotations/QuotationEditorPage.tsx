import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Col, Form, Input, Modal, Row, Select, Space, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { Controller, useFieldArray, useForm, useWatch } from 'react-hook-form';
import {
  Link,
  Navigate,
  useBlocker,
  useNavigate,
  useParams,
  useSearchParams,
} from 'react-router-dom';
import { searchCatalog } from '../../api/catalog';
import { useCurrentUser } from '../../api/currentUser';
import { listPartners } from '../../api/partners';
import {
  createQuotation,
  getQuotation,
  QuotationApiError,
  updateQuotation,
} from '../../api/quotations';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';
import {
  emptyQuotationForm,
  formFromQuotation,
  quotationFormSchema,
  toQuotationRequest,
  type QuotationFormValues,
} from './quotationForm';

export function QuotationEditorPage() {
  const { quotationId } = useParams();
  const [searchParams] = useSearchParams();
  const editing = quotationId !== undefined;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [savedDestination, setSavedDestination] = useState<string>();
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const currentUser = useCurrentUser(accessToken, session.sessionIdentity);
  const selectedLines: QuotationFormValues['lines'] =
    searchParams
      .get('skuIds')
      ?.split(',')
      .filter(Boolean)
      .map((skuId) => ({
        skuId,
        quantity: '1',
        unit: 'CASE',
        discountRate: '0',
        manualUnitPrice: '',
      })) ?? emptyQuotationForm.lines;
  const quotationQuery = useQuery({
    queryKey: ['quotations', quotationId],
    queryFn: ({ signal }) => getQuotation(accessToken, quotationId ?? '', signal),
    enabled: editing && accessToken !== '',
  });
  const partners = useQuery({
    queryKey: ['partners', 'quotation-options'],
    queryFn: ({ signal }) =>
      listPartners(accessToken, { status: ['ACTIVE'], pageSize: 100 }, signal),
    enabled: accessToken !== '',
  });
  const catalog = useQuery({
    queryKey: ['catalog', 'quotation-options'],
    queryFn: ({ signal }) => searchCatalog(accessToken, { pageSize: 100, sort: 'name' }, signal),
    enabled: accessToken !== '',
  });
  const {
    control,
    handleSubmit,
    reset,
    formState: { errors, isDirty },
  } = useForm<QuotationFormValues>({
    resolver: zodResolver(quotationFormSchema),
    defaultValues: {
      ...emptyQuotationForm,
      lines: selectedLines.length === 0 ? emptyQuotationForm.lines : selectedLines,
    },
  });
  const { fields, append, remove } = useFieldArray({ control, name: 'lines' });
  const currency = useWatch({ control, name: 'currency' });

  useEffect(() => {
    if (quotationQuery.data !== undefined) reset(formFromQuotation(quotationQuery.data.data));
  }, [quotationQuery.data, reset]);

  const skuOptions = useMemo(
    () =>
      catalog.data?.items.map((item) => ({
        value: item.sku.skuId,
        label: `${item.sku.displayName} · ${item.sku.skuCode}`,
      })) ?? [],
    [catalog.data],
  );
  const mutation = useMutation({
    mutationFn: async (values: QuotationFormValues) => {
      if (editing) {
        if (quotationQuery.data === undefined) throw new Error('Quotation detail is unavailable');
        return updateQuotation(
          accessToken,
          quotationQuery.data.data.id,
          quotationQuery.data.etag,
          toQuotationRequest(values),
        );
      }
      return createQuotation(accessToken, toQuotationRequest(values));
    },
    onSuccess: async (result, values) => {
      reset(values);
      await queryClient.invalidateQueries({ queryKey: ['quotations'] });
      setSavedDestination(`/app/quotations/${result.data.id}`);
    },
  });

  const blocker = useBlocker(isDirty && !mutation.isPending && savedDestination === undefined);
  useEffect(() => {
    if (savedDestination !== undefined) void navigate(savedDestination);
  }, [navigate, savedDestination]);
  useEffect(() => {
    if (blocker.state !== 'blocked') return;
    Modal.confirm({
      title: 'Discard unsaved quotation changes?',
      content: 'Pricing inputs on this page have not been saved.',
      okText: 'Discard changes',
      okButtonProps: { danger: true },
      onOk: () => blocker.proceed(),
      onCancel: () => blocker.reset(),
    });
  }, [blocker]);
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (isDirty) event.preventDefault();
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [isDirty]);

  if ((editing && quotationQuery.isPending) || partners.isPending || catalog.isPending) {
    return <LoadingState message="Loading quotation inputs…" />;
  }
  if (currentUser.isPending) return <LoadingState message="Checking quotation permissions…" />;
  if (!currentUser.data?.permissions.includes('quotation:create'))
    return <Navigate to="/forbidden" replace />;
  if (editing && quotationQuery.isError) {
    const problem =
      quotationQuery.error instanceof QuotationApiError ? quotationQuery.error : undefined;
    return (
      <ErrorState
        title="Quotation draft unavailable"
        description={
          problem ? `${problem.message} (${problem.code})` : 'The draft could not be loaded.'
        }
        actionLabel="Try again"
        onRetry={() => void quotationQuery.refetch()}
      />
    );
  }
  if (editing && !quotationQuery.data?.data.allowedActions.includes('EDIT'))
    return <Navigate to="/forbidden" replace />;
  if (partners.isError || catalog.isError) {
    return (
      <ErrorState
        title="Quotation inputs unavailable"
        description="Active partner or catalog options could not be loaded."
        actionLabel="Try again"
        onRetry={() => {
          void partners.refetch();
          void catalog.refetch();
        }}
      />
    );
  }

  const problem = mutation.error instanceof QuotationApiError ? mutation.error : undefined;
  return (
    <section className="quotation-page" aria-labelledby="quotation-editor-title">
      <Space orientation="vertical" size="large" className="quotation-page-stack">
        <div>
          <Typography.Title id="quotation-editor-title" level={2}>
            {editing ? 'Edit quotation draft' : 'Create quotation draft'}
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            Saving prices a revision against immutable partner, SKU, and price references. Route
            evaluation is a separate explicit action.
          </Typography.Paragraph>
        </div>
        {editing && quotationQuery.data?.data.status === 'CHANGES_REQUESTED' && (
          <Alert
            showIcon
            type="warning"
            title="Saving creates a new revision"
            description="The submitted revision remains frozen for audit. Your changes become a new draft revision."
          />
        )}
        {problem && (
          <Alert
            showIcon
            type={problem.status === 412 ? 'warning' : 'error'}
            title={
              problem.status === 412
                ? 'This quotation changed after you opened it'
                : 'Quotation was not saved'
            }
            description={`${problem.message} (${problem.code})`}
            action={
              problem.status === 412 ? (
                <Button size="small" onClick={() => void quotationQuery.refetch()}>
                  Reload current version
                </Button>
              ) : undefined
            }
          />
        )}
        <form onSubmit={(event) => void handleSubmit((values) => mutation.mutate(values))(event)}>
          <Space orientation="vertical" size="large" className="quotation-page-stack">
            <Card title="Customer and terms">
              <Row gutter={16}>
                <Col xs={24} lg={12}>
                  <Form.Item
                    label="Active customer"
                    required
                    validateStatus={errors.partnerId ? 'error' : undefined}
                    help={errors.partnerId?.message}
                  >
                    <Controller
                      control={control}
                      name="partnerId"
                      render={({ field }) => (
                        <Select
                          {...field}
                          aria-label="Active customer"
                          showSearch
                          optionFilterProp="label"
                          options={partners.data?.items.map((partner) => ({
                            value: partner.id,
                            label: `${partner.displayName ?? partner.legalName} · ${partner.number}`,
                          }))}
                        />
                      )}
                    />
                  </Form.Item>
                </Col>
                <Col xs={12} lg={4}>
                  <Form.Item
                    label="Currency"
                    required
                    validateStatus={errors.currency ? 'error' : undefined}
                    help={errors.currency?.message}
                  >
                    <Controller
                      control={control}
                      name="currency"
                      render={({ field }) => (
                        <Input {...field} aria-label="Currency" maxLength={3} />
                      )}
                    />
                  </Form.Item>
                </Col>
                <Col xs={12} lg={4}>
                  <Form.Item
                    label="Payment term (days)"
                    required
                    validateStatus={errors.paymentTermDays ? 'error' : undefined}
                    help={errors.paymentTermDays?.message}
                  >
                    <Controller
                      control={control}
                      name="paymentTermDays"
                      render={({ field }) => (
                        <Input
                          {...field}
                          aria-label="Payment term (days)"
                          type="number"
                          min={0}
                          max={180}
                          onChange={(event) => field.onChange(Number(event.target.value))}
                        />
                      )}
                    />
                  </Form.Item>
                </Col>
                <Col xs={12} lg={4}>
                  <Form.Item
                    label="Delivery date"
                    required
                    validateStatus={errors.requestedDeliveryDate ? 'error' : undefined}
                    help={errors.requestedDeliveryDate?.message}
                  >
                    <Controller
                      control={control}
                      name="requestedDeliveryDate"
                      render={({ field }) => (
                        <Input {...field} aria-label="Delivery date" type="date" />
                      )}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} lg={8}>
                  <Form.Item
                    label="Expires at"
                    required
                    validateStatus={errors.expiresAt ? 'error' : undefined}
                    help={errors.expiresAt?.message}
                  >
                    <Controller
                      control={control}
                      name="expiresAt"
                      render={({ field }) => (
                        <Input {...field} aria-label="Expires at" type="datetime-local" />
                      )}
                    />
                  </Form.Item>
                </Col>
              </Row>
            </Card>
            <Card title="Delivery address">
              <Row gutter={16}>
                {(
                  [
                    ['countryCode', 'Country code', 4],
                    ['province', 'Province', 5],
                    ['city', 'City', 5],
                    ['district', 'District', 5],
                    ['postalCode', 'Postal code', 5],
                    ['line1', 'Address line', 24],
                  ] as const
                ).map(([name, label, span]) => (
                  <Col xs={24} lg={span} key={name}>
                    <Form.Item
                      label={label}
                      required={!['district', 'postalCode'].includes(name)}
                      validateStatus={errors[name] ? 'error' : undefined}
                      help={errors[name]?.message}
                    >
                      <Controller
                        control={control}
                        name={name}
                        render={({ field }) => <Input {...field} aria-label={label} />}
                      />
                    </Form.Item>
                  </Col>
                ))}
              </Row>
            </Card>
            <Card
              title="Quotation lines"
              extra={
                <Button
                  onClick={() =>
                    append({
                      skuId: '',
                      quantity: '1',
                      unit: 'CASE',
                      discountRate: '0',
                      manualUnitPrice: '',
                    })
                  }
                >
                  Add line
                </Button>
              }
            >
              <Space orientation="vertical" size="middle" className="quotation-page-stack">
                {fields.map((field, index) => (
                  <div className="quotation-line-editor" key={field.id}>
                    <Form.Item
                      label="SKU"
                      required
                      validateStatus={errors.lines?.[index]?.skuId ? 'error' : undefined}
                      help={errors.lines?.[index]?.skuId?.message}
                    >
                      <Controller
                        control={control}
                        name={`lines.${index}.skuId`}
                        render={({ field: input }) => (
                          <Select
                            {...input}
                            aria-label={`Line ${index + 1} SKU`}
                            showSearch
                            optionFilterProp="label"
                            options={skuOptions}
                          />
                        )}
                      />
                    </Form.Item>
                    <Form.Item
                      label="Quantity"
                      required
                      validateStatus={errors.lines?.[index]?.quantity ? 'error' : undefined}
                      help={errors.lines?.[index]?.quantity?.message}
                    >
                      <Controller
                        control={control}
                        name={`lines.${index}.quantity`}
                        render={({ field: input }) => (
                          <Input {...input} aria-label={`Line ${index + 1} quantity`} />
                        )}
                      />
                    </Form.Item>
                    <Form.Item label="Unit" required>
                      <Controller
                        control={control}
                        name={`lines.${index}.unit`}
                        render={({ field: input }) => (
                          <Select
                            {...input}
                            aria-label={`Line ${index + 1} unit`}
                            options={[
                              { value: 'CASE', label: 'Case' },
                              { value: 'BOTTLE', label: 'Bottle' },
                            ]}
                          />
                        )}
                      />
                    </Form.Item>
                    <Form.Item
                      label="Discount rate"
                      required
                      validateStatus={errors.lines?.[index]?.discountRate ? 'error' : undefined}
                      help={errors.lines?.[index]?.discountRate?.message}
                    >
                      <Controller
                        control={control}
                        name={`lines.${index}.discountRate`}
                        render={({ field: input }) => (
                          <Input
                            {...input}
                            aria-label={`Line ${index + 1} discount rate`}
                            placeholder="0.0500"
                          />
                        )}
                      />
                    </Form.Item>
                    <Form.Item
                      label={`Manual unit price (${currency})`}
                      validateStatus={errors.lines?.[index]?.manualUnitPrice ? 'error' : undefined}
                      help={errors.lines?.[index]?.manualUnitPrice?.message}
                    >
                      <Controller
                        control={control}
                        name={`lines.${index}.manualUnitPrice`}
                        render={({ field: input }) => (
                          <Input
                            {...input}
                            aria-label={`Line ${index + 1} manual unit price`}
                            placeholder="Use current list price"
                          />
                        )}
                      />
                    </Form.Item>
                    <Button danger disabled={fields.length === 1} onClick={() => remove(index)}>
                      Remove
                    </Button>
                  </div>
                ))}
                {errors.lines?.root?.message && (
                  <Typography.Text type="danger">{errors.lines.root.message}</Typography.Text>
                )}
              </Space>
            </Card>
            <Space>
              <Button type="primary" htmlType="submit" loading={mutation.isPending}>
                Save quotation draft
              </Button>
              <Link to={editing ? `/app/quotations/${quotationId}` : '/app/quotations'}>
                Cancel
              </Link>
            </Space>
          </Space>
        </form>
      </Space>
    </section>
  );
}
