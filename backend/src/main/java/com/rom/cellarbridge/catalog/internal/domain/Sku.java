package com.rom.cellarbridge.catalog.internal.domain;

import com.rom.cellarbridge.catalog.CatalogItemStatus;
import com.rom.cellarbridge.catalog.SkuSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class Sku {

  private static final Pattern VINTAGE = Pattern.compile("NV|(19|20)[0-9]{2}");

  private final UUID id;
  private final UUID productId;
  private final String code;
  private final String vintage;
  private final int volumeMl;
  private final int unitsPerCase;
  private final PackageType packageType;
  private CatalogItemStatus status;
  private long version;

  private Sku(
      UUID id,
      UUID productId,
      String code,
      String vintage,
      int volumeMl,
      int unitsPerCase,
      PackageType packageType) {
    this.id = Objects.requireNonNull(id, "id");
    this.productId = Objects.requireNonNull(productId, "productId");
    this.code = requireText(code, "code");
    this.vintage = requireVintage(vintage);
    if (volumeMl <= 0 || unitsPerCase <= 0) {
      throw new IllegalArgumentException("SKU volume and units per case must be positive");
    }
    this.volumeMl = volumeMl;
    this.unitsPerCase = unitsPerCase;
    this.packageType = Objects.requireNonNull(packageType, "packageType");
    this.status = CatalogItemStatus.DRAFT;
  }

  public static Sku create(
      UUID id,
      UUID productId,
      String code,
      String vintage,
      int volumeMl,
      int unitsPerCase,
      PackageType packageType) {
    return new Sku(id, productId, code, vintage, volumeMl, unitsPerCase, packageType);
  }

  public void activate() {
    if (status != CatalogItemStatus.DRAFT && status != CatalogItemStatus.INACTIVE) {
      throw new IllegalStateException("Only draft or inactive SKUs can be activated");
    }
    status = CatalogItemStatus.ACTIVE;
    version++;
  }

  public void deactivate() {
    if (status != CatalogItemStatus.ACTIVE) {
      throw new IllegalStateException("Only active SKUs can be deactivated");
    }
    status = CatalogItemStatus.INACTIVE;
    version++;
  }

  public SkuSnapshot snapshot(
      String displayName, String producerName, String regionName, Clock clock) {
    return new SkuSnapshot(
        id,
        code,
        displayName,
        producerName,
        regionName,
        vintage,
        volumeMl,
        unitsPerCase,
        packageType.name(),
        version,
        Instant.now(clock));
  }

  public UUID id() {
    return id;
  }

  public UUID productId() {
    return productId;
  }

  public String code() {
    return code;
  }

  public String vintage() {
    return vintage;
  }

  public int volumeMl() {
    return volumeMl;
  }

  public int unitsPerCase() {
    return unitsPerCase;
  }

  public PackageType packageType() {
    return packageType;
  }

  public CatalogItemStatus status() {
    return status;
  }

  public long version() {
    return version;
  }

  private static String requireVintage(String value) {
    String normalized = requireText(value, "vintage").toUpperCase(java.util.Locale.ROOT);
    if (!VINTAGE.matcher(normalized).matches()) {
      throw new IllegalArgumentException("Vintage must be NV or a four-digit year");
    }
    return normalized;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.strip();
  }

  public enum PackageType {
    CASE,
    WOODEN_CASE,
    GIFT_BOX,
    BOTTLE
  }
}
