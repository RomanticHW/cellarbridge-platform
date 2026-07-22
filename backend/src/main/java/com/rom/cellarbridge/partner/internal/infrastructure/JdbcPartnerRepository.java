package com.rom.cellarbridge.partner.internal.infrastructure;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.partner.PartnerStatus;
import com.rom.cellarbridge.partner.internal.application.PartnerRepository;
import com.rom.cellarbridge.partner.internal.domain.Partner;
import com.rom.cellarbridge.platform.PendingEvent;
import com.rom.cellarbridge.platform.ReliableEventPublisher;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcPartnerRepository implements PartnerRepository {

  private static final DateTimeFormatter NUMBER_PERIOD =
      DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);
  private static final UUID NO_PARTNER = new UUID(0, 0);
  private static final String PARTNER_COLUMNS =
      """
      p.id, p.tenant_id, p.number, p.legal_name, p.display_name,
      p.registration_identifier, p.partner_type, p.status, p.default_currency,
      p.requested_payment_term_days, p.requested_route_codes,
      p.requested_service_regions, p.requested_currencies,
      p.contact_name, p.contact_email, p.contact_phone,
      p.billing_country_code, p.billing_province, p.billing_city,
      p.billing_district, p.billing_line1, p.billing_postal_code,
      p.duplicate_resolution_note, p.sales_owner_id, p.submitted_by_id,
      p.submitted_at, p.version, p.created_at, p.updated_at
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final JsonMapper jsonMapper;
  private final ReliableEventPublisher reliableEvents;

  JdbcPartnerRepository(
      NamedParameterJdbcTemplate jdbc,
      JsonMapper jsonMapper,
      ReliableEventPublisher reliableEvents) {
    this.jdbc = jdbc;
    this.jsonMapper = jsonMapper;
    this.reliableEvents = reliableEvents;
  }

  @Override
  public String nextNumber(TenantId tenantId, Instant now) {
    Long value =
        jdbc.getJdbcTemplate()
            .queryForObject("SELECT nextval('partner.partner_number_seq')", Long.class);
    if (value == null) {
      throw new IllegalStateException("Partner number sequence returned no value");
    }
    return "PAR-" + NUMBER_PERIOD.format(now) + "-" + "%06d".formatted(value);
  }

  @Override
  public Partner insert(TenantId tenantId, Partner partner, UUID actorId) {
    jdbc.update(
        """
        INSERT INTO partner.partner
          (id, tenant_id, number, legal_name, normalized_legal_name, display_name,
           registration_identifier, normalized_registration_identifier, partner_type, status,
           default_currency, requested_payment_term_days, requested_route_codes,
           requested_service_regions, requested_currencies, contact_name, contact_email,
           contact_phone, billing_country_code, billing_province, billing_city, billing_district,
           billing_line1, billing_postal_code, duplicate_resolution_note, sales_owner_id,
           submitted_by_id, submitted_at, created_at, created_by, updated_at, updated_by, version)
        VALUES
          (:id, :tenantId, :number, :legalName, :normalizedLegalName, :displayName,
           :registrationIdentifier, :normalizedRegistrationIdentifier, :partnerType, :status,
           :defaultCurrency, :paymentTermDays, :routeCodes, :serviceRegions, :currencies,
           :contactName, :contactEmail, :contactPhone, :countryCode, :province, :city, :district,
           :line1, :postalCode, :duplicateNote, :ownerId, :submittedById, :submittedAt,
           :createdAt, :actorId, :updatedAt, :actorId, :version)
        """,
        parameters(partner, actorId));
    return partner;
  }

  @Override
  public Optional<Partner> find(TenantId tenantId, UUID partnerId) {
    return jdbc
        .query(
            "SELECT "
                + PARTNER_COLUMNS
                + " FROM partner.partner p WHERE p.tenant_id = :tenantId AND p.id = :partnerId",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("partnerId", partnerId),
            (resultSet, rowNumber) -> mapPartner(resultSet))
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Partner> update(
      TenantId tenantId, Partner partner, long expectedVersion, UUID actorId) {
    MapSqlParameterSource parameters =
        parameters(partner, actorId).addValue("expectedVersion", expectedVersion);
    List<Partner> updated =
        jdbc.query(
            """
            UPDATE partner.partner p
               SET legal_name = :legalName,
                   normalized_legal_name = :normalizedLegalName,
                   display_name = :displayName,
                   registration_identifier = :registrationIdentifier,
                   normalized_registration_identifier = :normalizedRegistrationIdentifier,
                   partner_type = :partnerType,
                   status = :status,
                   default_currency = :defaultCurrency,
                   requested_payment_term_days = :paymentTermDays,
                   requested_route_codes = :routeCodes,
                   requested_service_regions = :serviceRegions,
                   requested_currencies = :currencies,
                   contact_name = :contactName,
                   contact_email = :contactEmail,
                   contact_phone = :contactPhone,
                   billing_country_code = :countryCode,
                   billing_province = :province,
                   billing_city = :city,
                   billing_district = :district,
                   billing_line1 = :line1,
                   billing_postal_code = :postalCode,
                   duplicate_resolution_note = :duplicateNote,
                   submitted_by_id = :submittedById,
                   submitted_at = :submittedAt,
                   updated_at = :updatedAt,
                   updated_by = :actorId,
                   version = version + 1
             WHERE tenant_id = :tenantId
               AND id = :id
               AND version = :expectedVersion
            RETURNING
              id, tenant_id, number, legal_name, display_name, registration_identifier,
              partner_type, status, default_currency, requested_payment_term_days,
              requested_route_codes, requested_service_regions, requested_currencies,
              contact_name, contact_email, contact_phone, billing_country_code,
              billing_province, billing_city, billing_district, billing_line1,
              billing_postal_code, duplicate_resolution_note, sales_owner_id,
              submitted_by_id, submitted_at, version, created_at, updated_at
            """,
            parameters,
            (resultSet, rowNumber) -> mapPartner(resultSet));
    return updated.stream().findFirst();
  }

  @Override
  public boolean registrationIdentifierExists(
      TenantId tenantId, String normalizedIdentifier, UUID excludingPartnerId) {
    if (normalizedIdentifier == null) {
      return false;
    }
    Integer count =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM partner.partner
             WHERE tenant_id = :tenantId
               AND normalized_registration_identifier = :identifier
               AND id <> :excludingId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("identifier", normalizedIdentifier)
                .addValue(
                    "excludingId", excludingPartnerId == null ? NO_PARTNER : excludingPartnerId),
            Integer.class);
    return count != null && count > 0;
  }

  @Override
  public boolean legalNameDuplicate(
      TenantId tenantId, String normalizedLegalName, UUID excludingPartnerId) {
    if (normalizedLegalName == null) {
      return false;
    }
    Integer count =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM partner.partner
             WHERE tenant_id = :tenantId
               AND normalized_legal_name = :legalName
               AND id <> :excludingId
               AND status <> 'REJECTED'
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("legalName", normalizedLegalName)
                .addValue(
                    "excludingId", excludingPartnerId == null ? NO_PARTNER : excludingPartnerId),
            Integer.class);
    return count != null && count > 0;
  }

  @Override
  public PartnerPage list(
      TenantId tenantId, PartnerSearch search, CursorPosition cursor, int pageSize) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(PARTNER_COLUMNS)
            .append(" FROM partner.partner p WHERE p.tenant_id = :tenantId");
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("limit", pageSize + 1);
    if (search.keyword() != null && !search.keyword().isBlank()) {
      sql.append(
          " AND (p.legal_name ILIKE :keyword ESCAPE '\\'"
              + " OR p.display_name ILIKE :keyword ESCAPE '\\'"
              + " OR p.number ILIKE :keyword ESCAPE '\\')");
      parameters.addValue("keyword", "%" + escapeLike(search.keyword().strip()) + "%");
    }
    if (!search.statuses().isEmpty()) {
      sql.append(" AND p.status IN (:statuses)");
      parameters.addValue("statuses", search.statuses().stream().map(Enum::name).toList());
    }
    if (search.ownerId() != null) {
      sql.append(" AND p.sales_owner_id = :ownerId");
      parameters.addValue("ownerId", search.ownerId());
    }
    if (search.routeCode() != null && !search.routeCode().isBlank()) {
      sql.append(
          """
           AND :routeCode = ANY(COALESCE(
             (SELECT eligibility.allowed_route_codes
                FROM partner.eligibility_version eligibility
               WHERE eligibility.tenant_id = p.tenant_id
                 AND eligibility.partner_id = p.id
               ORDER BY eligibility.eligibility_version DESC
               LIMIT 1),
             p.requested_route_codes))
          """);
      parameters.addValue("routeCode", search.routeCode());
    }
    if (search.updatedFrom() != null) {
      sql.append(" AND p.updated_at >= :updatedFrom");
      parameters.addValue("updatedFrom", timestamp(search.updatedFrom()));
    }
    if (search.updatedTo() != null) {
      sql.append(" AND p.updated_at < :updatedTo");
      parameters.addValue("updatedTo", timestamp(search.updatedTo()));
    }
    if (cursor != null) {
      sql.append(
          " AND (p.updated_at < :cursorUpdatedAt"
              + " OR (p.updated_at = :cursorUpdatedAt AND p.number > :cursorNumber)"
              + " OR (p.updated_at = :cursorUpdatedAt AND p.number = :cursorNumber"
              + " AND p.id > :cursorId))");
      parameters
          .addValue("cursorUpdatedAt", timestamp(cursor.updatedAt()))
          .addValue("cursorNumber", cursor.number())
          .addValue("cursorId", cursor.id());
    }
    sql.append(" ORDER BY p.updated_at DESC, p.number, p.id LIMIT :limit");
    List<Partner> rows =
        jdbc.query(sql.toString(), parameters, (resultSet, rowNumber) -> mapPartner(resultSet));
    boolean hasNext = rows.size() > pageSize;
    return new PartnerPage(
        hasNext ? List.copyOf(rows.subList(0, pageSize)) : List.copyOf(rows), hasNext);
  }

  @Override
  public int nextEligibilityVersion(TenantId tenantId, UUID partnerId) {
    Integer value =
        jdbc.queryForObject(
            """
            SELECT COALESCE(max(eligibility_version), 0) + 1
              FROM partner.eligibility_version
             WHERE tenant_id = :tenantId AND partner_id = :partnerId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("partnerId", partnerId),
            Integer.class);
    return value == null ? 1 : value;
  }

  @Override
  public void insertEligibility(
      TenantId tenantId,
      UUID partnerId,
      int eligibilityVersion,
      Partner.Eligibility eligibility,
      UUID actorId) {
    jdbc.update(
        """
        INSERT INTO partner.eligibility_version
          (tenant_id, partner_id, eligibility_version, allowed_route_codes,
           allowed_service_regions, allowed_currencies, payment_term_days,
           credit_limit_amount, credit_limit_currency, effective_from, approved_by,
           approved_at, created_at, created_by, updated_at, updated_by, version)
        VALUES
          (:tenantId, :partnerId, :eligibilityVersion, :routeCodes, :serviceRegions,
           :currencies, :paymentTermDays, :creditAmount, :creditCurrency, :effectiveFrom,
           :actorId, :effectiveFrom, :effectiveFrom, :actorId, :effectiveFrom, :actorId, 0)
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("partnerId", partnerId)
            .addValue("eligibilityVersion", eligibilityVersion)
            .addValue("routeCodes", sqlArray(eligibility.routeCodes()))
            .addValue("serviceRegions", sqlArray(eligibility.serviceRegions()))
            .addValue("currencies", sqlArray(eligibility.currencies()))
            .addValue("paymentTermDays", eligibility.paymentTermDays())
            .addValue("creditAmount", eligibility.creditLimitAmount())
            .addValue("creditCurrency", eligibility.creditLimitCurrency())
            .addValue("effectiveFrom", timestamp(eligibility.effectiveFrom()))
            .addValue("actorId", actorId));
  }

  @Override
  public Optional<EligibilityRecord> latestEligibility(TenantId tenantId, UUID partnerId) {
    return jdbc
        .query(
            """
            SELECT eligibility_version, allowed_route_codes, allowed_service_regions,
                   allowed_currencies, payment_term_days, credit_limit_amount,
                   credit_limit_currency, effective_from, approved_by
              FROM partner.eligibility_version
             WHERE tenant_id = :tenantId AND partner_id = :partnerId
             ORDER BY eligibility_version DESC
             LIMIT 1
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("partnerId", partnerId),
            (resultSet, rowNumber) ->
                new EligibilityRecord(
                    resultSet.getInt("eligibility_version"),
                    new Partner.Eligibility(
                        stringSet(resultSet.getArray("allowed_route_codes")),
                        stringSet(resultSet.getArray("allowed_service_regions")),
                        stringSet(resultSet.getArray("allowed_currencies")),
                        resultSet.getInt("payment_term_days"),
                        resultSet.getBigDecimal("credit_limit_amount"),
                        resultSet.getString("credit_limit_currency"),
                        instant(resultSet, "effective_from"),
                        resultSet.getObject("approved_by", UUID.class))))
        .stream()
        .findFirst();
  }

  @Override
  public void insertReviewDecision(
      TenantId tenantId,
      Partner before,
      Partner after,
      Partner.ReviewDecision decision,
      String reason,
      UUID reviewerId) {
    UUID decisionId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO partner.review_decision
          (id, tenant_id, partner_id, decision, reason, submitted_by_id, reviewer_id,
           previous_state, new_state, source_version, decided_at,
           created_at, created_by, updated_at, updated_by, version)
        VALUES
          (:id, :tenantId, :partnerId, :decision, :reason, :submittedById, :reviewerId,
           :previousState, :newState, :sourceVersion, :decidedAt,
           :decidedAt, :reviewerId, :decidedAt, :reviewerId, 0)
        """,
        new MapSqlParameterSource()
            .addValue("id", decisionId)
            .addValue("tenantId", tenantId.value())
            .addValue("partnerId", before.id())
            .addValue("decision", decision.name())
            .addValue("reason", reason.strip())
            .addValue("submittedById", before.submittedById())
            .addValue("reviewerId", reviewerId)
            .addValue("previousState", before.status().name())
            .addValue("newState", after.status().name())
            .addValue("sourceVersion", before.version())
            .addValue("decidedAt", timestamp(after.updatedAt())));
  }

  @Override
  public void insertAudit(
      TenantId tenantId,
      Partner partner,
      UUID actorId,
      String action,
      PartnerStatus previousState,
      PartnerStatus newState,
      String safeReason,
      List<ChangedField> changedFields,
      Instant occurredAt) {
    jdbc.update(
        """
        INSERT INTO partner.audit_entry
          (id, tenant_id, partner_id, actor_id, action, previous_state, new_state,
           safe_reason, changed_fields, occurred_at, created_at, created_by,
           updated_at, updated_by, version)
        VALUES
          (:id, :tenantId, :partnerId, :actorId, :action, :previousState, :newState,
           CAST(:safeReason AS varchar), CAST(:changedFields AS jsonb), :occurredAt,
           :occurredAt, :actorId, :occurredAt, :actorId, 0)
        """,
        new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("tenantId", tenantId.value())
            .addValue("partnerId", partner.id())
            .addValue("actorId", actorId)
            .addValue("action", action)
            .addValue("previousState", previousState == null ? null : previousState.name())
            .addValue("newState", newState == null ? null : newState.name())
            .addValue("safeReason", safeReason)
            .addValue("changedFields", writeJson(changedFields))
            .addValue("occurredAt", timestamp(occurredAt)));
  }

  @Override
  public UUID insertPublication(
      TenantId tenantId,
      Partner partner,
      Partner.EventType eventType,
      UUID actorId,
      int eligibilityVersion,
      String safeReason,
      Instant occurredAt) {
    UUID eventId = UUID.randomUUID();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("partnerId", partner.id());
    payload.put("number", partner.number());
    if (partner.submittedById() != null) payload.put("submittedBy", partner.submittedById());
    if (eligibilityVersion > 0) payload.put("eligibilityVersion", eligibilityVersion);
    if (safeReason != null) payload.put("safeReason", safeReason);
    jdbc.update(
        """
        INSERT INTO partner.local_event_publication
          (event_id, tenant_id, partner_id, event_type, event_version, payload, status,
           occurred_at, published_at, created_at, created_by, updated_at, updated_by, version)
        VALUES
          (:eventId, :tenantId, :partnerId, :eventType, 1, CAST(:payload AS jsonb),
           'COMPLETED', :occurredAt, :occurredAt, :occurredAt, :actorId,
           :occurredAt, :actorId, 0)
        """,
        new MapSqlParameterSource()
            .addValue("eventId", eventId)
            .addValue("tenantId", tenantId.value())
            .addValue("partnerId", partner.id())
            .addValue("eventType", eventType.value())
            .addValue("payload", writeJson(payload))
            .addValue("occurredAt", timestamp(occurredAt))
            .addValue("actorId", actorId));
    reliableEvents.publish(
        new PendingEvent(
            eventId,
            tenantId.value(),
            switch (eventType) {
              case SUBMITTED -> "cellarbridge.partner.submitted-for-review.v1";
              case ACTIVATED -> "cellarbridge.partner.activated.v1";
              case CHANGES_REQUESTED -> "cellarbridge.partner.changes-requested.v1";
              case REJECTED -> "cellarbridge.partner.rejected.v1";
              case SUSPENDED -> "cellarbridge.partner.suspended.v1";
            },
            1,
            occurredAt,
            "partner",
            new PendingEvent.Subject("PARTNER", partner.id(), partner.number()),
            partner.id(),
            actorId,
            payload,
            Map.of()));
    return eventId;
  }

  @Override
  public void openReviewWorkItem(
      TenantId tenantId, Partner partner, UUID eventId, UUID actorId, Instant occurredAt) {
    jdbc.update(
        """
        INSERT INTO partner.review_work_item
          (id, tenant_id, partner_id, partner_number, source_event_id, status,
           candidate_permission, created_at, created_by, updated_at, updated_by, version)
        VALUES
          (:id, :tenantId, :partnerId, :number, :eventId, 'OPEN', 'partner:review',
           :occurredAt, :actorId, :occurredAt, :actorId, 0)
        ON CONFLICT (tenant_id, source_event_id) DO NOTHING
        """,
        new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("tenantId", tenantId.value())
            .addValue("partnerId", partner.id())
            .addValue("number", partner.number())
            .addValue("eventId", eventId)
            .addValue("occurredAt", timestamp(occurredAt))
            .addValue("actorId", actorId));
  }

  @Override
  public void completeReviewWorkItems(
      TenantId tenantId, UUID partnerId, UUID actorId, Instant occurredAt) {
    jdbc.update(
        """
        UPDATE partner.review_work_item
           SET status = 'COMPLETED', completed_at = :occurredAt,
               updated_at = :occurredAt, updated_by = :actorId, version = version + 1
         WHERE tenant_id = :tenantId
           AND partner_id = :partnerId
           AND status = 'OPEN'
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("partnerId", partnerId)
            .addValue("actorId", actorId)
            .addValue("occurredAt", timestamp(occurredAt)));
  }

  @Override
  public List<TimelineEntry> timeline(TenantId tenantId, UUID partnerId) {
    return jdbc.query(
        """
        SELECT id, occurred_at, action, previous_state, new_state, safe_reason, changed_fields
          FROM partner.audit_entry
         WHERE tenant_id = :tenantId AND partner_id = :partnerId
         ORDER BY occurred_at, id
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("partnerId", partnerId),
        (resultSet, rowNumber) ->
            new TimelineEntry(
                resultSet.getObject("id", UUID.class),
                instant(resultSet, "occurred_at"),
                resultSet.getString("action"),
                resultSet.getString("previous_state"),
                resultSet.getString("new_state"),
                resultSet.getString("safe_reason"),
                changedFieldNames(resultSet.getString("changed_fields"))));
  }

  private MapSqlParameterSource parameters(Partner partner, UUID actorId) {
    Partner.Profile profile = partner.profile();
    Partner.Address address = profile.billingAddress();
    Partner.Contact contact = profile.contact();
    return new MapSqlParameterSource()
        .addValue("id", partner.id())
        .addValue("tenantId", partner.tenantId().value())
        .addValue("number", partner.number())
        .addValue("legalName", profile.legalName())
        .addValue("normalizedLegalName", profile.normalizedLegalName())
        .addValue("displayName", profile.displayName())
        .addValue("registrationIdentifier", blankToNull(profile.registrationIdentifier()))
        .addValue("normalizedRegistrationIdentifier", profile.normalizedRegistrationIdentifier())
        .addValue("partnerType", profile.type() == null ? null : profile.type().name())
        .addValue("status", partner.status().name())
        .addValue("defaultCurrency", profile.defaultCurrency())
        .addValue("paymentTermDays", profile.requestedPaymentTermDays())
        .addValue("routeCodes", sqlArray(profile.requestedRouteCodes()))
        .addValue("serviceRegions", sqlArray(profile.requestedServiceRegions()))
        .addValue("currencies", sqlArray(profile.requestedCurrencies()))
        .addValue("contactName", contact == null ? null : blankToNull(contact.name()))
        .addValue("contactEmail", contact == null ? null : blankToNull(contact.email()))
        .addValue("contactPhone", contact == null ? null : blankToNull(contact.phone()))
        .addValue("countryCode", address == null ? null : blankToNull(address.countryCode()))
        .addValue("province", address == null ? null : blankToNull(address.province()))
        .addValue("city", address == null ? null : blankToNull(address.city()))
        .addValue("district", address == null ? null : blankToNull(address.district()))
        .addValue("line1", address == null ? null : blankToNull(address.line1()))
        .addValue("postalCode", address == null ? null : blankToNull(address.postalCode()))
        .addValue("duplicateNote", blankToNull(profile.duplicateResolutionNote()))
        .addValue("ownerId", partner.salesOwnerId())
        .addValue("submittedById", partner.submittedById())
        .addValue("submittedAt", timestamp(partner.submittedAt()))
        .addValue("createdAt", timestamp(partner.createdAt()))
        .addValue("updatedAt", timestamp(partner.updatedAt()))
        .addValue("actorId", actorId)
        .addValue("version", partner.version());
  }

  private static Partner mapPartner(ResultSet resultSet) throws SQLException {
    return new Partner(
        resultSet.getObject("id", UUID.class),
        TenantId.of(resultSet.getObject("tenant_id", UUID.class)),
        resultSet.getString("number"),
        new Partner.Profile(
            resultSet.getString("legal_name"),
            resultSet.getString("display_name"),
            resultSet.getString("registration_identifier"),
            resultSet.getString("partner_type") == null
                ? null
                : Partner.PartnerType.valueOf(resultSet.getString("partner_type")),
            resultSet.getString("default_currency"),
            (Integer) resultSet.getObject("requested_payment_term_days"),
            stringSet(resultSet.getArray("requested_route_codes")),
            stringSet(resultSet.getArray("requested_service_regions")),
            stringSet(resultSet.getArray("requested_currencies")),
            contact(resultSet),
            address(resultSet),
            resultSet.getString("duplicate_resolution_note")),
        PartnerStatus.valueOf(resultSet.getString("status")),
        resultSet.getObject("sales_owner_id", UUID.class),
        resultSet.getObject("submitted_by_id", UUID.class),
        instant(resultSet, "submitted_at"),
        resultSet.getLong("version"),
        instant(resultSet, "created_at"),
        instant(resultSet, "updated_at"));
  }

  private static Set<String> stringSet(Array value) throws SQLException {
    return Arrays.stream((String[]) value.getArray()).collect(Collectors.toUnmodifiableSet());
  }

  private static Partner.Contact contact(ResultSet resultSet) throws SQLException {
    String name = resultSet.getString("contact_name");
    String email = resultSet.getString("contact_email");
    String phone = resultSet.getString("contact_phone");
    return name == null && email == null && phone == null
        ? null
        : new Partner.Contact(name, email, phone, true);
  }

  private static Partner.Address address(ResultSet resultSet) throws SQLException {
    String countryCode = resultSet.getString("billing_country_code");
    String province = resultSet.getString("billing_province");
    String city = resultSet.getString("billing_city");
    String district = resultSet.getString("billing_district");
    String line1 = resultSet.getString("billing_line1");
    String postalCode = resultSet.getString("billing_postal_code");
    return countryCode == null
            && province == null
            && city == null
            && district == null
            && line1 == null
            && postalCode == null
        ? null
        : new Partner.Address(countryCode, province, city, district, line1, postalCode);
  }

  private static SqlArrayValue sqlArray(Set<String> values) {
    return new SqlArrayValue("varchar", values.toArray());
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private String writeJson(Object value) {
    try {
      return jsonMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException("Partner audit serialization failed", exception);
    }
  }

  private List<String> changedFieldNames(String json) {
    try {
      List<Map<String, String>> values =
          jsonMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
      List<String> fields = new ArrayList<>();
      for (Map<String, String> value : values) {
        fields.add(value.get("field"));
      }
      return List.copyOf(fields);
    } catch (Exception exception) {
      throw new IllegalStateException("Partner audit payload is invalid", exception);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static java.time.OffsetDateTime timestamp(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    java.time.OffsetDateTime value = resultSet.getObject(column, java.time.OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
