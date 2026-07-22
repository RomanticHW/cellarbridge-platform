import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Timeline,
  Typography,
  message,
} from 'antd';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  getReceivable,
  recordPayment,
  reversePayment,
  SettlementApiError,
  type PaymentRecord,
  type ReceivableDetail,
} from '../../api/settlement';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from '../identity-access/authSession';

type Dialog = 'PAYMENT' | 'REVERSAL';

function label(value: string) {
  return value.replaceAll('_', ' ');
}

function money(value: ReceivableDetail['originalAmount']) {
  if (!value) return 'Restricted';
  return `${value.currency} ${value.amount}`;
}

function today() {
  return new Date().toISOString().slice(0, 10);
}

export function ReceivableDetailPage() {
  const { receivableId = '' } = useParams();
  const session = useAuthSession();
  const accessToken = session.accessToken ?? '';
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<Dialog>();
  const [paymentAmount, setPaymentAmount] = useState('');
  const [paymentDate, setPaymentDate] = useState(today());
  const [paymentMethod, setPaymentMethod] = useState<
    'BANK_TRANSFER' | 'CASH_SIMULATION' | 'OTHER_SIMULATION'
  >('BANK_TRANSFER');
  const [externalReference, setExternalReference] = useState('');
  const [note, setNote] = useState('');
  const [selectedPayment, setSelectedPayment] = useState<PaymentRecord>();
  const [reversalAmount, setReversalAmount] = useState('');
  const [reversalReason, setReversalReason] = useState('');
  const [reversalConfirmed, setReversalConfirmed] = useState(false);
  const [commandKey, setCommandKey] = useState('');

  const receivable = useQuery({
    queryKey: ['settlement', 'receivable', receivableId],
    queryFn: ({ signal }) => getReceivable(accessToken, receivableId, signal),
    enabled: accessToken !== '' && receivableId !== '',
  });

  const command = useMutation({
    mutationFn: async (active: Dialog) => {
      const detail = receivable.data;
      if (!detail) throw new Error('Receivable detail is unavailable');
      if (commandKey === '') throw new Error('Settlement command identity is unavailable');
      if (active === 'PAYMENT') {
        return recordPayment(
          accessToken,
          receivableId,
          detail.version,
          {
            amount: { amount: paymentAmount, currency: detail.originalAmount?.currency ?? '' },
            occurredOn: paymentDate,
            method: paymentMethod,
            externalReference,
            note: note || undefined,
          },
          commandKey,
        );
      }
      if (!selectedPayment?.reversibleAmount) throw new Error('Payment cannot be reversed');
      return reversePayment(
        accessToken,
        receivableId,
        selectedPayment.id,
        detail.version,
        {
          amount: {
            amount: reversalAmount,
            currency: selectedPayment.reversibleAmount.currency,
          },
          reason: reversalReason,
        },
        commandKey,
      );
    },
    onSuccess: async () => {
      setDialog(undefined);
      setPaymentAmount('');
      setExternalReference('');
      setNote('');
      setSelectedPayment(undefined);
      setReversalAmount('');
      setReversalReason('');
      setReversalConfirmed(false);
      setCommandKey('');
      await queryClient.invalidateQueries({ queryKey: ['settlement'] });
      void message.success('Receivable updated');
    },
    onError: async (error) => {
      if (error instanceof SettlementApiError && [409, 412].includes(error.status)) {
        await queryClient.invalidateQueries({
          queryKey: ['settlement', 'receivable', receivableId],
        });
        void message.warning(
          `${error.message} (${error.code}). Review the latest balance before retrying.`,
        );
      }
    },
  });

  if (receivable.isPending) return <LoadingState message="Loading receivable evidence…" />;
  if (receivable.isError) {
    const problem = receivable.error instanceof SettlementApiError ? receivable.error : undefined;
    return (
      <ErrorState
        title={problem?.status === 404 ? 'Receivable not found' : 'Receivable is unavailable'}
        description={
          problem ? `${problem.message} (${problem.code})` : 'The receivable could not be loaded.'
        }
        actionLabel="Try again"
        onRetry={() => void receivable.refetch()}
      />
    );
  }

  const detail = receivable.data;
  const canRecord = detail.allowedActions.includes('RECORD_PAYMENT');
  const canReverse = detail.allowedActions.includes('REVERSE_PAYMENT');
  const openPayment = () => {
    setPaymentAmount(detail.outstandingAmount?.amount ?? '');
    setPaymentDate(today());
    setCommandKey(`payment-ui-${crypto.randomUUID()}`);
    setDialog('PAYMENT');
  };
  const openReversal = (payment: PaymentRecord) => {
    setSelectedPayment(payment);
    setReversalAmount(payment.reversibleAmount?.amount ?? '');
    setReversalReason('');
    setReversalConfirmed(false);
    setCommandKey(`reversal-ui-${crypto.randomUUID()}`);
    setDialog('REVERSAL');
  };
  const closeDialog = () => {
    setDialog(undefined);
    setCommandKey('');
  };

  return (
    <article className="settlement-page" aria-labelledby="receivable-detail-title">
      <Space orientation="vertical" size="large" className="settlement-page-stack">
        <header className="settlement-heading">
          <div>
            <Link to="/app/receivables">← Back to receivables</Link>
            <Typography.Title id="receivable-detail-title" level={2}>
              {detail.number}
            </Typography.Title>
            <Typography.Text type="secondary">
              {detail.orderNumber} · {detail.partnerName}
            </Typography.Text>
          </div>
          <Space wrap>
            <Tag
              color={
                detail.status === 'OVERDUE'
                  ? 'error'
                  : detail.status === 'PAID'
                    ? 'success'
                    : 'blue'
              }
            >
              {label(detail.status)}
            </Tag>
            <Tag>Version {detail.version}</Tag>
          </Space>
        </header>

        <Alert
          type="info"
          showIcon
          title="Recorded financial evidence only"
          description="Payments are external facts entered by authorized staff. This workspace does not initiate bank transfers, post ledger entries, issue invoices, or calculate tax."
        />
        {!detail.commercialAmountVisible ? (
          <Alert
            type="warning"
            showIcon
            title="Commercial amounts are restricted"
            description="Status and timing remain visible, while amounts and payment evidence are masked for this role."
          />
        ) : null}

        <Card title="Balance snapshot">
          <Descriptions column={{ xs: 1, md: 2 }}>
            <Descriptions.Item label="Customer">{detail.partnerName}</Descriptions.Item>
            <Descriptions.Item label="Partner number">{detail.partnerNumber}</Descriptions.Item>
            <Descriptions.Item label="Original amount">
              {money(detail.originalAmount)}
            </Descriptions.Item>
            <Descriptions.Item label="Outstanding">
              {money(detail.outstandingAmount)}
            </Descriptions.Item>
            <Descriptions.Item label="Due date">{detail.dueDate}</Descriptions.Item>
            <Descriptions.Item label="Order">
              <Link to={`/app/orders/${detail.orderId}`}>{detail.orderNumber}</Link>
            </Descriptions.Item>
          </Descriptions>
          {canRecord ? (
            <Button type="primary" onClick={openPayment}>
              Record payment
            </Button>
          ) : null}
        </Card>

        <Card title="Payment and reversal facts">
          <Table<PaymentRecord>
            rowKey="id"
            pagination={false}
            dataSource={detail.payments}
            locale={{ emptyText: 'No payment facts are visible for this receivable.' }}
            scroll={{ x: 960 }}
            columns={[
              { title: 'Type', dataIndex: 'type', render: (value: string) => label(value) },
              { title: 'Amount', render: (_, item) => money(item.amount) },
              { title: 'Reference', dataIndex: 'externalReference' },
              { title: 'Date', dataIndex: 'occurredOn' },
              {
                title: 'Method',
                dataIndex: 'method',
                render: (value?: string | null) => (value ? label(value) : '—'),
              },
              {
                title: 'Reason',
                dataIndex: 'reason',
                render: (value?: string | null) => value ?? '—',
              },
              {
                title: 'Action',
                render: (_, item) =>
                  canReverse &&
                  item.type === 'PAYMENT' &&
                  item.reversibleAmount &&
                  !/^0+(?:\.0+)?$/.test(item.reversibleAmount.amount) ? (
                    <Button danger onClick={() => openReversal(item)}>
                      Reverse
                    </Button>
                  ) : null,
              },
            ]}
          />
        </Card>

        <Card title="Receivable timeline">
          <Timeline
            items={detail.history.map((item) => ({
              color:
                item.newStatus === 'PAID' ? 'green' : item.newStatus === 'OVERDUE' ? 'red' : 'blue',
              content: (
                <Space orientation="vertical" size={2}>
                  <Typography.Text>
                    {label(item.action)} → {label(item.newStatus)}
                  </Typography.Text>
                  <Typography.Text type="secondary">
                    {item.amount ? money(item.amount) : 'Status evidence'} ·{' '}
                    {new Date(item.occurredAt).toLocaleString()}
                  </Typography.Text>
                  {item.reason ? <Typography.Text>{item.reason}</Typography.Text> : null}
                </Space>
              ),
            }))}
          />
        </Card>
      </Space>

      <Modal
        title="Record external payment"
        open={dialog === 'PAYMENT'}
        confirmLoading={command.isPending}
        okText="Record payment"
        okButtonProps={{ disabled: paymentAmount === '' || externalReference.trim().length < 3 }}
        onCancel={closeDialog}
        onOk={() => command.mutate('PAYMENT')}
      >
        <Space orientation="vertical" size="middle" className="settlement-dialog-fields">
          <label>
            Amount
            <Input
              aria-label="Payment amount"
              value={paymentAmount}
              onChange={(event) => setPaymentAmount(event.target.value)}
            />
          </label>
          <label>
            Payment date
            <Input
              aria-label="Payment date"
              type="date"
              value={paymentDate}
              onChange={(event) => setPaymentDate(event.target.value)}
            />
          </label>
          <label>
            Method
            <Select
              aria-label="Payment method"
              value={paymentMethod}
              onChange={setPaymentMethod}
              options={[
                { value: 'BANK_TRANSFER', label: 'Bank transfer' },
                { value: 'CASH_SIMULATION', label: 'Cash simulation' },
                { value: 'OTHER_SIMULATION', label: 'Other simulation' },
              ]}
            />
          </label>
          <label>
            External reference
            <Input
              aria-label="External reference"
              value={externalReference}
              onChange={(event) => setExternalReference(event.target.value)}
            />
          </label>
          <label>
            Note
            <Input.TextArea
              aria-label="Payment note"
              value={note}
              maxLength={500}
              onChange={(event) => setNote(event.target.value)}
            />
          </label>
        </Space>
      </Modal>

      <Modal
        title="Reverse payment evidence"
        open={dialog === 'REVERSAL'}
        confirmLoading={command.isPending}
        okText="Confirm reversal"
        okButtonProps={{
          danger: true,
          disabled: reversalAmount === '' || reversalReason.trim().length < 5 || !reversalConfirmed,
        }}
        onCancel={closeDialog}
        onOk={() => command.mutate('REVERSAL')}
      >
        <Alert
          type="warning"
          showIcon
          title="This appends a reversal; it never edits or deletes the payment."
        />
        <Space orientation="vertical" size="middle" className="settlement-dialog-fields">
          <label>
            Reversal amount
            <Input
              aria-label="Reversal amount"
              value={reversalAmount}
              onChange={(event) => setReversalAmount(event.target.value)}
            />
          </label>
          <label>
            Reason
            <Input.TextArea
              aria-label="Reversal reason"
              value={reversalReason}
              maxLength={500}
              onChange={(event) => setReversalReason(event.target.value)}
            />
          </label>
          <Checkbox
            checked={reversalConfirmed}
            onChange={(event) => setReversalConfirmed(event.target.checked)}
          >
            I reviewed the payment, amount, and reason and confirm this reversal.
          </Checkbox>
        </Space>
      </Modal>
    </article>
  );
}
