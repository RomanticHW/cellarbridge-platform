package com.rom.cellarbridge.tradeplanning;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Supply Decision Hash V1.
 *
 * <p>The canonical projection writes each scalar in the documented field order as a four-byte
 * big-endian UTF-8 byte length followed by its bytes. The line count is a four-byte big-endian
 * integer, followed by lines ordered by quotation line ID using the same scalar framing. This
 * framing is independent of Java object layout and JSON property order.
 */
public final class SupplyDecisionHashV1 {

  private SupplyDecisionHashV1() {}

  public static String hash(HashInput input) {
    Objects.requireNonNull(input, "input");
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream canonical = new DataOutputStream(bytes)) {
        write(canonical, Integer.toString(input.schemaVersion()));
        write(canonical, input.policyVersion());
        write(canonical, DateTimeFormatter.ISO_INSTANT.format(input.decidedAt()));
        write(canonical, input.sourceRouteEvaluationId().toString());
        write(canonical, input.sourceRouteInputHash());
        write(canonical, input.selectedRouteCode().name());
        write(canonical, DateTimeFormatter.ISO_INSTANT.format(input.inventoryDataAsOf()));
        canonical.writeInt(input.lineDecisions().size());
        for (SupplyDecisionSnapshot.LineDecision line : input.lineDecisions()) {
          write(canonical, line.quotationLineId().toString());
          write(canonical, line.skuId().toString());
          write(
              canonical,
              line.requestedQuantity().setScale(6, RoundingMode.UNNECESSARY).toPlainString());
          write(canonical, line.quantityUnit().name());
          write(canonical, line.allocationMode().name());
          write(canonical, line.supplyPoolId() == null ? "" : line.supplyPoolId().toString());
          write(canonical, line.supplyType().name());
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Supply Decision Hash V1 calculation failed", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  public record HashInput(
      int schemaVersion,
      String policyVersion,
      Instant decidedAt,
      UUID sourceRouteEvaluationId,
      String sourceRouteInputHash,
      TradeRouteCode selectedRouteCode,
      Instant inventoryDataAsOf,
      List<SupplyDecisionSnapshot.LineDecision> lineDecisions) {

    public HashInput {
      policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
      decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
      sourceRouteEvaluationId =
          Objects.requireNonNull(sourceRouteEvaluationId, "sourceRouteEvaluationId");
      sourceRouteInputHash = Objects.requireNonNull(sourceRouteInputHash, "sourceRouteInputHash");
      selectedRouteCode = Objects.requireNonNull(selectedRouteCode, "selectedRouteCode");
      inventoryDataAsOf = Objects.requireNonNull(inventoryDataAsOf, "inventoryDataAsOf");
      Objects.requireNonNull(lineDecisions, "lineDecisions");
      lineDecisions =
          lineDecisions.stream()
              .map(line -> Objects.requireNonNull(line, "lineDecision"))
              .sorted(Comparator.comparing(SupplyDecisionSnapshot.LineDecision::quotationLineId))
              .toList();
    }
  }
}
