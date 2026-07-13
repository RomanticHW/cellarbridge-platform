package com.rom.cellarbridge.catalog.internal.application;

import com.rom.cellarbridge.catalog.CatalogQueryException;
import com.rom.cellarbridge.catalog.CatalogSearchQuery;
import com.rom.cellarbridge.catalog.CatalogSearchQuery.CatalogSearchItem;
import com.rom.cellarbridge.catalog.CatalogSearchQuery.CatalogSkuView;
import com.rom.cellarbridge.catalog.CatalogSearchQuery.SearchCommand;
import com.rom.cellarbridge.catalog.CatalogSearchQuery.SearchPage;
import com.rom.cellarbridge.catalog.CatalogSearchQuery.SupplyProjectionView;
import com.rom.cellarbridge.catalog.internal.application.CatalogSearchRepository.CatalogSkuRecord;
import com.rom.cellarbridge.catalog.internal.application.CatalogSearchRepository.CursorPosition;
import com.rom.cellarbridge.catalog.internal.application.CatalogSearchRepository.SearchCriteria;
import com.rom.cellarbridge.catalog.internal.application.CatalogSearchRepository.SearchSort;
import com.rom.cellarbridge.catalog.internal.application.CatalogSearchRepository.SupplyProjectionRecord;
import com.rom.cellarbridge.identityaccess.TenantId;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class CatalogSearchService implements CatalogSearchQuery {

  private static final int DEFAULT_PAGE_SIZE = 25;
  private static final int MAX_PAGE_SIZE = 100;
  private final CatalogSearchRepository repository;
  private final CatalogCursorCodec cursorCodec;
  private final Clock clock;

  public CatalogSearchService(
      CatalogSearchRepository repository, CatalogCursorCodec cursorCodec, Clock clock) {
    this.repository = repository;
    this.cursorCodec = cursorCodec;
    this.clock = clock;
  }

  @Override
  public SearchPage search(TenantId tenantId, SearchCommand command) {
    int pageSize = normalizePageSize(command.pageSize());
    SearchSort sort = SearchSort.parse(command.sort() == null ? "relevance" : command.sort());
    SearchCriteria criteria = criteria(command, sort);
    String filterHash = CatalogCursorCodec.filterHash(canonicalFilter(criteria));
    CursorPosition cursor = cursorCodec.decode(tenantId, filterHash, sort, command.cursor());

    List<CatalogSkuRecord> fetched = repository.search(tenantId, criteria, cursor, pageSize + 1);
    boolean hasNext = fetched.size() > pageSize;
    List<CatalogSkuRecord> visible =
        hasNext ? List.copyOf(fetched.subList(0, pageSize)) : List.copyOf(fetched);
    List<SupplyProjectionRecord> projections =
        repository.findSupplyProjections(
            tenantId,
            visible.stream().map(CatalogSkuRecord::id).collect(Collectors.toUnmodifiableSet()),
            criteria);
    List<CatalogSearchItem> items = assemble(visible, projections);
    String nextCursor =
        hasNext ? encodeCursor(tenantId, filterHash, sort, visible.getLast()) : null;
    Instant dataAsOf =
        projections.stream()
            .map(SupplyProjectionRecord::dataAsOf)
            .max(Comparator.naturalOrder())
            .orElseGet(clock::instant);
    return new SearchPage(items, nextCursor, hasNext, pageSize, dataAsOf);
  }

  @Override
  public CatalogSearchItem get(TenantId tenantId, UUID skuId) {
    CatalogSkuRecord sku =
        repository.find(tenantId, skuId).orElseThrow(CatalogQueryException::notFound);
    SearchCriteria criteria =
        new SearchCriteria(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Set.of(),
            Set.of(),
            null,
            null,
            null,
            SearchSort.NAME);
    List<SupplyProjectionRecord> projections =
        repository.findSupplyProjections(tenantId, Set.of(skuId), criteria);
    return assemble(List.of(sku), projections).getFirst();
  }

  private static List<CatalogSearchItem> assemble(
      List<CatalogSkuRecord> skus, List<SupplyProjectionRecord> projections) {
    Map<UUID, List<SupplyProjectionRecord>> suppliesBySku =
        projections.stream().collect(Collectors.groupingBy(SupplyProjectionRecord::skuId));
    List<CatalogSearchItem> items = new ArrayList<>(skus.size());
    for (CatalogSkuRecord sku : skus) {
      List<SupplyProjectionView> supplies =
          suppliesBySku.getOrDefault(sku.id(), List.of()).stream()
              .map(CatalogSearchService::toSupplyView)
              .toList();
      items.add(new CatalogSearchItem(toSkuView(sku), supplies));
    }
    return List.copyOf(items);
  }

  private static SupplyProjectionView toSupplyView(SupplyProjectionRecord projection) {
    return new SupplyProjectionView(
        projection.supplyPoolId(),
        projection.supplyType(),
        projection.locationLabel(),
        projection.availabilityClass(),
        projection.displayQuantityBand(),
        projection.automaticallyReservable(),
        projection.estimatedAvailableAt(),
        projection.dataAsOf());
  }

  private static CatalogSkuView toSkuView(CatalogSkuRecord sku) {
    return new CatalogSkuView(
        sku.id(),
        sku.code(),
        sku.productName(),
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

  private static SearchCriteria criteria(SearchCommand command, SearchSort sort) {
    String keyword = normalizeKeyword(command.keyword());
    Instant availableFrom = command.availableFrom();
    Instant availableTo = command.availableTo();
    if (availableFrom != null && availableTo != null && availableFrom.isAfter(availableTo)) {
      throw CatalogQueryException.invalidRequest("availableFrom cannot be after availableTo");
    }
    return new SearchCriteria(
        keyword,
        normalizeFilter(command.producer()),
        normalizeFilter(command.region()),
        upper(command.countryCode()),
        upper(command.category()),
        upper(command.vintage()),
        command.volumeMl(),
        immutableSet(command.supplyTypes()),
        immutableSet(command.availabilityClasses()),
        command.automaticallyReservable(),
        availableFrom,
        availableTo,
        sort);
  }

  private static int normalizePageSize(Integer requested) {
    int pageSize = requested == null ? DEFAULT_PAGE_SIZE : requested;
    if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw CatalogQueryException.invalidRequest("pageSize must be between 1 and 100");
    }
    return pageSize;
  }

  private static String normalizeKeyword(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (value.indexOf('%') >= 0 || value.indexOf('*') >= 0 || value.indexOf('_') >= 0) {
      throw CatalogQueryException.invalidRequest("Wildcard search syntax is not supported");
    }
    String normalized = normalizeFilter(value).replaceAll("[^\\p{L}\\p{N} -]", " ");
    normalized = normalized.replaceAll("\\s+", " ").strip();
    if (normalized.isEmpty() || normalized.length() > 100) {
      throw CatalogQueryException.invalidRequest(
          "keyword must contain 1 to 100 searchable characters");
    }
    if (normalized.split(" ").length > 8) {
      throw CatalogQueryException.invalidRequest("keyword can contain at most 8 terms");
    }
    return normalized;
  }

  private static String normalizeFilter(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String decomposed = Normalizer.normalize(value.strip(), Normalizer.Form.NFD);
    return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
  }

  private static String upper(String value) {
    return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
  }

  private static <T> Set<T> immutableSet(Set<T> values) {
    return values == null ? Set.of() : Set.copyOf(values);
  }

  private static String canonicalFilter(SearchCriteria criteria) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("keyword", criteria.keyword());
    values.put("producer", criteria.producer());
    values.put("region", criteria.region());
    values.put("country", criteria.countryCode());
    values.put("category", criteria.category());
    values.put("vintage", criteria.vintage());
    values.put("volume", criteria.volumeMl());
    values.put("supply", sorted(criteria.supplyTypes(), Function.identity()));
    values.put("availability", sorted(criteria.availabilityClasses(), Function.identity()));
    values.put("automatic", criteria.automaticallyReservable());
    values.put("from", criteria.availableFrom());
    values.put("to", criteria.availableTo());
    values.put("sort", criteria.sort().externalValue());
    return values.toString();
  }

  private static <T> List<String> sorted(Set<T> values, Function<T, String> mapper) {
    return values.stream().map(mapper).sorted().toList();
  }

  private String encodeCursor(
      TenantId tenantId, String filterHash, SearchSort sort, CatalogSkuRecord last) {
    return cursorCodec.encode(
        tenantId, filterHash, sort, new CursorPosition(last.sortValue(), last.code(), last.id()));
  }
}
