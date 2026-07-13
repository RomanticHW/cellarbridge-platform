import { z } from 'zod';
import type { CreatePartnerRequest, PartnerDetail, UpdatePartnerRequest } from '../../api/partners';

export const partnerTypes = [
  'RESTAURANT_GROUP',
  'DISTRIBUTOR',
  'RETAILER',
  'CORPORATE_BUYER',
  'OTHER',
] as const;

export const routeCodes = ['SH_GENERAL_TRADE', 'NB_BONDED_B2B', 'HK_FREE_TRADE'] as const;

const optionalTerm = z
  .string()
  .trim()
  .refine(
    (value) => value === '' || (/^[0-9]+$/.test(value) && Number(value) <= 180),
    'Enter a whole number from 0 to 180',
  );

export const partnerFormSchema = z.object({
  legalName: z.string().trim().max(200),
  displayName: z.string().trim().max(100),
  registrationIdentifier: z.string().trim().max(100),
  type: z.enum(partnerTypes),
  defaultCurrency: z
    .string()
    .trim()
    .regex(/^(?:[A-Z]{3})?$/, 'Use a three-letter currency code'),
  requestedPaymentTermDays: optionalTerm,
  requestedRouteCodes: z.array(z.enum(routeCodes)),
  requestedServiceRegions: z.array(z.string().trim().min(1).max(80)),
  requestedCurrencies: z.array(z.string().regex(/^[A-Z]{3}$/)),
  contactName: z.string().trim().max(100),
  contactEmail: z
    .string()
    .trim()
    .max(254)
    .refine(
      (value) => value === '' || z.email().safeParse(value).success,
      'Enter a valid email address',
    ),
  contactPhone: z.string().trim().max(40),
  countryCode: z
    .string()
    .trim()
    .regex(/^(?:[A-Z]{2})?$/, 'Use a two-letter country code'),
  province: z.string().trim().max(100),
  city: z.string().trim().max(100),
  district: z.string().trim().max(100),
  line1: z.string().trim().max(200),
  postalCode: z.string().trim().max(20),
  duplicateResolutionNote: z.string().trim().max(500),
});

export type PartnerFormValues = z.infer<typeof partnerFormSchema>;

export const emptyPartnerForm: PartnerFormValues = {
  legalName: '',
  displayName: '',
  registrationIdentifier: '',
  type: 'RESTAURANT_GROUP',
  defaultCurrency: 'CNY',
  requestedPaymentTermDays: '',
  requestedRouteCodes: [],
  requestedServiceRegions: [],
  requestedCurrencies: ['CNY'],
  contactName: '',
  contactEmail: '',
  contactPhone: '',
  countryCode: 'CN',
  province: '',
  city: '',
  district: '',
  line1: '',
  postalCode: '',
  duplicateResolutionNote: '',
};

function term(value: string): number | undefined {
  return value === '' ? undefined : Number(value);
}

function profileFields(values: PartnerFormValues) {
  return {
    legalName: values.legalName,
    displayName: values.displayName,
    registrationIdentifier: values.registrationIdentifier || undefined,
    defaultCurrency: values.defaultCurrency,
    type: values.type,
    requestedPaymentTermDays: term(values.requestedPaymentTermDays),
    requestedRouteCodes: values.requestedRouteCodes,
    requestedServiceRegions: values.requestedServiceRegions,
    requestedCurrencies: values.requestedCurrencies,
    contact: {
      name: values.contactName,
      email: values.contactEmail,
      phone: values.contactPhone || undefined,
      primary: true,
    },
    billingAddress: {
      countryCode: values.countryCode,
      province: values.province,
      city: values.city,
      district: values.district || undefined,
      line1: values.line1,
      postalCode: values.postalCode || undefined,
    },
    duplicateResolutionNote: values.duplicateResolutionNote || undefined,
  };
}

export function toCreateRequest(values: PartnerFormValues): CreatePartnerRequest {
  return profileFields(values);
}

export function toUpdateRequest(values: PartnerFormValues): UpdatePartnerRequest {
  return profileFields(values);
}

export function formFromPartner(partner: PartnerDetail): PartnerFormValues {
  const contact = partner.contacts[0];
  const address = partner.billingAddress;
  return {
    legalName: partner.legalName ?? '',
    displayName: partner.displayName ?? '',
    registrationIdentifier: '',
    type: partner.type ?? 'RESTAURANT_GROUP',
    defaultCurrency: partner.defaultCurrency ?? '',
    requestedPaymentTermDays: partner.paymentTermDays?.toString() ?? '',
    requestedRouteCodes: partner.routeEligibility as PartnerFormValues['requestedRouteCodes'],
    requestedServiceRegions: partner.requestedServiceRegions,
    requestedCurrencies: partner.requestedCurrencies,
    contactName: contact?.name ?? '',
    contactEmail: contact?.email ?? '',
    contactPhone: contact?.phone ?? '',
    countryCode: address?.countryCode ?? '',
    province: address?.province ?? '',
    city: address?.city ?? '',
    district: address?.district ?? '',
    line1: address?.line1 ?? '',
    postalCode: address?.postalCode ?? '',
    duplicateResolutionNote: '',
  };
}
