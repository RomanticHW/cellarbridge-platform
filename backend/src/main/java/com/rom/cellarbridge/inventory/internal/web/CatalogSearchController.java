package com.rom.cellarbridge.inventory.internal.web;

import com.rom.cellarbridge.catalog.CatalogItemStatus;
import com.rom.cellarbridge.inventory.AvailabilityClass;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.inventory.internal.application.CatalogSupplySearchService;
import com.rom.cellarbridge.inventory.internal.application.CatalogSupplySearchService.ExactLotView;
import com.rom.cellarbridge.inventory.internal.application.CatalogSupplySearchService.SearchCommand;
import com.rom.cellarbridge.inventory.internal.application.CatalogSupplySearchService.SearchPage;
import com.rom.cellarbridge.inventory.internal.application.CatalogSupplySearchService.SkuSearchItem;
import com.rom.cellarbridge.inventory.internal.application.CatalogSupplySearchService.SkuView;
import com.rom.cellarbridge.inventory.internal.application.CatalogSupplySearchService.SupplyView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog/skus")
final class CatalogSearchController {

  private final CatalogSupplySearchService service;

  CatalogSearchController(CatalogSupplySearchService service) {
    this.service = service;
  }

  @GetMapping
  ResponseEntity<SkuSearchPageResponse> search(
      @RequestParam(required = false) @Size(min = 1, max = 100) String keyword,
      @RequestParam(required = false) @Size(max = 160) String producer,
      @RequestParam(required = false) @Size(max = 160) String region,
      @RequestParam(required = false) @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
      @RequestParam(required = false)
          @Pattern(regexp = "^(RED|WHITE|ROSE|SPARKLING|FORTIFIED|DESSERT|OTHER)$")
          String category,
      @RequestParam(required = false) @Pattern(regexp = "^(NV|19[0-9]{2}|20[0-9]{2})$")
          String vintage,
      @RequestParam(required = false) @Min(1) Integer volumeMl,
      @RequestParam(required = false) Set<SupplyType> supplyType,
      @RequestParam(required = false) Set<AvailabilityClass> availabilityClass,
      @RequestParam(required = false) Boolean automaticallyReservable,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant availableFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant availableTo,
      @RequestParam(required = false, defaultValue = "relevance") String sort,
      @RequestParam(required = false) @Min(1) @Max(100) Integer pageSize,
      @RequestParam(required = false) String cursor) {
    SearchPage page =
        service.search(
            new SearchCommand(
                keyword,
                producer,
                region,
                countryCode,
                category,
                vintage,
                volumeMl,
                supplyType == null ? Set.of() : supplyType,
                availabilityClass == null ? Set.of() : availabilityClass,
                automaticallyReservable,
                availableFrom,
                availableTo,
                sort,
                pageSize,
                cursor));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(SkuSearchPageResponse.from(page));
  }

  @GetMapping("/{skuId}")
  ResponseEntity<SkuSearchItemResponse> get(@PathVariable UUID skuId) {
    SkuSearchItem item = service.get(skuId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag('"' + Long.toString(item.sku().version()) + '"')
        .body(SkuSearchItemResponse.from(item));
  }

  record PageInfoResponse(String nextCursor, boolean hasNext, int pageSize) {}

  record SkuSearchPageResponse(
      List<SkuSearchItemResponse> items,
      PageInfoResponse pageInfo,
      Instant dataAsOf,
      String availabilityDisclaimer) {
    static SkuSearchPageResponse from(SearchPage page) {
      return new SkuSearchPageResponse(
          page.items().stream().map(SkuSearchItemResponse::from).toList(),
          new PageInfoResponse(page.nextCursor(), page.hasNext(), page.pageSize()),
          page.dataAsOf(),
          page.availabilityDisclaimer());
    }
  }

  record SkuSearchItemResponse(SkuResponse sku, List<SupplyResponse> supplies) {
    static SkuSearchItemResponse from(SkuSearchItem item) {
      return new SkuSearchItemResponse(
          SkuResponse.from(item.sku()),
          item.supplies().stream().map(SupplyResponse::from).toList());
    }
  }

  record SkuResponse(
      UUID skuId,
      String skuCode,
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
      long sourceVersion,
      Instant updatedAt) {
    static SkuResponse from(SkuView sku) {
      return new SkuResponse(
          sku.id(),
          sku.code(),
          sku.displayName(),
          sku.producerName(),
          sku.regionName(),
          sku.countryCode(),
          sku.category(),
          sku.vintage(),
          sku.volumeMl(),
          sku.unitsPerCase(),
          sku.packageType(),
          sku.status(),
          sku.version(),
          sku.updatedAt());
    }
  }

  record QuantityResponse(String value, String unit) {
    static QuantityResponse cases(BigDecimal value) {
      return value == null
          ? null
          : new QuantityResponse(value.stripTrailingZeros().toPlainString(), "CASE");
    }
  }

  record ExactLotResponse(
      UUID lotId,
      String lotCode,
      String warehouseLabel,
      QuantityResponse onHandQuantity,
      QuantityResponse reservedQuantity,
      QuantityResponse availableQuantity,
      Instant availableFrom,
      Instant dataAsOf) {
    static ExactLotResponse from(ExactLotView lot) {
      return new ExactLotResponse(
          lot.lotId(),
          lot.lotCode(),
          lot.warehouseLabel(),
          QuantityResponse.cases(lot.onHandQuantity()),
          QuantityResponse.cases(lot.reservedQuantity()),
          QuantityResponse.cases(lot.availableQuantity()),
          lot.availableFrom(),
          lot.dataAsOf());
    }
  }

  record SupplyResponse(
      UUID supplyPoolId,
      SupplyType supplyType,
      String locationLabel,
      AvailabilityClass availabilityLevel,
      String displayQuantityBand,
      boolean automaticallyReservable,
      QuantityResponse displayedAvailableQuantity,
      List<ExactLotResponse> exactLots,
      Instant estimatedAvailableAt,
      Instant updatedAt) {
    static SupplyResponse from(SupplyView supply) {
      return new SupplyResponse(
          supply.supplyPoolId(),
          supply.supplyType(),
          supply.locationLabel(),
          supply.availabilityClass(),
          supply.displayQuantityBand(),
          supply.automaticallyReservable(),
          QuantityResponse.cases(supply.displayedAvailableQuantity()),
          supply.exactLots().stream().map(ExactLotResponse::from).toList(),
          supply.estimatedAvailableAt(),
          supply.dataAsOf());
    }
  }
}
