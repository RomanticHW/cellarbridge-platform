import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Col,
  Form,
  Input,
  Modal,
  Row,
  Select,
  Space,
  Typography,
  type InputProps,
} from 'antd';
import { useEffect, useState } from 'react';
import { Controller, useForm, type Control } from 'react-hook-form';
import { Link, Navigate, useBlocker, useNavigate, useParams } from 'react-router-dom';
import { useCurrentUser } from '../../api/currentUser';
import { createPartner, getPartner, PartnerApiError, updatePartner } from '../../api/partners';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';
import {
  emptyPartnerForm,
  formFromPartner,
  partnerFormSchema,
  partnerTypes,
  routeCodes,
  toCreateRequest,
  toUpdateRequest,
  type PartnerFormValues,
} from './partnerForm';

const partnerTypeOptions = partnerTypes.map((value) => ({
  value,
  label: value.replaceAll('_', ' ').toLowerCase(),
}));
const routeOptions = routeCodes.map((value) => ({ value, label: value.replaceAll('_', ' ') }));

type TextFieldName = {
  [Key in keyof PartnerFormValues]: PartnerFormValues[Key] extends string ? Key : never;
}[keyof PartnerFormValues];

function ControlledInput({
  control,
  name,
  ...props
}: InputProps & { control: Control<PartnerFormValues>; name: TextFieldName }) {
  return (
    <Controller
      control={control}
      name={name}
      render={({ field }) => <Input {...props} {...field} />}
    />
  );
}

