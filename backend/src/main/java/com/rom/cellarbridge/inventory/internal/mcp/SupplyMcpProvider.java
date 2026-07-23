package com.rom.cellarbridge.inventory.internal.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rom.cellarbridge.catalog.CatalogItemStatus;
import com.rom.cellarbridge.catalog.CatalogQueryException;
import com.rom.cellarbridge.inventory.AvailabilityClass;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.inventory.internal.application.CatalogSupplySearchService;
import com.rom.cellarbridge.inventory.internal.application.CatalogSupplySearchService.ExactLotView;
import com.rom.cellarbridge.inventory.internal.application.CatalogSupplySearchService.SearchCommand;
import com.rom.cellarbridge.inventory.internal.application.CatalogSupplySearchService.SearchPage;
import com.rom.cellarbridge.inventory.internal.application.CatalogSupplySearchService.SkuSearchItem;
import com.rom.cellarbridge.inventory.internal.application.CatalogSupplySearchService.SkuView;
import com.rom.cellarbridge.inventory.internal.application.CatalogSupplySearchService.SupplyView;
import com.rom.cellarbridge.platform.mcp.McpCallSupport;
import com.rom.cellarbridge.platform.mcp.McpCapabilitySupport;
import com.rom.cellarbridge.platform.mcp.McpCapabilitySupport.Arguments;
import com.rom.cellarbridge.platform.mcp.McpReadPayload;
import com.rom.cellarbridge.platform.mcp.McpSafeException;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public final class SupplyMcpProvider {

  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Pattern VINTAGE = Pattern.compile("^(NV|19[0-9]{2}|20[0-9]{2})$");
  private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());
  private static final Set<String> CATEGORIES =
      Set.of("RED", "WHITE", "ROSE", "SPARKLING", "FORTIFIED", "DESSERT", "OTHER");
  private static final Set<String> SORTS = Set.of("relevance", "name", "-updatedAt", "vintage");
  private static final Map<String, Object> SEARCH_PROPERTIES = searchProperties();

  private final CatalogSupplySearchService service;
  private final McpCallSupport calls;
  private final Clock clock;

  public SupplyMcpProvider(CatalogSupplySearchService service, McpCallSupport calls, Clock clock) {
    this.service = service;
    this.calls = calls;
    this.clock = clock;
  }

  @Bean
  SyncToolSpecification searchSupplyMcpTool() {
    return McpCapabilitySupport.readOnlyTool(
        calls,
        "SUPPLY_PROJECTION",
        "cellarbridge_search_supply",
        "Search CellarBridge supply",
        "Searches tenant-scoped SKU and supply projections with existing exact-lot and warehouse field permissions.",
        SEARCH_PROPERTIES,
        List.of(),
        this::searchSupply);
  }

  private McpReadPayload searchSupply(Arguments arguments) {
    SearchCommand command =
        validatedCommand(
            arguments.text("keyword"),
            arguments.text("producer"),
            arguments.text("region"),
            arguments.text("countryCode"),
            arguments.text("category"),
            arguments.text("vintage"),
            arguments.integer("volumeMl"),
            arguments.textList("supplyTypes"),
            arguments.textList("availabilityClasses"),
            arguments.textList("quantityUnits"),
            arguments.bool("automaticallyReservable"),
            arguments.text("availableFrom"),
            arguments.text("availableTo"),
            arguments.text("sort"),
            arguments.integer("pageSize"),
            arguments.text("cursor"));
    SearchPage page;
    try {
      page = service.search(command);
    } catch (CatalogQueryException exception) {
      throw McpSafeException.invalidRequest();
    }
    String status = page.items().isEmpty() ? "EMPTY" : projectionStatus(page.dataAsOf());
    return McpReadPayload.projection(
        page.dataAsOf(), status, List.of(page.availabilityDisclaimer()), SearchData.from(page));
  }

  @McpPrompt(
      name = "cellarbridge_supply_search_brief",
      title = "Supply search brief",
      description = "Guide a permission-filtered SKU and supply availability investigation.")
  public GetPromptResult supplySearchBrief(GetPromptRequest request) {
    McpCapabilitySupport.requireNoPromptArguments(request);
    return prompt(
        "Permission-filtered supply investigation",
        """
        Investigate CellarBridge supply without changing inventory.
        1. Call cellarbridge_current_user, then cellarbridge_search_supply using only filters the
           user supplied.
        2. Preserve the availability disclaimer, freshness, quantity units, and warnings.
        3. Use cellarbridge://catalog/skus/{skuId} only for a selected UUID returned by the search.
        4. Never infer hidden exact lots, warehouse assignments, quantities, or another tenant's
           inventory from missing or redacted fields.
        5. Treat availability as informational; final allocation occurs in the product workflow.
        """);
  }

  @McpResource(
      name = "cellarbridge_catalog_sku",
      title = "CellarBridge SKU supply",
      uri = "cellarbridge://catalog/skus/{skuId}",
      description = "One tenant-scoped SKU and its permission-filtered supply projection.",
      mimeType = "application/json")
  public String skuResource(String skuId) {
    return calls.json(
        calls.read(
            "SUPPLY_PROJECTION",
            () -> {
              UUID id = uuid(skuId);
              SkuSearchItem item;
              try {
                item = service.get(id);
              } catch (CatalogQueryException exception) {
                if (exception.code() == CatalogQueryException.Code.NOT_FOUND) {
                  throw McpSafeException.notFound();
                }
                throw McpSafeException.invalidRequest();
              }
              Instant dataAsOf =
                  item.supplies().stream()
                      .map(SupplyView::dataAsOf)
                      .max(Instant::compareTo)
                      .orElse(item.sku().updatedAt());
              return McpReadPayload.projection(
                  dataAsOf,
                  projectionStatus(dataAsOf),
                  List.of(CatalogSupplySearchService.AVAILABILITY_DISCLAIMER),
                  SkuData.from(item));
            }));
  }

  private SearchCommand validatedCommand(
      String keyword,
      String producer,
      String region,
      String countryCode,
      String category,
      String vintage,
      Integer volumeMl,
      List<String> supplyTypes,
      List<String> availabilityClasses,
      List<String> quantityUnits,
      Boolean automaticallyReservable,
      String availableFrom,
      String availableTo,
      String sort,
      Integer pageSize,
      String cursor) {
    String normalizedCountry = uppercase(optionalText(countryCode, 2, 2));
    if (normalizedCountry != null
        && (!COUNTRY.matcher(normalizedCountry).matches()
            || !ISO_COUNTRIES.contains(normalizedCountry))) {
      throw McpSafeException.invalidRequest();
    }
    String normalizedCategory = uppercase(optionalText(category, 1, 20));
    if (normalizedCategory != null && !CATEGORIES.contains(normalizedCategory)) {
      throw McpSafeException.invalidRequest();
    }
    String normalizedVintage = uppercase(optionalText(vintage, 2, 4));
    if (normalizedVintage != null && !VINTAGE.matcher(normalizedVintage).matches()) {
      throw McpSafeException.invalidRequest();
    }
    if (volumeMl != null && volumeMl < 1) {
      throw McpSafeException.invalidRequest();
    }
    String normalizedSort = optionalText(sort, 1, 40);
    normalizedSort = normalizedSort == null ? "relevance" : normalizedSort;
    if (!SORTS.contains(normalizedSort)) {
      throw McpSafeException.invalidRequest();
    }
    if (pageSize != null && (pageSize < 1 || pageSize > 100)) {
      throw McpSafeException.invalidRequest();
    }
    Instant from = instant(availableFrom);
    Instant to = instant(availableTo);
    if (from != null && to != null && to.isBefore(from)) {
      throw McpSafeException.invalidRequest();
    }
    return new SearchCommand(
        optionalText(keyword, 1, 100),
        optionalText(producer, 1, 160),
        optionalText(region, 1, 160),
        normalizedCountry,
        normalizedCategory,
        normalizedVintage,
        volumeMl,
        enumSet(supplyTypes, SupplyType.class),
        enumSet(availabilityClasses, AvailabilityClass.class),
        enumSet(quantityUnits, QuantityUnit.class),
        automaticallyReservable,
        from,
        to,
        normalizedSort,
        pageSize,
        optionalText(cursor, 1, 2048));
  }

  private String projectionStatus(Instant dataAsOf) {
    if (dataAsOf == null) {
      return "EMPTY";
    }
    return dataAsOf.isBefore(clock.instant().minusSeconds(10)) ? "STALE" : "CURRENT";
  }

  private static String optionalText(String value, int min, int max) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    if (normalized.length() < min || normalized.length() > max) {
      throw McpSafeException.invalidRequest();
    }
    return normalized;
  }

  private static String uppercase(String value) {
    return value == null ? null : value.toUpperCase(Locale.ROOT);
  }

  private static Instant instant(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (RuntimeException exception) {
      throw McpSafeException.invalidRequest();
    }
  }

  private static UUID uuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw McpSafeException.invalidRequest();
      }
      return parsed;
    } catch (RuntimeException exception) {
      throw McpSafeException.invalidRequest();
    }
  }

  private static <T extends Enum<T>> Set<T> enumSet(List<String> values, Class<T> type) {
    if (values == null || values.isEmpty()) {
      return Set.of();
    }
    if (values.size() > 20) {
      throw McpSafeException.invalidRequest();
    }
    try {
      return values.stream()
          .map(value -> Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT)))
          .collect(Collectors.toUnmodifiableSet());
    } catch (RuntimeException exception) {
      throw McpSafeException.invalidRequest();
    }
  }

  private static String quantity(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  private static Map<String, Object> searchProperties() {
    return Map.ofEntries(
        Map.entry(
            "keyword", McpCapabilitySupport.text("SKU name or code; 1 to 100 characters", 1, 100)),
        Map.entry(
            "producer", McpCapabilitySupport.text("Producer name; at most 160 characters", 1, 160)),
        Map.entry(
            "region", McpCapabilitySupport.text("Region name; at most 160 characters", 1, 160)),
        Map.entry(
            "countryCode",
            McpCapabilitySupport.text("ISO 3166-1 alpha-2 uppercase country code", 2, 2)),
        Map.entry(
            "category",
            McpCapabilitySupport.enumeratedText(
                "Wine category", CATEGORIES.stream().sorted().toList(), false)),
        Map.entry(
            "vintage", McpCapabilitySupport.text("NV or a four-digit vintage from 1900", 2, 4)),
        Map.entry(
            "volumeMl",
            McpCapabilitySupport.integer("Positive bottle volume in millilitres", 1, 100000)),
        Map.entry(
            "supplyTypes",
            McpCapabilitySupport.textArray("Supply types", enumNames(SupplyType.class), 20)),
        Map.entry(
            "availabilityClasses",
            McpCapabilitySupport.textArray(
                "Availability classes", enumNames(AvailabilityClass.class), 20)),
        Map.entry(
            "quantityUnits",
            McpCapabilitySupport.textArray("Quantity units", enumNames(QuantityUnit.class), 20)),
        Map.entry(
            "automaticallyReservable",
            McpCapabilitySupport.bool("Filter automatically reservable supply")),
        Map.entry(
            "availableFrom",
            McpCapabilitySupport.formattedText(
                "Inclusive ISO-8601 availability start", "date-time", 64)),
        Map.entry(
            "availableTo",
            McpCapabilitySupport.formattedText(
                "Inclusive ISO-8601 availability end", "date-time", 64)),
        Map.entry(
            "sort",
            McpCapabilitySupport.enumeratedText(
                "Supply sort order", SORTS.stream().sorted().toList(), false)),
        Map.entry("pageSize", McpCapabilitySupport.integer("Page size from 1 to 100", 1, 100)),
        Map.entry(
            "cursor", McpCapabilitySupport.text("Opaque cursor returned by this tool", 1, 2048)));
  }

  private static <T extends Enum<T>> List<String> enumNames(Class<T> type) {
    return Arrays.stream(type.getEnumConstants()).map(Enum::name).sorted().toList();
  }

  private static GetPromptResult prompt(String description, String text) {
    return new GetPromptResult(
        description, List.of(new PromptMessage(Role.USER, new TextContent(text))));
  }

  public record SearchData(
      List<SkuData> items, PageData pageInfo, Instant dataAsOf, String availabilityDisclaimer) {
    static SearchData from(SearchPage page) {
      return new SearchData(
          page.items().stream().map(SkuData::from).toList(),
          new PageData(page.nextCursor(), page.hasNext(), page.pageSize()),
          page.dataAsOf(),
          page.availabilityDisclaimer());
    }
  }

  public record PageData(String nextCursor, boolean hasNext, int pageSize) {}

  public record SkuData(SkuSummary sku, List<SupplyData> supplies) {
    static SkuData from(SkuSearchItem item) {
      return new SkuData(
          SkuSummary.from(item.sku()), item.supplies().stream().map(SupplyData::from).toList());
    }
  }

  public record SkuSummary(
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
    static SkuSummary from(SkuView sku) {
      return new SkuSummary(
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

  public record SupplyData(
      UUID supplyPoolId,
      SupplyType supplyType,
      QuantityUnit quantityUnit,
      String locationLabel,
      AvailabilityClass availabilityClass,
      String displayQuantityBand,
      boolean automaticallyReservable,
      @JsonInclude(JsonInclude.Include.NON_NULL) String displayedAvailableQuantity,
      List<ExactLotData> exactLots,
      Instant estimatedAvailableAt,
      Instant dataAsOf) {
    static SupplyData from(SupplyView supply) {
      return new SupplyData(
          supply.supplyPoolId(),
          supply.supplyType(),
          supply.quantityUnit(),
          supply.locationLabel(),
          supply.availabilityClass(),
          supply.displayQuantityBand(),
          supply.automaticallyReservable(),
          quantity(supply.displayedAvailableQuantity()),
          supply.exactLots().stream().map(ExactLotData::from).toList(),
          supply.estimatedAvailableAt(),
          supply.dataAsOf());
    }
  }

  public record ExactLotData(
      UUID lotId,
      String lotCode,
      String warehouseLabel,
      QuantityUnit quantityUnit,
      String onHandQuantity,
      String reservedQuantity,
      String availableQuantity,
      int warehouseAllocationPriority,
      long warehouseVersion,
      Instant availableFrom,
      Instant dataAsOf) {
    static ExactLotData from(ExactLotView lot) {
      return new ExactLotData(
          lot.lotId(),
          lot.lotCode(),
          lot.warehouseLabel(),
          lot.quantityUnit(),
          quantity(lot.onHandQuantity()),
          quantity(lot.reservedQuantity()),
          quantity(lot.availableQuantity()),
          lot.warehouseAllocationPriority(),
          lot.warehouseVersion(),
          lot.availableFrom(),
          lot.dataAsOf());
    }
  }
}
