import { describe, expect, it } from 'vitest';
import { quotationFormSchema, toQuotationRequest } from './quotationForm';

const values = {
  partnerId: '30000000-0000-4000-8000-000000000001',
  currency: 'CNY',
  requestedDeliveryDate: '2026-08-01',
  expiresAt: '2026-07-28T08:00',
  paymentTermDays: 30,
  countryCode: 'CN',
  province: 'Shanghai',
  city: 'Shanghai',
  district: '',
  line1: '18 Riverside Road',
  postalCode: '',
  lines: [
    {
      skuId: '34000000-0000-4000-8000-000000000001',
      quantity: '2.5',
      unit: 'CASE' as const,
      discountRate: '0.0750',
      manualUnitPrice: '4200.0000',
    },
  ],
};

describe('quotation form', () => {
  it('maps validated decimals and optional address fields without number coercion', () => {
    const parsed = quotationFormSchema.parse(values);
    expect(toQuotationRequest(parsed)).toMatchObject({
      currency: 'CNY',
      paymentTermDays: 30,
      deliveryAddress: { district: undefined, postalCode: undefined },
      lines: [
        {
          quantity: { value: '2.5', unit: 'CASE' },
          discountRate: '0.0750',
          manualUnitPrice: { amount: '4200.0000', currency: 'CNY' },
        },
      ],
    });
  });

  it('rejects duplicate SKUs and a one-hundred-percent discount', () => {
    expect(
      quotationFormSchema.safeParse({ ...values, lines: [values.lines[0], values.lines[0]] })
        .success,
    ).toBe(false);
    expect(
      quotationFormSchema.safeParse({
        ...values,
        lines: [{ ...values.lines[0], discountRate: '1.0000' }],
      }).success,
    ).toBe(false);
  });
});
