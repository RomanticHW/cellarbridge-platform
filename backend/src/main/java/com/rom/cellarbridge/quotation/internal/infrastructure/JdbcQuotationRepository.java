package com.rom.cellarbridge.quotation.internal.infrastructure;

import com.rom.cellarbridge.identityaccess.GlobalRegistryAccess;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.quotation.QuotationStatus;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.AcceptedOrderSource;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.CustomerDecision;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.CustomerDecisionType;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.CustomerOperation;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.ExpirationWorkItem;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.IdempotencyRecord;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.IdempotencyWrite;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.OrderLink;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.PortalContext;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.Address;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.ApprovalDecision;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.DraftTerms;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.PartnerSnapshot;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.Revision;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.TimelineEntry;
import com.rom.cellarbridge.quotation.internal.domain.QuotationApprovalPolicy.Requirement;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PriceReference;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PricedLine;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PricingResult;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.QuantityUnit;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.SkuSnapshot;
import com.rom.cellarbridge.quotation.internal.domain.QuotationProblem;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcQuotationRepository implements QuotationRepository {

  private static final DateTimeFormatter NUMBER_PERIOD =
      DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);
  private static final String CURRENT_SELECT =
      """
      SELECT q.id AS quotation_id, q.tenant_id, q.number AS quotation_number,
             q.partner_id, q.status, q.current_revision_no, q.owner_id,
             q.submitted_by_id, q.version AS quotation_version,
             q.created_at AS quotation_created_at, q.updated_at AS quotation_updated_at,
             r.id AS revision_id, r.partner_number, r.partner_display_name,
             r.partner_payment_term_days, r.partner_source_version, r.partner_captured_at,
             r.currency, r.requested_delivery_date, r.expires_at, r.payment_term_days,
             r.delivery_country_code, r.delivery_province, r.delivery_city,
             r.delivery_district, r.delivery_line1, r.delivery_postal_code,
             r.route_evaluation_id, r.route_policy_version, r.recommended_route_code,
             r.selected_route_code, r.route_override_reason, r.price_policy_version,
             r.approval_policy_version, r.subtotal, r.total, r.total_cost,
             r.estimated_margin_rate, r.route_charges, r.frozen_at,
             r.created_at AS revision_created_at
        FROM quotation.quotation q
        JOIN quotation.quotation_revision r
          ON r.tenant_id = q.tenant_id
         AND r.id = q.current_revision_id
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final JsonMapper jsonMapper;

  public JdbcQuotationRepository(NamedParameterJdbcTemplate jdbc, JsonMapper jsonMapper) {
    this.jdbc = jdbc;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public String nextNumber(TenantId tenantId, Instant now) {
    Long value =
        jdbc.getJdbcTemplate()
            .queryForObject("SELECT nextval('quotation.quotation_number_seq')", Long.class);
    if (value == null) {
      throw new IllegalStateException("Quotation number sequence returned no value");
    }
    return "QUO-" + NUMBER_PERIOD.format(now) + "-" + "%06d".formatted(value);
  }

  @Override
  public Map<UUID, PriceReference> currentPrices(
      TenantId tenantId, Set<UUID> skuIds, String currency, Instant now) {
    if (skuIds.isEmpty()) {
      return Map.of();
    }
    List<PriceReference> prices =
        jdbc.query(
            """
            SELECT DISTINCT ON (sku_id)
                   sku_id, currency, list_case_price, cost_case_price, price_version
              FROM quotation.price_reference
             WHERE tenant_id = :tenantId
               AND sku_id IN (:skuIds)
               AND currency = :currency
               AND effective_from <= :now
               AND (effective_to IS NULL OR effective_to > :now)
             ORDER BY sku_id, effective_from DESC, price_version DESC
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("skuIds", skuIds)
                .addValue("currency", currency)
                .addValue("now", timestamp(now)),
            (resultSet, rowNumber) ->
                new PriceReference(
                    resultSet.getObject("sku_id", UUID.class),
                    resultSet.getString("currency"),
                    resultSet.getBigDecimal("list_case_price"),
                    resultSet.getBigDecimal("cost_case_price"),
                    resultSet.getString("price_version")));
    Map<UUID, PriceReference> result = new LinkedHashMap<>();
    prices.forEach(price -> result.put(price.skuId(), price));
    return Map.copyOf(result);
  }

  @Override
  public void insert(TenantId tenantId, QuotationAggregate quotation, UUID actorId) {
    jdbc.update(
        """
        INSERT INTO quotation.quotation
          (id, tenant_id, number, partner_id, status, current_revision_no,
           current_revision_id, owner_id, submitted_by_id, created_at, created_by,
           updated_at, updated_by, version)
        VALUES
          (:id, :tenantId, :number, :partnerId, :status, :revisionNo,
           :revisionId, :ownerId, :submittedById, :createdAt, :actorId,
           :updatedAt, :actorId, :version)
        """,
        quotationParameters(quotation, actorId));
    insertRevision(quotation, actorId);
    insertLines(quotation, actorId);
    insertAudit(null, quotation, actorId, "QUOTATION_DRAFT_CREATED", null);
    insertPublication(quotation, actorId, "cellarbridge.quotation.draft-created.v1", null);
  }

  @Override
  public Optional<QuotationAggregate> find(TenantId tenantId, UUID quotationId) {
    List<QuotationAggregate> rows =
        jdbc.query(
            CURRENT_SELECT + " WHERE q.tenant_id = :tenantId AND q.id = :quotationId",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("quotationId", quotationId),
            (resultSet, rowNumber) -> mapAggregate(resultSet));
    return rows.stream().findFirst();
  }

  @Override
  public List<QuotationAggregate> list(
      TenantId tenantId, Set<QuotationStatus> statuses, UUID ownerId, UUID partnerId, int limit) {
    StringBuilder sql = new StringBuilder(CURRENT_SELECT).append(" WHERE q.tenant_id = :tenantId");
    MapSqlParameterSource parameters =
        new MapSqlParameterSource().addValue("tenantId", tenantId.value()).addValue("limit", limit);
    if (statuses != null && !statuses.isEmpty()) {
      sql.append(" AND q.status IN (:statuses)");
      parameters.addValue("statuses", statuses.stream().map(Enum::name).toList());
    }
    if (ownerId != null) {
      sql.append(" AND q.owner_id = :ownerId");
      parameters.addValue("ownerId", ownerId);
    }
    if (partnerId != null) {
      sql.append(" AND q.partner_id = :partnerId");
      parameters.addValue("partnerId", partnerId);
    }
    sql.append(" ORDER BY q.created_at DESC, q.number, q.id LIMIT :limit");
    return jdbc.query(
        sql.toString(), parameters, (resultSet, rowNumber) -> mapAggregate(resultSet));
  }

  @Override
  public void saveDraft(
      TenantId tenantId,
      QuotationAggregate before,
      QuotationAggregate after,
      long expectedVersion,
      UUID actorId) {
    updateQuotation(after, expectedVersion, actorId);
    if (before.revision().id().equals(after.revision().id())) {
      updateRevision(after, actorId);
      deleteLines(after.tenantId(), after.revision().id());
    } else {
      insertRevision(after, actorId);
    }
    insertLines(after, actorId);
    insertAudit(before, after, actorId, "QUOTATION_DRAFT_UPDATED", null);
  }

  @Override
  public void saveRoute(
      TenantId tenantId,
      QuotationAggregate before,
      QuotationAggregate after,
      long expectedVersion,
      UUID actorId) {
    updateQuotation(after, expectedVersion, actorId);
    updateRevision(after, actorId);
    deleteLines(after.tenantId(), after.revision().id());
    insertLines(after, actorId);
    insertAudit(
        before,
        after,
        actorId,
        after.revision().routeOverrideReason() == null
            ? "TRADE_ROUTE_EVALUATED"
            : "TRADE_ROUTE_OVERRIDDEN",
        after.revision().routeOverrideReason());
    insertPublication(after, actorId, "cellarbridge.trade-planning.route-evaluated.v1", null);
  }

  @Override
  public void saveSubmission(
      TenantId tenantId,
      QuotationAggregate before,
      QuotationAggregate after,
      List<Requirement> requirements,
      long expectedVersion,
      UUID actorId) {
    updateQuotation(after, expectedVersion, actorId);
    updateRevision(after, actorId);
    insertRequirements(after, requirements, actorId);
    if (after.status() == QuotationStatus.PENDING_APPROVAL) {
      openWorkItem(after, actorId);
    }
    insertAudit(before, after, actorId, "QUOTATION_SUBMITTED", null);
    insertPublication(
        after,
        actorId,
        after.status() == QuotationStatus.PENDING_APPROVAL
            ? "cellarbridge.quotation.approval-requested.v1"
            : "cellarbridge.quotation.approved.v1",
        null);
  }

  @Override
  public boolean saveDecision(
      TenantId tenantId,
      QuotationAggregate before,
      QuotationAggregate after,
      ApprovalDecision decision,
      long expectedVersion,
      UUID actorId) {
    int inserted =
        jdbc.update(
            """
            INSERT INTO quotation.approval_decision
              (id, tenant_id, quotation_id, revision_id, decision, reviewer_id, reason,
               occurred_at, created_at, created_by, updated_at, updated_by, version)
            VALUES
              (:id, :tenantId, :quotationId, :revisionId, :decision, :reviewerId, :reason,
               :occurredAt, :occurredAt, :actorId, :occurredAt, :actorId, 0)
            ON CONFLICT (tenant_id, revision_id, reviewer_id) DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("id", decision.id())
                .addValue("tenantId", after.tenantId().value())
                .addValue("quotationId", after.id())
                .addValue("revisionId", after.revision().id())
                .addValue("decision", decision.decision().name())
                .addValue("reviewerId", decision.reviewerId())
                .addValue("reason", decision.reason())
                .addValue("occurredAt", timestamp(decision.occurredAt()))
                .addValue("actorId", actorId));
    if (inserted == 0) {
      return false;
    }
    updateQuotation(after, expectedVersion, actorId);
    completeWorkItems(after, actorId);
    insertAudit(
        before, after, actorId, "QUOTATION_" + decision.decision().name(), decision.reason());
    insertPublication(
        after,
        actorId,
        "cellarbridge.quotation."
            + decision.decision().name().toLowerCase().replace('_', '-')
            + ".v1",
        decision.reason());
    return true;
  }

  @Override
  public void saveIssue(
      TenantId tenantId,
      QuotationAggregate before,
      QuotationAggregate after,
      long expectedVersion,
      UUID actorId,
      UUID accessId,
      String tokenHash,
      String supplierPublicId,
      String supplierDisplayName,
      String termsVersion,
      Instant portalExpiresAt) {
    updateQuotation(after, expectedVersion, actorId);
    jdbc.update(
        """
        INSERT INTO quotation.portal_access
          (id, tenant_id, quotation_id, revision_id, partner_id, token_hash, purpose,
           allowed_actions, terms_version, supplier_public_id, supplier_display_name,
           quotation_expires_at, expires_at,
           created_at, created_by, updated_at, updated_by, version)
        VALUES
          (:id, :tenantId, :quotationId, :revisionId, :partnerId, :tokenHash,
           'CUSTOMER_QUOTATION_DECISION', ARRAY['VIEW','ACCEPT','REJECT'], :termsVersion,
           :supplierPublicId, :supplierDisplayName, :quotationExpiresAt, :portalExpiresAt,
           :occurredAt, :actorId, :occurredAt, :actorId, 0)
        """,
        new MapSqlParameterSource()
            .addValue("id", accessId)
            .addValue("tenantId", after.tenantId().value())
            .addValue("quotationId", after.id())
            .addValue("revisionId", after.revision().id())
            .addValue("partnerId", after.partnerId())
            .addValue("tokenHash", tokenHash)
            .addValue("termsVersion", termsVersion)
            .addValue("supplierPublicId", supplierPublicId)
            .addValue("supplierDisplayName", supplierDisplayName)
            .addValue("quotationExpiresAt", timestamp(after.revision().terms().expiresAt()))
            .addValue("portalExpiresAt", timestamp(portalExpiresAt))
            .addValue("occurredAt", timestamp(after.updatedAt()))
            .addValue("actorId", actorId));
    jdbc.update(
        """
        INSERT INTO quotation.expiration_work_item
          (id, tenant_id, quotation_id, revision_id, due_at, status, attempts,
           created_at, created_by, updated_at, updated_by, version)
        VALUES
          (:id, :tenantId, :quotationId, :revisionId, :dueAt, 'PENDING', 0,
           :occurredAt, :actorId, :occurredAt, :actorId, 0)
        ON CONFLICT (tenant_id, quotation_id, revision_id) DO NOTHING
        """,
        actionParameters(after, actorId)
            .addValue("id", UUID.randomUUID())
            .addValue("dueAt", timestamp(after.revision().terms().expiresAt())));
    insertAudit(before, after, actorId, "QUOTATION_ISSUED", null);
    insertPublication(after, actorId, "cellarbridge.quotation.issued.v1", null);
  }

  @Override
  @GlobalRegistryAccess
  public Optional<PortalContext> findPortalContext(
      String tokenHash, Instant now, boolean forUpdate) {
    String portalLookup =
        """
        SELECT portal.id, portal.tenant_id, portal.quotation_id, portal.revision_id,
               portal.partner_id, portal.supplier_public_id, portal.supplier_display_name,
               portal.terms_version, portal.allowed_actions, portal.expires_at
          FROM quotation.portal_access portal
          JOIN quotation.quotation q
            ON q.tenant_id = portal.tenant_id
           AND q.id = portal.quotation_id
           AND q.partner_id = portal.partner_id
           AND q.current_revision_id = portal.revision_id
          JOIN quotation.quotation_revision bound_revision
            ON bound_revision.tenant_id = portal.tenant_id
           AND bound_revision.id = portal.revision_id
           AND bound_revision.quotation_id = portal.quotation_id
           AND bound_revision.expires_at = portal.quotation_expires_at
         WHERE portal.token_hash = :tokenHash
           AND portal.purpose = 'CUSTOMER_QUOTATION_DECISION'
           AND portal.revoked_at IS NULL
           AND portal.expires_at > :now
        """;
    if (forUpdate) {
      portalLookup += " FOR UPDATE OF portal";
    }
    List<PortalAccessRow> rows =
        jdbc.query(
            portalLookup,
            new MapSqlParameterSource()
                .addValue("tokenHash", tokenHash)
                .addValue("now", timestamp(now)),
            (resultSet, rowNumber) ->
                new PortalAccessRow(
                    resultSet.getObject("id", UUID.class),
                    new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                    resultSet.getObject("quotation_id", UUID.class),
                    resultSet.getObject("revision_id", UUID.class),
                    resultSet.getObject("partner_id", UUID.class),
                    resultSet.getString("supplier_public_id"),
                    resultSet.getString("supplier_display_name"),
                    resultSet.getString("terms_version"),
                    stringSet(resultSet, "allowed_actions"),
                    instant(resultSet, "expires_at")));
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    PortalAccessRow access = rows.getFirst();
    Optional<QuotationAggregate> quotation =
        forUpdate
            ? findForUpdate(access.tenantId(), access.quotationId())
            : find(access.tenantId(), access.quotationId());
    if (quotation.isEmpty()
        || !quotation.get().revision().id().equals(access.revisionId())
        || !quotation.get().partnerId().equals(access.partnerId())) {
      return Optional.empty();
    }
    QuotationAggregate aggregate = quotation.orElseThrow();
    return Optional.of(
        new PortalContext(
            access.id(),
            access.tenantId(),
            access.partnerId(),
            access.revisionId(),
            access.supplierPublicId(),
            access.supplierDisplayName(),
            aggregate.revision().partnerSnapshot().number(),
            access.termsVersion(),
            access.allowedActions(),
            access.expiresAt(),
            aggregate,
            findCustomerDecision(access.tenantId(), access.quotationId()).orElse(null)));
  }

  @Override
  public Optional<QuotationAggregate> findForUpdate(TenantId tenantId, UUID quotationId) {
    List<QuotationAggregate> rows =
        jdbc.query(
            CURRENT_SELECT
                + " WHERE q.tenant_id = :tenantId AND q.id = :quotationId FOR UPDATE OF q",
            ids(tenantId, "quotationId", quotationId),
            (resultSet, rowNumber) -> mapAggregate(resultSet));
    return rows.stream().findFirst();
  }

  @Override
  public Optional<IdempotencyRecord> findIdempotency(
      TenantId tenantId, UUID partnerId, CustomerOperation operation, String keyHash) {
    List<IdempotencyRecord> rows =
        jdbc.query(
            """
            SELECT request_hash, decision_id
              FROM quotation.http_idempotency
             WHERE tenant_id = :tenantId
               AND partner_id = :partnerId
               AND operation = :operation
               AND key_hash = :keyHash
               AND expires_at > CURRENT_TIMESTAMP
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("partnerId", partnerId)
                .addValue("operation", operation.name())
                .addValue("keyHash", keyHash),
            (resultSet, rowNumber) ->
                new IdempotencyRecord(
                    resultSet.getString("request_hash"),
                    resultSet.getObject("decision_id", UUID.class)));
    return rows.stream().findFirst();
  }

  @Override
  public void lockIdempotencyKey(
      TenantId tenantId, UUID partnerId, CustomerOperation operation, String keyHash) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(:scope, 0))",
        new MapSqlParameterSource()
            .addValue(
                "scope",
                tenantId.value() + ":" + partnerId + ":" + operation.name() + ":" + keyHash),
        (resultSet, rowNumber) -> resultSet.getObject(1));
  }

  @Override
  public void saveCustomerDecision(
      TenantId tenantId,
      QuotationAggregate before,
      QuotationAggregate after,
      CustomerDecision decision,
      IdempotencyWrite idempotency,
      UUID actorId) {
    requireTenant(tenantId, before.tenantId(), after.tenantId(), decision.tenantId());
    updateQuotation(after, before.version(), actorId);
    jdbc.update(
        """
        INSERT INTO quotation.customer_decision
          (id, tenant_id, quotation_id, revision_id, portal_access_id, partner_id, decision,
           accepted_terms_version, buyer_reference, reason_category, commercial_snapshot,
           snapshot_hash, idempotency_digest, accepted_event_id, decided_at,
           created_at, created_by, updated_at, updated_by, version)
        VALUES
          (:id, :tenantId, :quotationId, :revisionId, :portalAccessId, :partnerId, :decision,
           :acceptedTermsVersion, :buyerReference, :reasonCategory,
           CAST(:commercialSnapshot AS jsonb), :snapshotHash, :idempotencyDigest,
           :acceptedEventId, :decidedAt,
           :decidedAt, :actorId, :decidedAt, :actorId, 0)
        """,
        new MapSqlParameterSource()
            .addValue("id", decision.id())
            .addValue("tenantId", decision.tenantId().value())
            .addValue("quotationId", decision.quotationId())
            .addValue("revisionId", decision.revisionId())
            .addValue("portalAccessId", decision.portalAccessId())
            .addValue("partnerId", decision.partnerId())
            .addValue("decision", decision.decision().name())
            .addValue("acceptedTermsVersion", decision.acceptedTermsVersion())
            .addValue("buyerReference", decision.buyerReference())
            .addValue("reasonCategory", decision.reasonCategory())
            .addValue("commercialSnapshot", decision.commercialSnapshot())
            .addValue("snapshotHash", decision.snapshotHash())
            .addValue("idempotencyDigest", decision.idempotencyDigest())
            .addValue("acceptedEventId", decision.acceptedEventId())
            .addValue("decidedAt", timestamp(decision.decidedAt()))
            .addValue("actorId", actorId));
    insertIdempotency(decision.tenantId(), decision.partnerId(), decision, idempotency);
    completeExpirationForQuotation(after, actorId);
    insertAudit(
        before,
        after,
        actorId,
        "CUSTOMER_TOKEN",
        decision.decision() == CustomerDecisionType.ACCEPTED
            ? "QUOTATION_ACCEPTED_BY_CUSTOMER"
            : "QUOTATION_REJECTED_BY_CUSTOMER",
        decision.reasonCategory());
  }

  @Override
  public void saveIdempotencyResult(
      TenantId tenantId,
      PortalContext context,
      CustomerDecision decision,
      IdempotencyWrite idempotency) {
    requireTenant(tenantId, context.tenantId(), decision.tenantId());
    insertIdempotency(context.tenantId(), context.partnerId(), decision, idempotency);
  }

  @Override
  @GlobalRegistryAccess
  public List<ExpirationWorkItem> claimExpired(
      Instant now, UUID claimOwner, Instant claimUntil, int batchSize) {
    return jdbc.query(
        """
        WITH candidates AS (
          SELECT id
            FROM quotation.expiration_work_item
           WHERE due_at <= :now
             AND (status = 'PENDING'
                  OR (status = 'CLAIMED' AND claim_until <= :now))
           ORDER BY due_at, id
           FOR UPDATE SKIP LOCKED
           LIMIT :batchSize
        )
        UPDATE quotation.expiration_work_item work
           SET status = 'CLAIMED', claim_owner = :claimOwner, claim_until = :claimUntil,
               attempts = attempts + 1, updated_at = :now, updated_by = :actorId,
               version = version + 1
          FROM candidates
         WHERE work.id = candidates.id
        RETURNING work.id, work.tenant_id, work.quotation_id, work.revision_id, work.due_at
        """,
        new MapSqlParameterSource()
            .addValue("now", timestamp(now))
            .addValue("claimOwner", claimOwner.toString())
            .addValue("actorId", claimOwner)
            .addValue("claimUntil", timestamp(claimUntil))
            .addValue("batchSize", batchSize),
        (resultSet, rowNumber) ->
            new ExpirationWorkItem(
                resultSet.getObject("id", UUID.class),
                new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                resultSet.getObject("quotation_id", UUID.class),
                resultSet.getObject("revision_id", UUID.class),
                instant(resultSet, "due_at")));
  }

  @Override
  public void saveExpiration(
      TenantId tenantId, QuotationAggregate before, QuotationAggregate after, UUID systemActorId) {
    requireTenant(tenantId, before.tenantId(), after.tenantId());
    updateQuotation(after, before.version(), systemActorId);
    completeExpirationForQuotation(after, systemActorId);
    insertAudit(before, after, systemActorId, "SYSTEM", "QUOTATION_EXPIRED", null);
  }

  @Override
  public void completeExpiration(
      TenantId tenantId, ExpirationWorkItem workItem, Instant now, UUID systemActorId) {
    requireTenant(tenantId, workItem.tenantId());
    jdbc.update(
        """
        UPDATE quotation.expiration_work_item
           SET status = 'COMPLETED', claim_owner = NULL, claim_until = NULL,
               completion_outcome = 'SKIPPED_FINAL',
               completed_at = COALESCE(completed_at, :now), updated_at = :now,
               updated_by = :actorId, version = version + 1
         WHERE tenant_id = :tenantId AND id = :id AND status <> 'COMPLETED'
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("id", workItem.id())
            .addValue("now", timestamp(now))
            .addValue("actorId", systemActorId));
  }

  @Override
  public Optional<OrderLink> findOrderLink(TenantId tenantId, UUID quotationId) {
    List<OrderLink> rows =
        jdbc.query(
            """
            SELECT quotation_id, revision_id, acceptance_id, order_id, order_number,
                   snapshot_hash, source_event_id, converted_at
              FROM quotation.order_link
             WHERE tenant_id = :tenantId AND quotation_id = :quotationId
            """,
            ids(tenantId, "quotationId", quotationId),
            (resultSet, rowNumber) ->
                new OrderLink(
                    resultSet.getObject("quotation_id", UUID.class),
                    resultSet.getObject("revision_id", UUID.class),
                    resultSet.getObject("acceptance_id", UUID.class),
                    resultSet.getObject("order_id", UUID.class),
                    resultSet.getString("order_number"),
                    resultSet.getString("snapshot_hash"),
                    resultSet.getObject("source_event_id", UUID.class),
                    instant(resultSet, "converted_at")));
    return rows.stream().findFirst();
  }

  @Override
  public Optional<AcceptedOrderSource> findAcceptedOrderSource(
      TenantId tenantId, UUID quotationId) {
    List<AcceptedOrderSource> rows =
        jdbc.query(
            """
            SELECT id, revision_id, snapshot_hash
              FROM quotation.customer_decision
             WHERE tenant_id = :tenantId
               AND quotation_id = :quotationId
               AND decision = 'ACCEPTED'
            """,
            ids(tenantId, "quotationId", quotationId),
            (resultSet, rowNumber) ->
                new AcceptedOrderSource(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("revision_id", UUID.class),
                    "sha256:" + resultSet.getString("snapshot_hash")));
    return rows.stream().findFirst();
  }

  @Override
  public void saveOrderConversion(
      TenantId tenantId,
      QuotationAggregate before,
      QuotationAggregate after,
      OrderLink orderLink,
      UUID systemActorId) {
    requireTenant(tenantId, before.tenantId(), after.tenantId());
    if (!before.id().equals(orderLink.quotationId())
        || !before.revision().id().equals(orderLink.revisionId())) {
      throw new IllegalArgumentException("Order link does not match the quotation revision");
    }
    updateQuotation(after, before.version(), systemActorId);
    int inserted =
        jdbc.update(
            """
            INSERT INTO quotation.order_link
              (id, tenant_id, quotation_id, revision_id, acceptance_id, order_id, order_number,
               snapshot_hash, source_event_id, converted_at, created_at, created_by,
               updated_at, updated_by, version)
            VALUES
              (:sourceEventId, :tenantId, :quotationId, :revisionId, :acceptanceId,
               :orderId, :orderNumber, :snapshotHash, :sourceEventId, :convertedAt,
               :convertedAt, :actorId, :convertedAt, :actorId, 0)
            ON CONFLICT (tenant_id, quotation_id) DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("quotationId", orderLink.quotationId())
                .addValue("revisionId", orderLink.revisionId())
                .addValue("acceptanceId", orderLink.acceptanceId())
                .addValue("orderId", orderLink.orderId())
                .addValue("orderNumber", orderLink.orderNumber())
                .addValue("snapshotHash", orderLink.snapshotHash())
                .addValue("sourceEventId", orderLink.sourceEventId())
                .addValue("convertedAt", timestamp(orderLink.convertedAt()))
                .addValue("actorId", systemActorId));
    if (inserted != 1) {
      throw new IllegalStateException("Quotation already has a different order link");
    }
    insertAudit(
        before,
        after,
        systemActorId,
        "SYSTEM",
        "QUOTATION_CONVERTED_TO_ORDER",
        orderLink.orderNumber());
  }

  private Optional<CustomerDecision> findCustomerDecision(TenantId tenantId, UUID quotationId) {
    List<CustomerDecision> rows =
        jdbc.query(
            """
            SELECT id, tenant_id, quotation_id, revision_id, portal_access_id, partner_id,
                   decision, accepted_terms_version, buyer_reference, reason_category,
                   commercial_snapshot::text, snapshot_hash, idempotency_digest,
                   accepted_event_id, decided_at
              FROM quotation.customer_decision
             WHERE tenant_id = :tenantId AND quotation_id = :quotationId
            """,
            ids(tenantId, "quotationId", quotationId),
            (resultSet, rowNumber) ->
                new CustomerDecision(
                    resultSet.getObject("id", UUID.class),
                    new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                    resultSet.getObject("quotation_id", UUID.class),
                    resultSet.getObject("revision_id", UUID.class),
                    resultSet.getObject("portal_access_id", UUID.class),
                    resultSet.getObject("partner_id", UUID.class),
                    CustomerDecisionType.valueOf(resultSet.getString("decision")),
                    resultSet.getString("accepted_terms_version"),
                    resultSet.getString("buyer_reference"),
                    resultSet.getString("reason_category"),
                    resultSet.getString("commercial_snapshot"),
                    resultSet.getString("snapshot_hash"),
                    resultSet.getString("idempotency_digest"),
                    resultSet.getObject("accepted_event_id", UUID.class),
                    instant(resultSet, "decided_at")));
    return rows.stream().findFirst();
  }

  private void insertIdempotency(
      TenantId tenantId, UUID partnerId, CustomerDecision decision, IdempotencyWrite idempotency) {
    jdbc.update(
        """
        INSERT INTO quotation.http_idempotency
          (id, tenant_id, partner_id, operation, key_hash, request_hash, status, decision_id,
           response_status, expires_at, created_at, updated_at, version)
        VALUES
          (:id, :tenantId, :partnerId, :operation, :keyHash, :requestHash, 'COMPLETED',
           :decisionId, :responseStatus, :expiresAt, :createdAt, :createdAt, 0)
        ON CONFLICT (tenant_id, partner_id, operation, key_hash) DO NOTHING
        """,
        new MapSqlParameterSource()
            .addValue("id", idempotency.id())
            .addValue("tenantId", tenantId.value())
            .addValue("partnerId", partnerId)
            .addValue("operation", idempotency.operation().name())
            .addValue("keyHash", idempotency.keyHash())
            .addValue("requestHash", idempotency.requestHash())
            .addValue("decisionId", decision.id())
            .addValue("responseStatus", idempotency.responseStatus())
            .addValue("expiresAt", timestamp(idempotency.expiresAt()))
            .addValue("createdAt", timestamp(idempotency.createdAt())));
  }

  private void completeExpirationForQuotation(QuotationAggregate quotation, UUID actorId) {
    String outcome = quotation.status() == QuotationStatus.EXPIRED ? "EXPIRED" : "SKIPPED_FINAL";
    jdbc.update(
        """
        UPDATE quotation.expiration_work_item
           SET status = 'COMPLETED', claim_owner = NULL, claim_until = NULL,
               completion_outcome = CASE WHEN :outcome = 'EXPIRED' THEN 'EXPIRED' ELSE 'SKIPPED_FINAL' END,
               completed_at = COALESCE(completed_at, :occurredAt), updated_at = :occurredAt,
               updated_by = :actorId, version = version + 1
         WHERE tenant_id = :tenantId AND quotation_id = :quotationId
           AND status <> 'COMPLETED'
        """,
        actionParameters(quotation, actorId).addValue("outcome", outcome));
  }

  private QuotationAggregate mapAggregate(ResultSet resultSet) throws SQLException {
    TenantId tenantId = new TenantId(resultSet.getObject("tenant_id", UUID.class));
    UUID quotationId = resultSet.getObject("quotation_id", UUID.class);
    UUID revisionId = resultSet.getObject("revision_id", UUID.class);
    List<PricedLine> lines = lines(tenantId, revisionId);
    BigDecimalValues values =
        new BigDecimalValues(
            resultSet.getBigDecimal("subtotal"),
            resultSet.getBigDecimal("total"),
            resultSet.getBigDecimal("total_cost"),
            resultSet.getBigDecimal("estimated_margin_rate"),
            resultSet.getBigDecimal("route_charges"));
    PricingResult pricing =
        new PricingResult(
            lines,
            values.subtotal(),
            values.total(),
            values.totalCost(),
            values.marginRate(),
            values.routeCharges());
    PartnerSnapshot partner =
        new PartnerSnapshot(
            resultSet.getObject("partner_id", UUID.class),
            resultSet.getString("partner_number"),
            resultSet.getString("partner_display_name"),
            resultSet.getInt("partner_payment_term_days"),
            resultSet.getInt("partner_source_version"),
            instant(resultSet, "partner_captured_at"));
    DraftTerms terms =
        new DraftTerms(
            resultSet.getString("currency"),
            resultSet.getObject("requested_delivery_date", java.time.LocalDate.class),
            instant(resultSet, "expires_at"),
            resultSet.getInt("payment_term_days"),
            new Address(
                resultSet.getString("delivery_country_code"),
                resultSet.getString("delivery_province"),
                resultSet.getString("delivery_city"),
                resultSet.getString("delivery_district"),
                resultSet.getString("delivery_line1"),
                resultSet.getString("delivery_postal_code")));
    Revision revision =
        new Revision(
            revisionId,
            resultSet.getInt("current_revision_no"),
            partner,
            terms,
            pricing,
            resultSet.getObject("route_evaluation_id", UUID.class),
            resultSet.getString("route_policy_version"),
            route(resultSet.getString("recommended_route_code")),
            route(resultSet.getString("selected_route_code")),
            resultSet.getString("route_override_reason"),
            resultSet.getString("price_policy_version"),
            resultSet.getString("approval_policy_version"),
            requirements(tenantId, revisionId),
            nullableInstant(resultSet, "frozen_at"),
            instant(resultSet, "revision_created_at"));
    return new QuotationAggregate(
        quotationId,
        tenantId,
        resultSet.getString("quotation_number"),
        resultSet.getObject("partner_id", UUID.class),
        QuotationStatus.valueOf(resultSet.getString("status")),
        resultSet.getInt("current_revision_no"),
        resultSet.getObject("owner_id", UUID.class),
        resultSet.getObject("submitted_by_id", UUID.class),
        resultSet.getLong("quotation_version"),
        instant(resultSet, "quotation_created_at"),
        instant(resultSet, "quotation_updated_at"),
        revision,
        approvals(tenantId, quotationId),
        timeline(tenantId, quotationId));
  }

  private List<PricedLine> lines(TenantId tenantId, UUID revisionId) {
    return jdbc.query(
        """
        SELECT *
          FROM quotation.quotation_line
         WHERE tenant_id = :tenantId AND revision_id = :revisionId
         ORDER BY created_at, id
        """,
        ids(tenantId, "revisionId", revisionId),
        (resultSet, rowNumber) ->
            new PricedLine(
                resultSet.getObject("id", UUID.class),
                new SkuSnapshot(
                    resultSet.getObject("sku_id", UUID.class),
                    resultSet.getString("sku_code"),
                    resultSet.getString("display_name"),
                    resultSet.getString("producer_name"),
                    resultSet.getString("region_name"),
                    resultSet.getString("country_code"),
                    resultSet.getString("category"),
                    resultSet.getString("vintage"),
                    resultSet.getInt("volume_ml"),
                    resultSet.getInt("units_per_case"),
                    resultSet.getString("package_type"),
                    resultSet.getLong("sku_source_version"),
                    instant(resultSet, "sku_captured_at")),
                resultSet.getBigDecimal("quantity"),
                QuantityUnit.valueOf(resultSet.getString("quantity_unit")),
                resultSet.getObject("preferred_supply_pool_id", UUID.class),
                resultSet.getString("supply_type"),
                resultSet.getBigDecimal("list_unit_price"),
                resultSet.getBigDecimal("discount_rate"),
                resultSet.getBigDecimal("net_unit_price"),
                resultSet.getBigDecimal("allocated_charges"),
                resultSet.getBigDecimal("line_total"),
                resultSet.getBigDecimal("cost_unit_price"),
                resultSet.getBigDecimal("line_cost"),
                resultSet.getBigDecimal("estimated_margin_rate"),
                resultSet.getBoolean("manual_price"),
                resultSet.getString("currency"),
                resultSet.getString("price_source_version")));
  }

  private List<Requirement> requirements(TenantId tenantId, UUID revisionId) {
    return jdbc.query(
        """
        SELECT rule_id, code, actual_value, threshold, message
          FROM quotation.approval_requirement
         WHERE tenant_id = :tenantId AND revision_id = :revisionId
         ORDER BY rule_id
        """,
        ids(tenantId, "revisionId", revisionId),
        (resultSet, rowNumber) ->
            new Requirement(
                resultSet.getString("rule_id"),
                resultSet.getString("code"),
                resultSet.getString("actual_value"),
                resultSet.getString("threshold"),
                resultSet.getString("message")));
  }

  private List<ApprovalDecision> approvals(TenantId tenantId, UUID quotationId) {
    return jdbc.query(
        """
        SELECT id, revision_id, decision, reviewer_id, reason, occurred_at
          FROM quotation.approval_decision
         WHERE tenant_id = :tenantId AND quotation_id = :quotationId
         ORDER BY occurred_at, id
        """,
        ids(tenantId, "quotationId", quotationId),
        (resultSet, rowNumber) ->
            new ApprovalDecision(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("revision_id", UUID.class),
                QuotationAggregate.Decision.valueOf(resultSet.getString("decision")),
                resultSet.getObject("reviewer_id", UUID.class),
                resultSet.getString("reason"),
                instant(resultSet, "occurred_at")));
  }

  private List<TimelineEntry> timeline(TenantId tenantId, UUID quotationId) {
    return jdbc.query(
        """
        SELECT id, occurred_at, action, previous_state, new_state, safe_reason
          FROM quotation.audit_entry
         WHERE tenant_id = :tenantId AND quotation_id = :quotationId
         ORDER BY occurred_at, id
        """,
        ids(tenantId, "quotationId", quotationId),
        (resultSet, rowNumber) ->
            new TimelineEntry(
                resultSet.getObject("id", UUID.class),
                instant(resultSet, "occurred_at"),
                resultSet.getString("action"),
                resultSet.getString("previous_state"),
                resultSet.getString("new_state"),
                resultSet.getString("safe_reason")));
  }

  private void insertRevision(QuotationAggregate quotation, UUID actorId) {
    jdbc.update(
        """
        INSERT INTO quotation.quotation_revision
          (id, tenant_id, quotation_id, revision_no, partner_number, partner_display_name,
           partner_payment_term_days, partner_source_version, partner_captured_at,
           currency, requested_delivery_date, expires_at, payment_term_days,
           delivery_country_code, delivery_province, delivery_city, delivery_district,
           delivery_line1, delivery_postal_code, route_evaluation_id, route_policy_version,
           recommended_route_code, selected_route_code, route_override_reason,
           price_policy_version, approval_policy_version, subtotal, total, total_cost,
           estimated_margin_rate, route_charges, frozen_at, created_at, created_by,
           updated_at, updated_by, version)
        VALUES
          (:revisionId, :tenantId, :quotationId, :revisionNo, :partnerNumber, :partnerName,
           :partnerPaymentTermDays, :partnerSourceVersion, :partnerCapturedAt,
           :currency, :deliveryDate, :expiresAt, :paymentTermDays,
           :countryCode, :province, :city, :district, :line1, :postalCode,
           :routeEvaluationId, :routePolicyVersion, :recommendedRoute, :selectedRoute,
           :overrideReason, :pricePolicyVersion, :approvalPolicyVersion, :subtotal,
           :total, :totalCost, :marginRate, :routeCharges, :frozenAt, :revisionCreatedAt,
           :actorId, :updatedAt, :actorId, 0)
        """,
        revisionParameters(quotation, actorId));
  }

  private void updateRevision(QuotationAggregate quotation, UUID actorId) {
    jdbc.update(
        """
        UPDATE quotation.quotation_revision
           SET partner_number = :partnerNumber,
               partner_display_name = :partnerName,
               partner_payment_term_days = :partnerPaymentTermDays,
               partner_source_version = :partnerSourceVersion,
               partner_captured_at = :partnerCapturedAt,
               currency = :currency,
               requested_delivery_date = :deliveryDate,
               expires_at = :expiresAt,
               payment_term_days = :paymentTermDays,
               delivery_country_code = :countryCode,
               delivery_province = :province,
               delivery_city = :city,
               delivery_district = :district,
               delivery_line1 = :line1,
               delivery_postal_code = :postalCode,
               route_evaluation_id = :routeEvaluationId,
               route_policy_version = :routePolicyVersion,
               recommended_route_code = :recommendedRoute,
               selected_route_code = :selectedRoute,
               route_override_reason = :overrideReason,
               subtotal = :subtotal,
               total = :total,
               total_cost = :totalCost,
               estimated_margin_rate = :marginRate,
               route_charges = :routeCharges,
               frozen_at = :frozenAt,
               updated_at = :updatedAt,
               updated_by = :actorId,
               version = version + 1
         WHERE tenant_id = :tenantId AND id = :revisionId
        """,
        revisionParameters(quotation, actorId));
  }

  private void insertLines(QuotationAggregate quotation, UUID actorId) {
    for (PricedLine line : quotation.revision().pricing().lines()) {
      jdbc.update(
          """
          INSERT INTO quotation.quotation_line
            (id, tenant_id, revision_id, sku_id, sku_code, display_name, producer_name,
             region_name, country_code, category, vintage, volume_ml, units_per_case,
             package_type, sku_source_version, sku_captured_at, quantity, quantity_unit,
             preferred_supply_pool_id, supply_type, currency, list_unit_price, discount_rate,
             net_unit_price, allocated_charges, line_total, cost_unit_price, line_cost,
             estimated_margin_rate, manual_price, price_source_version, created_at, created_by,
             updated_at, updated_by, version)
          VALUES
            (:id, :tenantId, :revisionId, :skuId, :skuCode, :displayName, :producerName,
             :regionName, :countryCode, :category, :vintage, :volumeMl, :unitsPerCase,
             :packageType, :skuSourceVersion, :skuCapturedAt, :quantity, :quantityUnit,
             :supplyPoolId, :supplyType, :currency, :listPrice, :discountRate, :netPrice,
             :charges, :lineTotal, :costPrice, :lineCost, :marginRate, :manualPrice,
             :priceVersion, :occurredAt, :actorId, :occurredAt, :actorId, 0)
          """,
          lineParameters(quotation, line, actorId));
    }
  }

  private void insertRequirements(
      QuotationAggregate quotation, List<Requirement> requirements, UUID actorId) {
    for (Requirement requirement : requirements) {
      jdbc.update(
          """
          INSERT INTO quotation.approval_requirement
            (id, tenant_id, revision_id, rule_id, code, actual_value, threshold, message,
             created_at, created_by, updated_at, updated_by, version)
          VALUES
            (:id, :tenantId, :revisionId, :ruleId, :code, :actualValue, :threshold, :message,
             :occurredAt, :actorId, :occurredAt, :actorId, 0)
          """,
          new MapSqlParameterSource()
              .addValue("id", UUID.randomUUID())
              .addValue("tenantId", quotation.tenantId().value())
              .addValue("revisionId", quotation.revision().id())
              .addValue("ruleId", requirement.ruleId())
              .addValue("code", requirement.code())
              .addValue("actualValue", requirement.actualValue())
              .addValue("threshold", requirement.threshold())
              .addValue("message", requirement.message())
              .addValue("occurredAt", timestamp(quotation.updatedAt()))
              .addValue("actorId", actorId));
    }
  }

  private void openWorkItem(QuotationAggregate quotation, UUID actorId) {
    jdbc.update(
        """
        INSERT INTO quotation.approval_work_item
          (id, tenant_id, quotation_id, revision_id, status, candidate_permission,
           created_at, created_by, updated_at, updated_by, version)
        VALUES
          (:id, :tenantId, :quotationId, :revisionId, 'OPEN', 'quotation:approve',
           :occurredAt, :actorId, :occurredAt, :actorId, 0)
        """,
        actionParameters(quotation, actorId).addValue("id", UUID.randomUUID()));
  }

  private void completeWorkItems(QuotationAggregate quotation, UUID actorId) {
    jdbc.update(
        """
        UPDATE quotation.approval_work_item
           SET status = 'COMPLETED', completed_at = :occurredAt,
               updated_at = :occurredAt, updated_by = :actorId, version = version + 1
         WHERE tenant_id = :tenantId AND quotation_id = :quotationId AND status = 'OPEN'
        """,
        actionParameters(quotation, actorId));
  }

  private void insertAudit(
      QuotationAggregate before,
      QuotationAggregate after,
      UUID actorId,
      String action,
      String reason) {
    insertAudit(before, after, actorId, "INTERNAL_USER", action, reason);
  }

  private void insertAudit(
      QuotationAggregate before,
      QuotationAggregate after,
      UUID actorId,
      String actorType,
      String action,
      String reason) {
    jdbc.update(
        """
        INSERT INTO quotation.audit_entry
          (id, tenant_id, quotation_id, revision_id, actor_id, actor_type, action,
           previous_state, new_state, safe_reason, occurred_at,
           created_at, created_by, updated_at, updated_by, version)
        VALUES
          (:id, :tenantId, :quotationId, :revisionId, :actorId, :actorType, :action,
           :previousState, :newState, :reason, :occurredAt,
           :occurredAt, :actorId, :occurredAt, :actorId, 0)
        """,
        actionParameters(after, actorId)
            .addValue("id", UUID.randomUUID())
            .addValue("actorType", actorType)
            .addValue("action", action)
            .addValue("previousState", before == null ? null : before.status().name())
            .addValue("newState", after.status().name())
            .addValue("reason", reason));
  }

  private void insertPublication(
      QuotationAggregate quotation, UUID actorId, String eventType, String safeReason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("quotationId", quotation.id());
    payload.put("quotationNumber", quotation.number());
    payload.put("revision", quotation.currentRevision());
    payload.put("status", quotation.status().name());
    payload.put("actorId", actorId);
    if (safeReason != null) {
      payload.put("reason", safeReason);
    }
    try {
      jdbc.update(
          """
          INSERT INTO quotation.local_event_publication
            (event_id, tenant_id, quotation_id, revision_id, event_type, event_version,
             payload, status, occurred_at, published_at, created_at, created_by,
             updated_at, updated_by, version)
          VALUES
            (:eventId, :tenantId, :quotationId, :revisionId, :eventType, 1,
             CAST(:payload AS jsonb), 'COMPLETED', :occurredAt, :occurredAt,
             :occurredAt, :actorId, :occurredAt, :actorId, 0)
          """,
          actionParameters(quotation, actorId)
              .addValue("eventId", UUID.randomUUID())
              .addValue("eventType", eventType)
              .addValue("payload", jsonMapper.writeValueAsString(payload)));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize quotation publication", exception);
    }
  }

  private void updateQuotation(QuotationAggregate quotation, long expectedVersion, UUID actorId) {
    int updated =
        jdbc.update(
            """
            UPDATE quotation.quotation
               SET status = :status,
                   current_revision_no = :revisionNo,
                   current_revision_id = :revisionId,
                   submitted_by_id = :submittedById,
                   updated_at = :updatedAt,
                   updated_by = :actorId,
                   version = version + 1
             WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
            """,
            quotationParameters(quotation, actorId).addValue("expectedVersion", expectedVersion));
    if (updated == 0) {
      throw versionConflict(quotation.tenantId(), quotation.id());
    }
  }

  private QuotationProblem versionConflict(TenantId tenantId, UUID quotationId) {
    Map<String, Object> current =
        jdbc.queryForMap(
            "SELECT version, status FROM quotation.quotation WHERE tenant_id = :tenantId AND id = :id",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("id", quotationId));
    return new QuotationProblem(
        HttpStatus.PRECONDITION_FAILED,
        "RESOURCE_VERSION_CONFLICT",
        "Quotation changed after it was loaded",
        ((Number) current.get("version")).longValue(),
        current.get("status").toString());
  }

  private MapSqlParameterSource quotationParameters(QuotationAggregate quotation, UUID actorId) {
    return new MapSqlParameterSource()
        .addValue("id", quotation.id())
        .addValue("tenantId", quotation.tenantId().value())
        .addValue("number", quotation.number())
        .addValue("partnerId", quotation.partnerId())
        .addValue("status", quotation.status().name())
        .addValue("revisionNo", quotation.currentRevision())
        .addValue("revisionId", quotation.revision().id())
        .addValue("ownerId", quotation.ownerId())
        .addValue("submittedById", quotation.submittedById())
        .addValue("createdAt", timestamp(quotation.createdAt()))
        .addValue("updatedAt", timestamp(quotation.updatedAt()))
        .addValue("actorId", actorId)
        .addValue("version", quotation.version());
  }

  private MapSqlParameterSource revisionParameters(QuotationAggregate quotation, UUID actorId) {
    Revision revision = quotation.revision();
    PartnerSnapshot partner = revision.partnerSnapshot();
    DraftTerms terms = revision.terms();
    Address address = terms.deliveryAddress();
    PricingResult pricing = revision.pricing();
    return new MapSqlParameterSource()
        .addValue("revisionId", revision.id())
        .addValue("tenantId", quotation.tenantId().value())
        .addValue("quotationId", quotation.id())
        .addValue("revisionNo", revision.number())
        .addValue("partnerNumber", partner.number())
        .addValue("partnerName", partner.displayName())
        .addValue("partnerPaymentTermDays", partner.paymentTermDays())
        .addValue("partnerSourceVersion", partner.sourceVersion())
        .addValue("partnerCapturedAt", timestamp(partner.capturedAt()))
        .addValue("currency", terms.currency())
        .addValue("deliveryDate", terms.requestedDeliveryDate())
        .addValue("expiresAt", timestamp(terms.expiresAt()))
        .addValue("paymentTermDays", terms.paymentTermDays())
        .addValue("countryCode", address.countryCode())
        .addValue("province", address.province())
        .addValue("city", address.city())
        .addValue("district", address.district())
        .addValue("line1", address.line1())
        .addValue("postalCode", address.postalCode())
        .addValue("routeEvaluationId", revision.routeEvaluationId())
        .addValue("routePolicyVersion", revision.routePolicyVersion())
        .addValue("recommendedRoute", name(revision.recommendedRouteCode()))
        .addValue("selectedRoute", name(revision.selectedRouteCode()))
        .addValue("overrideReason", revision.routeOverrideReason())
        .addValue("pricePolicyVersion", revision.pricePolicyVersion())
        .addValue("approvalPolicyVersion", revision.approvalPolicyVersion())
        .addValue("subtotal", pricing.subtotal())
        .addValue("total", pricing.total())
        .addValue("totalCost", pricing.totalCost())
        .addValue("marginRate", pricing.marginRate())
        .addValue("routeCharges", pricing.routeCharges())
        .addValue("frozenAt", timestamp(revision.frozenAt()))
        .addValue("revisionCreatedAt", timestamp(revision.createdAt()))
        .addValue("updatedAt", timestamp(quotation.updatedAt()))
        .addValue("actorId", actorId);
  }

  private MapSqlParameterSource lineParameters(
      QuotationAggregate quotation, PricedLine line, UUID actorId) {
    SkuSnapshot sku = line.sku();
    return new MapSqlParameterSource()
        .addValue("id", line.lineId())
        .addValue("tenantId", quotation.tenantId().value())
        .addValue("revisionId", quotation.revision().id())
        .addValue("skuId", sku.skuId())
        .addValue("skuCode", sku.skuCode())
        .addValue("displayName", sku.displayName())
        .addValue("producerName", sku.producerName())
        .addValue("regionName", sku.regionName())
        .addValue("countryCode", sku.countryCode())
        .addValue("category", sku.category())
        .addValue("vintage", sku.vintage())
        .addValue("volumeMl", sku.volumeMl())
        .addValue("unitsPerCase", sku.unitsPerCase())
        .addValue("packageType", sku.packageType())
        .addValue("skuSourceVersion", sku.sourceVersion())
        .addValue("skuCapturedAt", timestamp(sku.capturedAt()))
        .addValue("quantity", line.quantity())
        .addValue("quantityUnit", line.unit().name())
        .addValue("supplyPoolId", line.preferredSupplyPoolId())
        .addValue("supplyType", line.supplyType())
        .addValue("currency", line.currency())
        .addValue("listPrice", line.listUnitPrice())
        .addValue("discountRate", line.discountRate())
        .addValue("netPrice", line.netUnitPrice())
        .addValue("charges", line.allocatedCharges())
        .addValue("lineTotal", line.lineTotal())
        .addValue("costPrice", line.costUnitPrice())
        .addValue("lineCost", line.lineCost())
        .addValue("marginRate", line.marginRate())
        .addValue("manualPrice", line.manualPrice())
        .addValue("priceVersion", line.priceSourceVersion())
        .addValue("occurredAt", timestamp(quotation.updatedAt()))
        .addValue("actorId", actorId);
  }

  private MapSqlParameterSource actionParameters(QuotationAggregate quotation, UUID actorId) {
    return new MapSqlParameterSource()
        .addValue("tenantId", quotation.tenantId().value())
        .addValue("quotationId", quotation.id())
        .addValue("revisionId", quotation.revision().id())
        .addValue("occurredAt", timestamp(quotation.updatedAt()))
        .addValue("actorId", actorId);
  }

  private void deleteLines(TenantId tenantId, UUID revisionId) {
    jdbc.update(
        "DELETE FROM quotation.quotation_line WHERE tenant_id = :tenantId AND revision_id = :revisionId",
        ids(tenantId, "revisionId", revisionId));
  }

  private static MapSqlParameterSource ids(TenantId tenantId, String name, UUID id) {
    return new MapSqlParameterSource().addValue("tenantId", tenantId.value()).addValue(name, id);
  }

  private static void requireTenant(TenantId expected, TenantId... actual) {
    for (TenantId tenantId : actual) {
      if (!expected.equals(tenantId)) {
        throw new IllegalArgumentException("Tenant scope mismatch");
      }
    }
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getTimestamp(column).toInstant();
  }

  private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
    Timestamp timestamp = resultSet.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static Set<String> stringSet(ResultSet resultSet, String column) throws SQLException {
    java.sql.Array array = resultSet.getArray(column);
    if (array == null) {
      return Set.of();
    }
    return Set.copyOf(List.of((String[]) array.getArray()));
  }

  private static TradeRouteCode route(String value) {
    return value == null ? null : TradeRouteCode.valueOf(value);
  }

  private static String name(TradeRouteCode value) {
    return value == null ? null : value.name();
  }

  private record BigDecimalValues(
      java.math.BigDecimal subtotal,
      java.math.BigDecimal total,
      java.math.BigDecimal totalCost,
      java.math.BigDecimal marginRate,
      java.math.BigDecimal routeCharges) {}

  private record PortalAccessRow(
      UUID id,
      TenantId tenantId,
      UUID quotationId,
      UUID revisionId,
      UUID partnerId,
      String supplierPublicId,
      String supplierDisplayName,
      String termsVersion,
      Set<String> allowedActions,
      Instant expiresAt) {}
}
