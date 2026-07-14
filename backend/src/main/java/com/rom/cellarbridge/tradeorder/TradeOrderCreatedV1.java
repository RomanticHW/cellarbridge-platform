package com.rom.cellarbridge.tradeorder;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public immutable order fact. Consumers do not need to read Trade Order-owned tables. */
public record TradeOrderCreatedV1(
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

  public static final String TYPE = "cellarbridge.order.created.v1";

  public TradeOrderCreatedV1 {
    metadata = Map.copyOf(metadata);
  }

  public record Subject(String type, UUID id, String number) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Payload(
      UUID orderId,
      String orderNumber,
      UUID sourceQuotationId,
      UUID sourceRevisionId,
      String sourceQuotationNumber,
      int sourceRevision,
      UUID sourceEventId,
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
      List<Line> lines,
      Instant createdAt) {

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

  public record Line(
      UUID orderLineId,
      UUID sourceQuotationLineId,
      UUID skuId,
      String skuCode,
      String description,
      String quantity,
      String unit,
      String netUnitPrice,
      String lineTotal,
      UUID supplyPoolId,
      String supplyType) {}
}
