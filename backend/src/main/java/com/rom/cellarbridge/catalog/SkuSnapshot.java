package com.rom.cellarbridge.catalog;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SkuSnapshot(
    UUID skuId,
    String skuCode,
    String displayName,
    String producerName,
    String regionName,
    String vintage,
    int volumeMl,
    int unitsPerCase,
    String packageType,
    long sourceVersion,
    Instant capturedAt) {

  public SkuSnapshot {
    Objects.requireNonNull(skuId, "skuId");
    requireText(skuCode, "skuCode");
    requireText(displayName, "displayName");
    requireText(producerName, "producerName");
    requireText(regionName, "regionName");
    requireText(vintage, "vintage");
    requireText(packageType, "packageType");
    if (volumeMl <= 0 || unitsPerCase <= 0 || sourceVersion < 0) {
      throw new IllegalArgumentException("SKU snapshot dimensions and version must be valid");
    }
    Objects.requireNonNull(capturedAt, "capturedAt");
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
