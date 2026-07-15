package com.rom.cellarbridge.inventory.internal.application;

import com.rom.cellarbridge.catalog.CatalogItemStatus;
import com.rom.cellarbridge.catalog.CatalogSearchQuery;
import com.rom.cellarbridge.catalog.CatalogSearchQuery.CatalogSearchItem;
import com.rom.cellarbridge.catalog.CatalogSearchQuery.CatalogSkuView;
import com.rom.cellarbridge.catalog.CatalogSearchQuery.SupplyProjectionView;
import com.rom.cellarbridge.identityaccess.AuthorizationService;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.inventory.AvailabilityClass;
import com.rom.cellarbridge.inventory.InventorySupplyQuery;
import com.rom.cellarbridge.inventory.InventorySupplyQuery.ExactLotAvailability;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class CatalogSupplySearchService {

  public static final String AVAILABILITY_DISCLAIMER =
      "Availability is informational and is not an inventory commitment. Final allocation occurs during order reservation.";

  private final CatalogSearchQuery catalogSearchQuery;
  private final InventorySupplyQuery inventorySupplyQuery;
  private final TenantContextHolder contextHolder;
  private final AuthorizationService authorizationService;

  public CatalogSupplySearchService(
      CatalogSearchQuery catalogSearchQuery,
      InventorySupplyQuery inventorySupplyQuery,
      TenantContextHolder contextHolder,
      AuthorizationService authorizationService) {
    this.catalogSearchQuery = catalogSearchQuery;
    this.inventorySupplyQuery = inventorySupplyQuery;
    this.contextHolder = contextHolder;
    this.authorizationService = authorizationService;
  }

  public SearchPage search(SearchCommand command) {
    TenantContext context = requireSearchAccess();
    CatalogSearchQuery.SearchPage catalogPage =
        catalogSearchQuery.search(context.tenantId(), toCatalogCommand(command));
    List<SkuSearchItem> items = enrich(catalogPage.items(), context);
    Instant dataAsOf =
        items.stream()
            .flatMap(item -> item.supplies().stream())
            .map(SupplyView::dataAsOf)
            .max(Comparator.naturalOrder())
            .orElse(catalogPage.dataAsOf());
    return new SearchPage(
        items,
        catalogPage.nextCursor(),
        catalogPage.hasNext(),
        catalogPage.pageSize(),
        dataAsOf,
        AVAILABILITY_DISCLAIMER);
  }

  public SkuSearchItem get(UUID skuId) {
    TenantContext context = requireSearchAccess();
    CatalogSearchItem item = catalogSearchQuery.get(context.tenantId(), skuId);
    return enrich(List.of(item), context).getFirst();
  }

  private TenantContext requireSearchAccess() {
    TenantContext context = contextHolder.requireCurrent();
    authorizationService.require(PermissionCode.CATALOG_READ, context.tenantId());
    authorizationService.require(PermissionCode.INVENTORY_READ, context.tenantId());
    return context;
  }

  private List<SkuSearchItem> enrich(List<CatalogSearchItem> catalogItems, TenantContext context) {
    boolean exact = context.hasPermission(PermissionCode.INVENTORY_READ_EXACT);
    Set<UUID> authorizedPools = exact ? inventorySupplyQuery.authorizedSupplyPoolIds() : Set.of();
    Set<UUID> visiblePoolIds =
        catalogItems.stream()
            .flatMap(item -> item.supplies().stream())
            .map(SupplyProjectionView::supplyPoolId)
            .collect(Collectors.toUnmodifiableSet());
    Map<SupplyLotKey, List<ExactLotAvailability>> lotsBySupply =
        exact
            ? inventorySupplyQuery.findAuthorizedLots(visiblePoolIds).stream()
                .collect(
                    Collectors.groupingBy(
                        lot ->
                            new SupplyLotKey(
                                lot.supplyPoolId(), lot.skuId(), lot.quantityUnit())))
            : Map.of();
    return catalogItems.stream()
        .map(
            item ->
                new SkuSearchItem(
                    toSkuView(item.sku()),
                    item.supplies().stream()
                        .map(
                            projection ->
                                toSupplyView(
                                    item.sku().id(),
                                    projection,
                                    lotsBySupply,
                                    exact,
                                    authorizedPools))
                        .toList()))
        .toList();
  }

  private static SupplyView toSupplyView(
      UUID skuId,
      SupplyProjectionView projection,
      Map<SupplyLotKey, List<ExactLotAvailability>> lotsBySupply,
      boolean exact,
      Set<UUID> authorizedPools) {
    boolean canViewExact =
        exact
            && projection.automaticallyReservable()
            && authorizedPools.contains(projection.supplyPoolId());
    List<ExactLotView> exactLots =
        canViewExact
            ? lotsBySupply
                .getOrDefault(
                    new SupplyLotKey(
                        projection.supplyPoolId(),
                        skuId,
                        QuantityUnit.valueOf(projection.quantityUnit())),
                    List.of())
                .stream()
                .map(
                    lot ->
                        new ExactLotView(
                            lot.lotId(),
                            lot.lotCode(),
                            lot.warehouseLabel(),
                            lot.quantityUnit(),
                            lot.onHandQuantity(),
                            lot.reservedQuantity(),
                            lot.availableQuantity(),
                            lot.allocationPriority(),
                            lot.warehouseVersion(),
                            lot.availableFrom(),
                            lot.dataAsOf()))
                .toList()
            : List.of();
    BigDecimal displayedQuantity =
        canViewExact
            ? exactLots.stream()
                .map(ExactLotView::availableQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
            : null;
    Instant dataAsOf =
        exactLots.stream()
            .map(ExactLotView::dataAsOf)
            .max(Comparator.naturalOrder())
            .filter(value -> value.isAfter(projection.dataAsOf()))
            .orElse(projection.dataAsOf());
    return new SupplyView(
        projection.supplyPoolId(),
        SupplyType.valueOf(projection.supplyType()),
        QuantityUnit.valueOf(projection.quantityUnit()),
        projection.locationLabel(),
        AvailabilityClass.valueOf(projection.availabilityClass()),
        projection.displayQuantityBand(),
        projection.automaticallyReservable(),
        displayedQuantity,
        exactLots,
        projection.estimatedAvailableAt(),
        dataAsOf);
  }

  private static SkuView toSkuView(CatalogSkuView sku) {
    return new SkuView(
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

  private static CatalogSearchQuery.SearchCommand toCatalogCommand(SearchCommand command) {
    return new CatalogSearchQuery.SearchCommand(
        command.keyword(),
        command.producer(),
        command.region(),
        command.countryCode(),
        command.category(),
        command.vintage(),
        command.volumeMl(),
        names(command.supplyTypes()),
        names(command.availabilityClasses()),
        names(command.quantityUnits()),
        command.automaticallyReservable(),
        command.availableFrom(),
        command.availableTo(),
        command.sort(),
        command.pageSize(),
        command.cursor());
  }

  private static <T extends Enum<T>> Set<String> names(Set<T> values) {
    return values == null
        ? Set.of()
        : values.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
  }

  public record SearchCommand(
      String keyword,
      String producer,
      String region,
      String countryCode,
      String category,
      String vintage,
      Integer volumeMl,
      Set<SupplyType> supplyTypes,
      Set<AvailabilityClass> availabilityClasses,
      Set<QuantityUnit> quantityUnits,
      Boolean automaticallyReservable,
      Instant availableFrom,
      Instant availableTo,
      String sort,
      Integer pageSize,
      String cursor) {}

  public record SearchPage(
      List<SkuSearchItem> items,
      String nextCursor,
      boolean hasNext,
      int pageSize,
      Instant dataAsOf,
      String availabilityDisclaimer) {}

  public record SkuSearchItem(SkuView sku, List<SupplyView> supplies) {}

  public record SkuView(
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

  public record SupplyView(
      UUID supplyPoolId,
      SupplyType supplyType,
      QuantityUnit quantityUnit,
      String locationLabel,
      AvailabilityClass availabilityClass,
      String displayQuantityBand,
      boolean automaticallyReservable,
      BigDecimal displayedAvailableQuantity,
      List<ExactLotView> exactLots,
      Instant estimatedAvailableAt,
      Instant dataAsOf) {}

  public record ExactLotView(
      UUID lotId,
      String lotCode,
      String warehouseLabel,
      QuantityUnit quantityUnit,
      BigDecimal onHandQuantity,
      BigDecimal reservedQuantity,
      BigDecimal availableQuantity,
      int warehouseAllocationPriority,
      long warehouseVersion,
      Instant availableFrom,
      Instant dataAsOf) {}

  private record SupplyLotKey(UUID supplyPoolId, UUID skuId, QuantityUnit quantityUnit) {}
}
