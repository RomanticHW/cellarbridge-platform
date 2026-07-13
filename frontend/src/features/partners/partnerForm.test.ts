import { describe, expect, it } from 'vitest';
import type { PartnerDetail } from '../../api/partners';
import {
  emptyPartnerForm,
  formFromPartner,
  partnerFormSchema,
  toCreateRequest,
  toUpdateRequest,
} from './partnerForm';

const complete = {
  ...emptyPartnerForm,
  legalName: 'Cedar Dining Group',
  displayName: 'Cedar Dining',
  registrationIdentifier: 'REG-301',
  requestedPaymentTermDays: '30',
  requestedRouteCodes: ['SH_GENERAL_TRADE'] as const,
  requestedServiceRegions: ['CN-SH'],
  requestedCurrencies: ['CNY'],
  contactName: 'Lin Wen',
  contactEmail: 'lin.wen@example.test',
  province: 'Shanghai',
  city: 'Shanghai',
  line1: '301 Huaihai Road',
};

describe('partner form mapping', () => {
  it('validates and maps a create draft without exposing empty optional fields', () => {
    const values = partnerFormSchema.parse(complete);
    const request = toCreateRequest(values);

    expect(request).toMatchObject({
      legalName: 'Cedar Dining Group',
      requestedPaymentTermDays: 30,
      requestedRouteCodes: ['SH_GENERAL_TRADE'],
      contact: { name: 'Lin Wen', primary: true },
    });
    expect(request.contact?.phone).toBeUndefined();
  });

  it('accepts an incomplete draft and hydrates missing profile fields', () => {
    const values = partnerFormSchema.parse({
      ...emptyPartnerForm,
      defaultCurrency: '',
      requestedCurrencies: [],
      countryCode: '',
    });

    expect(toCreateRequest(values)).toMatchObject({
      legalName: '',
      displayName: '',
      defaultCurrency: '',
      contact: { name: '', email: '' },
      billingAddress: { countryCode: '', province: '', city: '', line1: '' },
    });

    const incomplete = {
      id: '30000000-0000-4000-8000-000000000002',
      number: 'PAR-202607-000002',
      legalName: null,
      displayName: null,
      status: 'DRAFT',
      defaultCurrency: null,
      routeEligibility: [],
      version: 0,
      updatedAt: '2026-07-13T08:00:00Z',
      type: null,
      contacts: [],
      billingAddress: null,
      creditLimit: null,
      eligibility: null,
      requestedServiceRegions: [],
      requestedCurrencies: [],
      allowedActions: ['EDIT'],
      duplicateWarnings: [],
      timeline: [],
    } satisfies PartnerDetail;

    expect(formFromPartner(incomplete)).toMatchObject({
      legalName: '',
      displayName: '',
      defaultCurrency: '',
      contactName: '',
      countryCode: '',
      line1: '',
    });
  });

  it('maps blank draft eligibility to omitted patch fields and rejects invalid values', () => {
    const values = partnerFormSchema.parse({
      ...complete,
      registrationIdentifier: '',
      requestedPaymentTermDays: '',
      duplicateResolutionNote: '',
    });
    expect(toUpdateRequest(values)).toMatchObject({
      registrationIdentifier: undefined,
      requestedPaymentTermDays: undefined,
      duplicateResolutionNote: undefined,
    });
    expect(
      partnerFormSchema.safeParse({ ...complete, contactEmail: 'invalid', countryCode: 'China' })
        .success,
    ).toBe(false);
  });

  it('hydrates editable values from the masked partner response', () => {
    const partner = {
      id: '30000000-0000-4000-8000-000000000001',
      number: 'PAR-202607-000001',
      legalName: complete.legalName,
      displayName: complete.displayName,
      status: 'DRAFT',
      defaultCurrency: 'CNY',
      routeEligibility: ['SH_GENERAL_TRADE'],
      version: 0,
      updatedAt: '2026-07-13T08:00:00Z',
      type: 'RESTAURANT_GROUP',
      registrationIdentifierMasked: '****0301',
      contacts: [{ name: 'Lin Wen', email: 'lin.wen@example.test', primary: true }],
      billingAddress: {
        countryCode: 'CN',
        province: 'Shanghai',
        city: 'Shanghai',
        line1: '301 Huaihai Road',
      },
      paymentTermDays: null,
      creditLimit: null,
      eligibility: null,
      requestedServiceRegions: ['CN-SH'],
      requestedCurrencies: ['CNY'],
      allowedActions: ['EDIT'],
      duplicateWarnings: [],
      timeline: [],
    } satisfies PartnerDetail;

    expect(formFromPartner(partner)).toMatchObject({
      legalName: complete.legalName,
      registrationIdentifier: '',
      requestedPaymentTermDays: '',
      countryCode: 'CN',
    });
  });
});
