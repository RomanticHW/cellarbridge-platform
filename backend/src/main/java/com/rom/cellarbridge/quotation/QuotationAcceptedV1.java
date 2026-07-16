package com.rom.cellarbridge.quotation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public, immutable quotation fact consumed by downstream modules. The payload is deliberately
 * self-contained so consumers never need to read Quotation-owned tables.
 *
 * <p>V1 requires accepted terms, requested delivery date and delivery address; sourceOwnerId is
 * optional. snapshotHash is the bare lowercase 64-hex result of {@link QuotationSnapshotHashV1}'s
 * canonical commercial projection.
 */
public record QuotationAcceptedV1(
    UUID id,
    String type,
    String specVersion,
    Instant occurredAt,
    UUID tenantId,
    String producer,
    Subject subject,
    UUID correlationId,
    UUID causationId,
    Payload payload,
    Map<String, Object> metadata) {

  public static final String TYPE = "cellarbridge.quotation.accepted.v1";

  public QuotationAcceptedV1 {
    metadata = Map.copyOf(metadata);
  }

  public record Subject(String type, UUID id, String number) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Payload(
      UUID quotationId,
      UUID revisionId,
      String quotationNumber,
      int revision,
      UUID acceptanceId,
      Instant acceptedAt,
      UUID sourceOwnerId,
      Customer customer,
      String currency,
      String totalAmount,
      int paymentTermDays,
      Route route,
      String acceptedTermsVersion,
      LocalDate requestedDeliveryDate,
      DeliveryAddress deliveryAddress,
      String snapshotHash,
      SupplyDecision supplyDecision,
      List<Line> lines) {

    public Payload {
      lines = List.copyOf(lines);
    }
  }

  public record Customer(
      UUID partnerId, String partnerNumber, String displayName, int sourceVersion) {}

  public record Route(String code, String policyVersion, LocalDate estimatedDeliveryDate) {}

  public record DeliveryAddress(
      String countryCode,
      String province,
      String city,
      String district,
      String line1,
      String postalCode) {}

  public record SupplyDecision(
      int schemaVersion,
      String policyVersion,
      Instant decidedAt,
      UUID sourceRouteEvaluationId,
      String sourceRouteInputHash,
      String selectedRouteCode,
      Instant inventoryDataAsOf,
      String decisionHash) {}

  public record Line(
      UUID quotationLineId,
      UUID skuId,
      String skuCode,
      String description,
      String quantity,
      String unit,
      String netUnitPrice,
      String lineTotal,
      UUID supplyPoolId,
      @JsonInclude(JsonInclude.Include.NON_NULL) String allocationMode,
      String supplyType) {}
}
