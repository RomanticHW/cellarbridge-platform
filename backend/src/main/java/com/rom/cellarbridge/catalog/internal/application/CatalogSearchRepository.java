package com.rom.cellarbridge.catalog.internal.application;

import com.rom.cellarbridge.catalog.CatalogItemStatus;
import com.rom.cellarbridge.catalog.CatalogQueryException;
import com.rom.cellarbridge.identityaccess.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface CatalogSearchRepository {

  List<CatalogSkuRecord> search(
      TenantId tenantId, SearchCriteria criteria, CursorPosition cursor, int limit);

  Optional<CatalogSkuRecord> find(TenantId tenantId, UUID skuId);

  List<SupplyProjectionRecord> findSupplyProjections(
      TenantId tenantId, Set<UUID> skuIds, SearchCriteria criteria);

  record SearchCriteria(
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
      SearchSort sort) {}

  record CursorPosition(String sortValue, String skuCode, UUID skuId) {}

  record CatalogSkuRecord(
      UUID id,
      String code,
      String productName,
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
      Instant updatedAt,
      String sortValue) {}

  record SupplyProjectionRecord(
      UUID skuId,
      UUID supplyPoolId,
      String supplyType,
      String quantityUnit,
      String locationLabel,
      String availabilityClass,
      String displayQuantityBand,
      boolean automaticallyReservable,
      Instant estimatedAvailableAt,
      Instant dataAsOf) {}

  enum SearchSort {
    RELEVANCE("relevance"),
    NAME("name"),
    UPDATED_DESC("-updatedAt"),
    VINTAGE("vintage");

    private final String externalValue;

    SearchSort(String externalValue) {
      this.externalValue = externalValue;
    }

    String externalValue() {
      return externalValue;
    }

    static SearchSort parse(String value) {
      for (SearchSort sort : values()) {
        if (sort.externalValue.equals(value)) {
          return sort;
        }
      }
      throw CatalogQueryException.invalidRequest(
          "Sort must be one of relevance, name, -updatedAt, or vintage");
    }
  }
}
