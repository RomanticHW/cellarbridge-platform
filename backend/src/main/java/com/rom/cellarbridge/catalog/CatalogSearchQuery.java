package com.rom.cellarbridge.catalog;

import com.rom.cellarbridge.identityaccess.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Public, tenant-explicit Catalog query contract for application-level composition. */
public interface CatalogSearchQuery {

  SearchPage search(TenantId tenantId, SearchCommand command);

  default CatalogSearchItem get(TenantId tenantId, UUID skuId) {
    return get(tenantId, skuId, Integer.MAX_VALUE);
  }

  CatalogSearchItem get(TenantId tenantId, UUID skuId, int maxSupplyProjections);

  record SearchCommand(
      String keyword,
      String producer,
      String region,
      String countryCode,
      String category,
      String vintage,
      Integer volumeMl,
      Set<String> supplyTypes,
      Set<String> availabilityClasses,
      Set<String> quantityUnits,
      Boolean automaticallyReservable,
      Instant availableFrom,
      Instant availableTo,
      String sort,
      Integer pageSize,
      String cursor,
      String authorizationScope,
      int maxSupplyProjections) {}

  record SearchPage(
      List<CatalogSearchItem> items,
      String nextCursor,
      boolean hasNext,
      int pageSize,
      Instant dataAsOf) {}

  record CatalogSearchItem(CatalogSkuView sku, List<SupplyProjectionView> supplies) {}

  record CatalogSkuView(
      UUID id,
      String code,
      String displayName,
      String producerName,
      String regionName,
      String countryCode,
      String category,
      String vintage,
      int volumeMl,
      int unitsPerCase,
      String packageType,
      CatalogItemStatus status,
      long version,
      Instant updatedAt) {}

  record SupplyProjectionView(
      UUID supplyPoolId,
      String supplyType,
      String quantityUnit,
      String locationLabel,
      String availabilityClass,
      String displayQuantityBand,
      boolean automaticallyReservable,
      Instant estimatedAvailableAt,
      Instant dataAsOf) {}
}
