package com.rom.cellarbridge.fulfillment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Source-owned, read-only Fulfillment facts for bounded business investigation. */
public interface FulfillmentInvestigationQuery {

  Optional<FulfillmentSource> findById(UUID planId);

  Optional<FulfillmentSource> findByOrderId(UUID orderId);

  Optional<FulfillmentSource> findByReservationId(UUID reservationId);

  Optional<FulfillmentSource> findByStepId(UUID stepId);

  record FulfillmentSource(
      UUID id,
      UUID orderId,
      UUID reservationId,
      FulfillmentStatus status,
      CurrentStep currentStep,
      long version,
      Instant updatedAt) {}

  record CurrentStep(String code, FulfillmentStepStatus status, String ownerRole) {}
}
