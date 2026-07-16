import { z } from 'zod';
import type { QuotationDetail, QuotationDraftRequest } from '../../api/quotations';

const decimal = /^\d+(\.\d{1,6})?$/;
const rate = /^0(\.\d{1,4})?$/;
const money = /^\d+(\.\d{1,4})?$/;

export const quotationFormSchema = z.object({
  partnerId: z.string().uuid('Select an active partner'),
  currency: z.string().regex(/^[A-Z]{3}$/, 'Use a three-letter currency'),
  requestedDeliveryDate: z.string().min(1, 'Delivery date is required'),
  expiresAt: z.string().min(1, 'Expiry is required'),
  paymentTermDays: z.number().int().min(0).max(180),
  countryCode: z.string().regex(/^[A-Z]{2}$/, 'Use a two-letter country code'),
  province: z.string().trim().min(1, 'Province is required').max(100),
  city: z.string().trim().min(1, 'City is required').max(100),
  district: z.string().trim().max(100),
  line1: z.string().trim().min(1, 'Address is required').max(200),
  postalCode: z.string().trim().max(20),
  lines: z
    .array(
      z
        .object({
          skuId: z.string().uuid('Select an active SKU'),
          quantity: z.string().regex(decimal, 'Use a positive quantity'),
          unit: z.enum(['CASE', 'BOTTLE']),
          supplyStrategy: z.enum(['AUTO', 'FIXED']),
          preferredSupplyPoolId: z.string().uuid().optional(),
          discountRate: z.string().regex(rate, 'Use a rate from 0 up to, but not including, 1'),
          manualUnitPrice: z
            .string()
            .regex(money, 'Use up to four decimal places')
            .or(z.literal('')),
        })
        .superRefine((line, context) => {
          if (line.supplyStrategy === 'FIXED' && line.preferredSupplyPoolId === undefined) {
            context.addIssue({
              code: 'custom',
              path: ['preferredSupplyPoolId'],
              message: 'Select an automatically reservable supply pool',
            });
          }
        }),
    )
    .min(1, 'Add at least one line')
    .max(50)
    .refine((lines) => new Set(lines.map((line) => line.skuId)).size === lines.length, {
      message: 'A SKU may appear only once',
    }),
});

export type QuotationFormValues = z.infer<typeof quotationFormSchema>;

function dateTimeLocal(value: string): string {
  const date = new Date(value);
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

const tomorrow = new Date(Date.now() + 86_400_000);
const twoWeeks = new Date(Date.now() + 14 * 86_400_000);

export const emptyQuotationForm: QuotationFormValues = {
  partnerId: '',
  currency: 'CNY',
  requestedDeliveryDate: twoWeeks.toISOString().slice(0, 10),
  expiresAt: dateTimeLocal(tomorrow.toISOString()),
  paymentTermDays: 30,
  countryCode: 'CN',
  province: 'Shanghai',
  city: 'Shanghai',
  district: '',
  line1: '',
  postalCode: '',
  lines: [
    {
      skuId: '',
      quantity: '1',
      unit: 'CASE',
      supplyStrategy: 'AUTO',
      preferredSupplyPoolId: undefined,
      discountRate: '0',
      manualUnitPrice: '',
    },
  ],
};

export function toQuotationRequest(values: QuotationFormValues): QuotationDraftRequest {
  return {
    partnerId: values.partnerId,
    currency: values.currency,
    requestedDeliveryDate: values.requestedDeliveryDate,
    expiresAt: new Date(values.expiresAt).toISOString(),
    paymentTermDays: values.paymentTermDays,
    deliveryAddress: {
      countryCode: values.countryCode,
      province: values.province,
      city: values.city,
      district: values.district || undefined,
      line1: values.line1,
      postalCode: values.postalCode || undefined,
    },
    lines: values.lines.map((line) => ({
      skuId: line.skuId,
      quantity: { value: line.quantity, unit: line.unit },
      preferredSupplyPoolId:
        line.supplyStrategy === 'FIXED' ? line.preferredSupplyPoolId : undefined,
      discountRate: line.discountRate,
      manualUnitPrice:
        line.manualUnitPrice === ''
          ? undefined
          : { amount: line.manualUnitPrice, currency: values.currency },
    })),
  };
}

export function formFromQuotation(quotation: QuotationDetail): QuotationFormValues {
  return {
    partnerId: quotation.partnerId,
    currency: quotation.total.currency,
    requestedDeliveryDate: quotation.requestedDeliveryDate,
    expiresAt: dateTimeLocal(quotation.expiresAt),
    paymentTermDays: quotation.paymentTermDays,
    countryCode: quotation.deliveryAddress.countryCode,
    province: quotation.deliveryAddress.province,
    city: quotation.deliveryAddress.city,
    district: quotation.deliveryAddress.district ?? '',
    line1: quotation.deliveryAddress.line1,
    postalCode: quotation.deliveryAddress.postalCode ?? '',
    lines: quotation.lines.map((line) => ({
      skuId: line.sku.skuId,
      quantity: line.quantity.value,
      unit: line.quantity.unit,
      supplyStrategy: line.supplyPoolId == null ? 'AUTO' : 'FIXED',
      preferredSupplyPoolId: line.supplyPoolId ?? undefined,
      discountRate: line.discountRate ?? '0',
      manualUnitPrice: '',
    })),
  };
}
