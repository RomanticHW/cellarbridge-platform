package com.rom.cellarbridge.partner;

import com.rom.cellarbridge.identityaccess.TenantId;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public interface PartnerEligibilityService {

  EligibilitySnapshot requireActive(TenantId tenantId, UUID partnerId);

  record EligibilitySnapshot(
      UUID partnerId,
      String partnerNumber,
      String displayName,
      int sourceVersion,
      Set<String> routeCodes,
      Set<String> serviceRegions,
      Set<String> currencies,
      int paymentTermDays,
      Instant capturedAt) {

    public EligibilitySnapshot {
      routeCodes = Set.copyOf(routeCodes);
      serviceRegions = Set.copyOf(serviceRegions);
      currencies = Set.copyOf(currencies);
    }
  }
}