export function PartnerEditorPage() {
  const { partnerId } = useParams();
  const editing = partnerId !== undefined;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [savedDestination, setSavedDestination] = useState<string>();
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const currentUser = useCurrentUser(session.accessToken, session.sessionIdentity);
  const partnerQuery = useQuery({
    queryKey: ['partners', partnerId],
    queryFn: ({ signal }) => getPartner(accessToken, partnerId ?? '', signal),
    enabled: editing && accessToken !== '',
  });
  const {
    control,
    handleSubmit,
    reset,
    formState: { errors, isDirty },
  } = useForm<PartnerFormValues>({
    resolver: zodResolver(partnerFormSchema),
    defaultValues: emptyPartnerForm,
  });

  useEffect(() => {
    if (partnerQuery.data !== undefined) reset(formFromPartner(partnerQuery.data.data));
  }, [partnerQuery.data, reset]);

  const mutation = useMutation({
    mutationFn: async (values: PartnerFormValues) => {
      if (editing) {
        const current = partnerQuery.data;
        if (current === undefined) throw new Error('Partner detail is unavailable');
        return updatePartner(accessToken, current.data.id, current.etag, toUpdateRequest(values));
      }
      return createPartner(accessToken, toCreateRequest(values));
    },
    onSuccess: async (result, values) => {
      reset(values);
      await queryClient.invalidateQueries({ queryKey: ['partners'] });
      setSavedDestination(`/app/partners/${result.data.id}`);
    },
  });

  const blocker = useBlocker(isDirty && !mutation.isPending && savedDestination === undefined);
  useEffect(() => {
    if (savedDestination !== undefined) void navigate(savedDestination);
  }, [navigate, savedDestination]);
  useEffect(() => {
    if (blocker.state !== 'blocked') return;
    Modal.confirm({
      title: 'Discard unsaved partner changes?',
      content: 'The fields on this page have not been saved.',
      okText: 'Discard changes',
      okButtonProps: { danger: true },
      onOk: () => blocker.proceed(),
      onCancel: () => blocker.reset(),
    });
  }, [blocker]);

  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (!isDirty) return;
      event.preventDefault();
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [isDirty]);

  if (editing && partnerQuery.isPending) return <LoadingState message="Loading partner profile…" />;
  if (currentUser.isPending) return <LoadingState message="Checking partner permissions…" />;
  if (!currentUser.data?.permissions.includes('partner:create')) {
    return <Navigate to="/forbidden" replace />;
  }
  if (editing && partnerQuery.isError) {
    const problem = partnerQuery.error instanceof PartnerApiError ? partnerQuery.error : undefined;
    return (
      <ErrorState
        title={problem?.status === 403 ? 'Partner access denied' : 'Partner profile unavailable'}
        description={
          problem === undefined
            ? 'The partner profile could not be loaded in the current tenant scope.'
            : `${problem.message} (${problem.code})`
        }
        actionLabel="Try again"
        onRetry={() => void partnerQuery.refetch()}
      />
    );
  }
  if (editing && !partnerQuery.data?.data.allowedActions.includes('EDIT')) {
    return <Navigate to="/forbidden" replace />;
  }

  const problem = mutation.error instanceof PartnerApiError ? mutation.error : undefined;

  return (
    <section className="partner-page" aria-labelledby="partner-editor-title">
      <Space orientation="vertical" size="large" className="partner-page-stack">
        <div>
          <Typography.Title id="partner-editor-title" level={2}>
            {editing ? 'Edit partner draft' : 'Create partner draft'}
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            Save an incomplete draft at any stage. Required identity, contact, address, commercial,
            and registration fields are checked when the profile is submitted for review.
          </Typography.Paragraph>
        </div>

        {editing && (
          <Alert
            showIcon
            type="info"
            title="Protected registration identifier"
            description="Leave the registration identifier blank to retain the stored value. Only its masked suffix is returned by the API."
          />
        )}
        {problem !== undefined && (
          <Alert
            showIcon
            type={problem.status === 412 ? 'warning' : 'error'}
            title={
              problem.status === 412
                ? 'This partner changed after you opened it'
                : problem.code === 'PARTNER_DUPLICATE_IDENTIFIER'
                  ? 'Registration identifier already in use'
                  : 'Partner draft was not saved'
            }
            description={`${problem.message} (${problem.code})`}
            action={
              problem.status === 412 ? (
                <Button size="small" onClick={() => void partnerQuery.refetch()}>
                  Reload current version
                </Button>
              ) : undefined
            }
          />
        )}

        <form onSubmit={(event) => void handleSubmit((values) => mutation.mutate(values))(event)}>
          <Space orientation="vertical" size="large" className="partner-page-stack">
            <Card title="Business identity">
              <Row gutter={16}>
                <Col xs={24} lg={12}>
                  <Form.Item
                    label="Legal name"
                    validateStatus={errors.legalName ? 'error' : undefined}
                    help={errors.legalName?.message}
                  >
                    <ControlledInput
                      control={control}
                      name="legalName"
                      autoComplete="organization"
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} lg={12}>
                  <Form.Item
                    label="Display name"
                    validateStatus={errors.displayName ? 'error' : undefined}
                    help={errors.displayName?.message}
                  >
                    <ControlledInput control={control} name="displayName" />
                  </Form.Item>
                </Col>
                <Col xs={24} lg={12}>
                  <Form.Item label="Registration identifier">
                    <ControlledInput control={control} name="registrationIdentifier" />
                  </Form.Item>
                </Col>
                <Col xs={24} lg={12}>
                  <Form.Item label="Partner type">
                    <Controller
                      control={control}
                      name="type"
                      render={({ field }) => <Select {...field} options={partnerTypeOptions} />}
                    />
                  </Form.Item>
                </Col>
              </Row>
            </Card>

            <Card title="Requested commercial eligibility">
              <Row gutter={16}>
                <Col xs={24} md={8}>
                  <Form.Item
                    label="Default currency"
                    validateStatus={errors.defaultCurrency ? 'error' : undefined}
                    help={errors.defaultCurrency?.message}
                  >
                    <ControlledInput control={control} name="defaultCurrency" maxLength={3} />
                  </Form.Item>
                </Col>
                <Col xs={24} md={8}>
                  <Form.Item
                    label="Payment term (days)"
                    validateStatus={errors.requestedPaymentTermDays ? 'error' : undefined}
                    help={errors.requestedPaymentTermDays?.message}
                  >
                    <ControlledInput
                      control={control}
                      name="requestedPaymentTermDays"
                      inputMode="numeric"
                    />
                  </Form.Item>
                </Col>
                <Col xs={24}>
                  <Form.Item label="Trade routes">
                    <Controller
                      control={control}
                      name="requestedRouteCodes"
                      render={({ field }) => (
                        <Select {...field} mode="multiple" options={routeOptions} />
                      )}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} lg={12}>
                  <Form.Item label="Service regions" extra="Enter a region and press Return.">
                    <Controller
                      control={control}
                      name="requestedServiceRegions"
                      render={({ field }) => (
                        <Select {...field} mode="tags" tokenSeparators={[',']} />
                      )}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} lg={12}>
                  <Form.Item label="Currencies" extra="Use three-letter uppercase codes.">
                    <Controller
                      control={control}
                      name="requestedCurrencies"
                      render={({ field }) => (
                        <Select {...field} mode="tags" tokenSeparators={[',']} />
                      )}
                    />
                  </Form.Item>
                </Col>
              </Row>
            </Card>

            <Card title="Primary contact and billing address">
              <Row gutter={16}>
                <Col xs={24} lg={8}>
                  <Form.Item
                    label="Contact name"
                    validateStatus={errors.contactName ? 'error' : undefined}
                    help={errors.contactName?.message}
                  >
                    <ControlledInput control={control} name="contactName" autoComplete="name" />
                  </Form.Item>
                </Col>
                <Col xs={24} lg={8}>
                  <Form.Item
                    label="Contact email"
                    validateStatus={errors.contactEmail ? 'error' : undefined}
                    help={errors.contactEmail?.message}
                  >
                    <ControlledInput
                      control={control}
                      name="contactEmail"
                      type="email"
                      autoComplete="email"
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} lg={8}>
                  <Form.Item label="Contact phone">
                    <ControlledInput control={control} name="contactPhone" autoComplete="tel" />
                  </Form.Item>
                </Col>
                <Col xs={24} md={6}>
                  <Form.Item
                    label="Country"
                    validateStatus={errors.countryCode ? 'error' : undefined}
                    help={errors.countryCode?.message}
                  >
                    <ControlledInput control={control} name="countryCode" maxLength={2} />
                  </Form.Item>
                </Col>
                <Col xs={24} md={9}>
                  <Form.Item
                    label="Province or state"
                    validateStatus={errors.province ? 'error' : undefined}
                    help={errors.province?.message}
                  >
                    <ControlledInput control={control} name="province" />
                  </Form.Item>
                </Col>
                <Col xs={24} md={9}>
                  <Form.Item
                    label="City"
                    validateStatus={errors.city ? 'error' : undefined}
                    help={errors.city?.message}
                  >
                    <ControlledInput control={control} name="city" />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item label="District">
                    <ControlledInput control={control} name="district" />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item label="Postal code">
                    <ControlledInput control={control} name="postalCode" />
                  </Form.Item>
                </Col>
                <Col xs={24}>
                  <Form.Item
                    label="Address line"
                    validateStatus={errors.line1 ? 'error' : undefined}
                    help={errors.line1?.message}
                  >
                    <ControlledInput control={control} name="line1" autoComplete="street-address" />
                  </Form.Item>
                </Col>
              </Row>
            </Card>

            <Card title="Duplicate assessment">
              <Form.Item
                label="Difference note"
                extra="Required at submission when another partner has the same normalized legal name."
              >
                <Controller
                  control={control}
                  name="duplicateResolutionNote"
                  render={({ field }) => <Input.TextArea {...field} rows={3} />}
                />
              </Form.Item>
            </Card>

            <Space>
              <Button type="primary" htmlType="submit" loading={mutation.isPending}>
                Save partner draft
              </Button>
              <Link to={editing ? `/app/partners/${partnerId}` : '/app/partners'}>Cancel</Link>
            </Space>
          </Space>
        </form>
      </Space>
    </section>
  );
}
