package com.rom.cellarbridge.catalog.internal.infrastructure;

import com.rom.cellarbridge.catalog.CatalogItemStatus;
import com.rom.cellarbridge.catalog.internal.application.CatalogSearchRepository;
import com.rom.cellarbridge.identityaccess.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCatalogSearchRepository implements CatalogSearchRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcCatalogSearchRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<CatalogSkuRecord> search(
      TenantId tenantId, SearchCriteria criteria, CursorPosition cursor, int limit) {
    MapSqlParameterSource parameters = baseParameters(tenantId, criteria).addValue("limit", limit);
    StringBuilder where =
        new StringBuilder(" WHERE s.tenant_id = :tenantId AND s.status = 'ACTIVE'");
    appendCatalogFilters(where, parameters, criteria);
    where.append(" AND EXISTS (SELECT 1 FROM catalog.sku_supply_projection sp");
    where.append(" WHERE sp.tenant_id = s.tenant_id AND sp.sku_id = s.id");
    appendSupplyFilters(where, parameters, criteria, "sp");
    where.append(")");

    String scoreExpression =
        criteria.keyword() == null
            ? "0::numeric"
            : "round((ts_rank_cd(s.search_document, websearch_to_tsquery('simple', :keyword))"
                + " + word_similarity(:keyword, s.search_text))::numeric, 6)";

    String cursorClause = cursorClause(criteria.sort(), cursor, parameters);
    String sortValue = sortValueExpression(criteria.sort());
    String ordering = ordering(criteria.sort());
    String sql =
        """
        WITH candidates AS (
          SELECT s.id,
                 s.code,
                 wp.name AS product_name,
                 wp.normalized_name AS normalized_product_name,
                 p.name AS producer_name,
                 r.name AS region_name,
                 r.country_code,
                 wp.category,
                 s.vintage_code,
                 s.volume_ml,
                 s.units_per_case,
                 s.package_type,
                 s.status,
                 s.version,
                 s.updated_at,
                 %s AS relevance_score,
                 CASE WHEN s.vintage_code = 'NV' THEN 9999
                      ELSE s.vintage_code::integer END AS vintage_sort
            FROM catalog.sku s
            JOIN catalog.wine_product wp
              ON wp.tenant_id = s.tenant_id
             AND wp.id = s.product_id
            JOIN catalog.producer p
              ON p.tenant_id = wp.tenant_id
             AND p.id = wp.producer_id
            JOIN catalog.region r
              ON r.tenant_id = wp.tenant_id
             AND r.id = wp.region_id
            %s
        )
        SELECT c.*,
               %s AS sort_value
          FROM candidates c
          %s
          %s
         LIMIT :limit
        """
            .formatted(scoreExpression, where, sortValue, cursorClause, ordering);
    return jdbc.query(sql, parameters, (resultSet, rowNumber) -> mapSku(resultSet));
  }

  @Override
  public Optional<CatalogSkuRecord> find(TenantId tenantId, UUID skuId) {
    return jdbc
        .query(
            """
            SELECT s.id,
                   s.code,
                   wp.name AS product_name,
                   p.name AS producer_name,
                   r.name AS region_name,
                   r.country_code,
                   wp.category,
                   s.vintage_code,
                   s.volume_ml,
                   s.units_per_case,
                   s.package_type,
                   s.status,
                   s.version,
                   s.updated_at,
                   s.code AS sort_value
              FROM catalog.sku s
              JOIN catalog.wine_product wp
                ON wp.tenant_id = s.tenant_id
               AND wp.id = s.product_id
              JOIN catalog.producer p
                ON p.tenant_id = wp.tenant_id
               AND p.id = wp.producer_id
              JOIN catalog.region r
                ON r.tenant_id = wp.tenant_id
               AND r.id = wp.region_id
             WHERE s.tenant_id = :tenantId
               AND s.id = :skuId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("skuId", skuId),
            (resultSet, rowNumber) -> mapSku(resultSet))
        .stream()
        .findFirst();
  }

  @Override
  public List<SupplyProjectionRecord> findSupplyProjections(
      TenantId tenantId, Set<UUID> skuIds, SearchCriteria criteria, int limit) {
    if (skuIds == null || skuIds.isEmpty()) {
      return List.of();
    }
    MapSqlParameterSource parameters =
        baseParameters(tenantId, criteria).addValue("skuIds", skuIds).addValue("limit", limit);
    StringBuilder where =
        new StringBuilder(" WHERE sp.tenant_id = :tenantId AND sp.sku_id IN (:skuIds)");
    appendSupplyFilters(where, parameters, criteria, "sp");
    return jdbc.query(
        """
        SELECT sp.sku_id,
               sp.supply_pool_id,
               sp.supply_type,
               sp.quantity_unit,
               sp.location_label,
               sp.availability_class,
               sp.display_quantity_band,
               sp.automatically_reservable,
               sp.estimated_available_at,
               sp.data_as_of
          FROM catalog.sku_supply_projection sp
          %s
         ORDER BY sp.sku_id,
                  sp.automatically_reservable DESC,
                  sp.quantity_unit,
                  sp.location_label,
                  sp.supply_pool_id
         LIMIT :limit
        """
            .formatted(where),
        parameters,
        (resultSet, rowNumber) -> mapSupply(resultSet));
  }

  private static MapSqlParameterSource baseParameters(TenantId tenantId, SearchCriteria criteria) {
    return new MapSqlParameterSource().addValue("tenantId", tenantId.value());
  }

  private static void appendCatalogFilters(
      StringBuilder sql, MapSqlParameterSource parameters, SearchCriteria criteria) {
    if (criteria.keyword() != null) {
      sql.append(
          " AND s.id IN (SELECT keyword_match.id FROM ("
              + " SELECT ks.id FROM catalog.sku ks"
              + " WHERE ks.tenant_id = :tenantId AND ks.status = 'ACTIVE'"
              + " AND ks.search_document @@ websearch_to_tsquery('simple', :keyword)"
              + " UNION SELECT ks.id FROM catalog.sku ks"
              + " WHERE ks.tenant_id = :tenantId AND ks.status = 'ACTIVE'"
              + " AND ks.search_text % :keyword"
              + " UNION SELECT ks.id FROM catalog.sku ks"
              + " WHERE ks.tenant_id = :tenantId AND ks.status = 'ACTIVE'"
              + " AND ks.search_text %> :keyword) keyword_match)");
      parameters.addValue("keyword", criteria.keyword());
    }
    addEquals(sql, parameters, "p.normalized_name", "producer", criteria.producer());
    addEquals(sql, parameters, "r.normalized_name", "region", criteria.region());
    addEquals(sql, parameters, "r.country_code", "countryCode", criteria.countryCode());
    addEquals(sql, parameters, "wp.category", "category", criteria.category());
    addEquals(sql, parameters, "s.vintage_code", "vintage", criteria.vintage());
    if (criteria.volumeMl() != null) {
      sql.append(" AND s.volume_ml = :volumeMl");
      parameters.addValue("volumeMl", criteria.volumeMl());
    }
  }

  private static void appendSupplyFilters(
      StringBuilder sql, MapSqlParameterSource parameters, SearchCriteria criteria, String alias) {
    if (!criteria.supplyTypes().isEmpty()) {
      sql.append(" AND ").append(alias).append(".supply_type IN (:supplyTypes)");
      parameters.addValue("supplyTypes", criteria.supplyTypes());
    }
    if (!criteria.availabilityClasses().isEmpty()) {
      sql.append(" AND ").append(alias).append(".availability_class IN (:availabilityClasses)");
      parameters.addValue("availabilityClasses", criteria.availabilityClasses());
    }
    if (!criteria.quantityUnits().isEmpty()) {
      sql.append(" AND ").append(alias).append(".quantity_unit IN (:quantityUnits)");
      parameters.addValue("quantityUnits", criteria.quantityUnits());
    }
    if (criteria.automaticallyReservable() != null) {
      sql.append(" AND ")
          .append(alias)
          .append(".automatically_reservable = :automaticallyReservable");
      parameters.addValue("automaticallyReservable", criteria.automaticallyReservable());
    }
    if (criteria.availableFrom() != null) {
      sql.append(" AND ").append(alias).append(".estimated_available_at >= :availableFrom");
      parameters.addValue("availableFrom", criteria.availableFrom());
    }
    if (criteria.availableTo() != null) {
      sql.append(" AND ").append(alias).append(".estimated_available_at <= :availableTo");
      parameters.addValue("availableTo", criteria.availableTo());
    }
  }

  private static String cursorClause(
      SearchSort sort, CursorPosition cursor, MapSqlParameterSource parameters) {
    if (cursor == null) {
      return "";
    }
    parameters
        .addValue("cursorSort", cursor.sortValue())
        .addValue("cursorCode", cursor.skuCode())
        .addValue("cursorId", cursor.skuId());
    String tie = "(c.code > :cursorCode OR (c.code = :cursorCode AND c.id > :cursorId))";
    return switch (sort) {
      case RELEVANCE ->
          "WHERE c.relevance_score < CAST(:cursorSort AS numeric)"
              + " OR (c.relevance_score = CAST(:cursorSort AS numeric) AND "
              + tie
              + ")";
      case NAME ->
          "WHERE c.normalized_product_name > :cursorSort"
              + " OR (c.normalized_product_name = :cursorSort AND "
              + tie
              + ")";
      case UPDATED_DESC ->
          "WHERE c.updated_at < CAST(:cursorSort AS timestamptz)"
              + " OR (c.updated_at = CAST(:cursorSort AS timestamptz) AND "
              + tie
              + ")";
      case VINTAGE ->
          "WHERE c.vintage_sort > CAST(:cursorSort AS integer)"
              + " OR (c.vintage_sort = CAST(:cursorSort AS integer) AND "
              + tie
              + ")";
    };
  }

  private static String sortValueExpression(SearchSort sort) {
    return switch (sort) {
      case RELEVANCE -> "c.relevance_score::text";
      case NAME -> "c.normalized_product_name";
      case UPDATED_DESC -> "c.updated_at::text";
      case VINTAGE -> "c.vintage_sort::text";
    };
  }

  private static String ordering(SearchSort sort) {
    return switch (sort) {
      case RELEVANCE -> "ORDER BY c.relevance_score DESC, c.code, c.id";
      case NAME -> "ORDER BY c.normalized_product_name, c.code, c.id";
      case UPDATED_DESC -> "ORDER BY c.updated_at DESC, c.code, c.id";
      case VINTAGE -> "ORDER BY c.vintage_sort, c.code, c.id";
    };
  }

  private static void addEquals(
      StringBuilder sql,
      MapSqlParameterSource parameters,
      String column,
      String parameter,
      Object value) {
    if (value != null) {
      sql.append(" AND ").append(column).append(" = :").append(parameter);
      parameters.addValue(parameter, value);
    }
  }

  private static CatalogSkuRecord mapSku(ResultSet resultSet) throws SQLException {
    return new CatalogSkuRecord(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("code"),
        resultSet.getString("product_name"),
        resultSet.getString("producer_name"),
        resultSet.getString("region_name"),
        resultSet.getString("country_code"),
        resultSet.getString("category"),
        resultSet.getString("vintage_code"),
        resultSet.getInt("volume_ml"),
        resultSet.getInt("units_per_case"),
        resultSet.getString("package_type"),
        CatalogItemStatus.valueOf(resultSet.getString("status")),
        resultSet.getLong("version"),
        resultSet.getTimestamp("updated_at").toInstant(),
        resultSet.getString("sort_value"));
  }

  private static SupplyProjectionRecord mapSupply(ResultSet resultSet) throws SQLException {
    return new SupplyProjectionRecord(
        resultSet.getObject("sku_id", UUID.class),
        resultSet.getObject("supply_pool_id", UUID.class),
        resultSet.getString("supply_type"),
        resultSet.getString("quantity_unit"),
        resultSet.getString("location_label"),
        resultSet.getString("availability_class"),
        resultSet.getString("display_quantity_band"),
        resultSet.getBoolean("automatically_reservable"),
        resultSet.getTimestamp("estimated_available_at") == null
            ? null
            : resultSet.getTimestamp("estimated_available_at").toInstant(),
        resultSet.getTimestamp("data_as_of").toInstant());
  }
}
