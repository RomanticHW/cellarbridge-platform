package com.rom.cellarbridge.quotation;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

/** Canonical Snapshot Hash V1 shared by accepted-quotation producers and consumers. */
public final class QuotationSnapshotHashV1 {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private QuotationSnapshotHashV1() {}

  public static String hash(Snapshot snapshot) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(JSON.writeValueAsBytes(canonical(snapshot))));
    } catch (JacksonException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Snapshot Hash V1 calculation failed", exception);
    }
  }

  private static Map<String, Object> canonical(Snapshot snapshot) {
    return object(
        "schemaVersion", snapshot.schemaVersion(),
        "quotationId", snapshot.quotationId(),
        "revisionId", snapshot.revisionId(),
        "quotationNumber", snapshot.quotationNumber(),
        "revision", snapshot.revision(),
        "customer",
            object(
                "partnerId", snapshot.customer().partnerId(),
                "partnerNumber", snapshot.customer().partnerNumber(),
                "displayName", snapshot.customer().displayName(),
                "sourceVersion", snapshot.customer().sourceVersion()),
        "currency", snapshot.currency(),
        "totalAmount", decimal(snapshot.totalAmount()),
        "paymentTermDays", snapshot.paymentTermDays(),
        "route",
            object(
                "code", snapshot.route().code(),
                "policyVersion", snapshot.route().policyVersion(),
                "estimatedDeliveryDate", snapshot.route().estimatedDeliveryDate()),
        "acceptedTermsVersion", snapshot.acceptedTermsVersion(),
        "requestedDeliveryDate", snapshot.requestedDeliveryDate(),
        "deliveryAddress",
            object(
                "countryCode", snapshot.deliveryAddress().countryCode(),
                "province", snapshot.deliveryAddress().province(),
                "city", snapshot.deliveryAddress().city(),
                "district", snapshot.deliveryAddress().district(),
                "line1", snapshot.deliveryAddress().line1(),
                "postalCode", snapshot.deliveryAddress().postalCode()),
        "lines", snapshot.lines().stream().map(QuotationSnapshotHashV1::canonicalLine).toList());
  }

  private static Map<String, Object> canonicalLine(QuotationAcceptedV1.Line line) {
    return object(
        "quotationLineId", line.quotationLineId(),
        "skuId", line.skuId(),
        "skuCode", line.skuCode(),
        "description", line.description(),
        "quantity", decimal(line.quantity()),
        "unit", line.unit(),
        "netUnitPrice", decimal(line.netUnitPrice()),
        "lineTotal", decimal(line.lineTotal()),
        "supplyPoolId", line.supplyPoolId(),
        "supplyType", line.supplyType());
  }

  private static Map<String, Object> object(Object... fields) {
    Map<String, Object> object = new LinkedHashMap<>();
    for (int index = 0; index < fields.length; index += 2) {
      Object value = fields[index + 1];
      if (value == null) {
        value = NullNode.getInstance();
      } else if (value instanceof UUID || value instanceof LocalDate) {
        value = value.toString();
      }
      object.put((String) fields[index], value);
    }
    return object;
  }

  private static String decimal(String value) {
    return new BigDecimal(value).stripTrailingZeros().toPlainString();
  }

  public record Snapshot(
      int schemaVersion,
      UUID quotationId,
      UUID revisionId,
      String quotationNumber,
      int revision,
      QuotationAcceptedV1.Customer customer,
      String currency,
      String totalAmount,
      int paymentTermDays,
      QuotationAcceptedV1.Route route,
      String acceptedTermsVersion,
      LocalDate requestedDeliveryDate,
      QuotationAcceptedV1.DeliveryAddress deliveryAddress,
      List<QuotationAcceptedV1.Line> lines) {

    public Snapshot {
      if (schemaVersion != 1) {
        throw new IllegalArgumentException("Snapshot Hash V1 requires schemaVersion 1");
      }
      lines = List.copyOf(lines);
    }

    public static Snapshot from(QuotationAcceptedV1.Payload payload) {
      return new Snapshot(
          1,
          payload.quotationId(),
          payload.revisionId(),
          payload.quotationNumber(),
          payload.revision(),
          payload.customer(),
          payload.currency(),
          payload.totalAmount(),
          payload.paymentTermDays(),
          payload.route(),
          payload.acceptedTermsVersion(),
          payload.requestedDeliveryDate(),
          payload.deliveryAddress(),
          payload.lines());
    }
  }
}
