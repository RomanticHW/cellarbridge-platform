package com.rom.cellarbridge.catalog.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.catalog.CatalogItemStatus;
import com.rom.cellarbridge.catalog.SkuSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogDomainTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void supportsNvAndPreservesAnImmutableSnapshotAcrossProductRename() {
    WineProduct product =
        WineProduct.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Moonlit Terrace",
            WineProduct.Category.RED,
            "Dark fruit and cedar profile.",
            Set.of("structured", "cellar-selection"));
    Sku sku =
        Sku.create(
            UUID.randomUUID(), product.id(), "CB-MT-NV-750X6", "nv", 750, 6, Sku.PackageType.CASE);
    sku.activate();

    SkuSnapshot captured =
        sku.snapshot(product.name(), "Silver Vale Estate", "Lumen Valley", CLOCK);
    product.updateContent("Moonlit Terrace Reserve", "Updated description.", Set.of("reserve"));

    assertThat(sku.status()).isEqualTo(CatalogItemStatus.ACTIVE);
    assertThat(captured.displayName()).isEqualTo("Moonlit Terrace");
    assertThat(captured.vintage()).isEqualTo("NV");
    assertThat(captured.capturedAt()).isEqualTo(Instant.parse("2026-07-13T12:00:00Z"));
  }

  @Test
  void deactivatesWithoutChangingTheStableSkuDefinition() {
    Sku sku =
        Sku.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "CB-AUR-2022-750X12",
            "2022",
            750,
            12,
            Sku.PackageType.WOODEN_CASE);

    sku.activate();
    sku.deactivate();

    assertThat(sku.status()).isEqualTo(CatalogItemStatus.INACTIVE);
    assertThat(sku.code()).isEqualTo("CB-AUR-2022-750X12");
    assertThat(sku.version()).isEqualTo(2);
  }

  @Test
  void rejectsInvalidVintageAndNonPositivePackageDimensions() {
    assertThatThrownBy(
            () ->
                Sku.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "CB-BAD",
                    "1899",
                    750,
                    6,
                    Sku.PackageType.CASE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                Sku.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "CB-BAD",
                    "2022",
                    0,
                    6,
                    Sku.PackageType.CASE))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
