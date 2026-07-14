package com.rom.cellarbridge.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.quotation.QuotationAcceptedV1;
import com.rom.cellarbridge.quotation.QuotationSnapshotHashV1;
import com.rom.cellarbridge.quotation.internal.application.TradeOrderCreatedEventHandler;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import com.rom.cellarbridge.tradeorder.internal.application.QuotationAcceptedEventHandler;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TradeOrderConversionIntegrationTest extends PostgresIntegrationTestSupport {

  private static final UUID TENANT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID PARTNER_ID = UUID.fromString("53000000-0000-4000-8000-000000000001");
  private static final UUID OWNER_ID = UUID.fromString("11200000-0000-4000-8000-000000000001");
  private static final String NORTH_SALES = "order-north-sales-token";
  private static final String NORTH_BUYER = "order-north-buyer-token";
  private static final String NORTH_MANAGER = "order-north-manager-token";
  private static final String NORTH_TRADE = "order-north-trade-token";
  private static final String HARBOR_MANAGER = "order-harbor-manager-token";
  private static final Set<String> BUYER_FORBIDDEN_FIELDS =
      Set.of(
          "partnerId",
          "skuId",
          "orderLineId",
          "sourceQuotationLineId",
          "supplyPoolId",
          "supplyType",
          "snapshotHash",
          "sourceEventId",
          "acceptanceId",
          "correlationId",
          "causationId",
          "version",
          "cost",
          "margin",
          "score",
          "warehouseId",
          "lotId");

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  private int port;

  @Autowired private LocalEventDeliveryService deliveryService;
  @Autowired private JdbcEventFailureRecorder failureRecorder;
  @Autowired private QuotationAcceptedEventHandler handler;
  @Autowired private TradeOrderCreatedEventHandler quotationHandler;
  @Autowired private JdbcEventPublicationSource publicationSource;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper jsonMapper;

  @Test
  void createsOneOrderAndOneReliableNextFactAcrossDuplicatesAndConcurrency() {
    UUID quotationId = UUID.randomUUID();
    EventDelivery delivery = delivery(UUID.randomUUID(), quotationId, 'a', PARTNER_ID);
    int consumers = 12;
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<LocalEventDeliveryService.DeliveryOutcome>> attempts;
    try (ExecutorService executor = Executors.newFixedThreadPool(consumers)) {
      attempts =
          java.util.stream.IntStream.range(0, consumers)
              .mapToObj(
                  ignored ->
                      CompletableFuture.supplyAsync(
                          () -> {
                            await(start);
                            return deliveryService.deliver(handler, delivery);
                          },
                          executor))
              .toList();
      start.countDown();
      CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).join();
    }

    List<LocalEventDeliveryService.DeliveryOutcome> outcomes =
        attempts.stream().map(CompletableFuture::join).toList();
    assertThat(outcomes)
        .containsExactlyInAnyOrderElementsOf(
            java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(LocalEventDeliveryService.DeliveryOutcome.PROCESSED),
                    java.util.stream.Stream.generate(
                            () -> LocalEventDeliveryService.DeliveryOutcome.ALREADY_PROCESSED)
                        .limit(consumers - 1))
                .toList());
    assertOrderGraph(quotationId, 1, 1, 1);
    assertThat(inboxValue(delivery.eventId(), "status")).isEqualTo("PROCESSED");
    assertThat(inboxNumber(delivery.eventId(), "duplicate_count")).isEqualTo(consumers - 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT updated_at >= created_at FROM platform_event.event_inbox WHERE event_id = ?",
                Boolean.class,
                delivery.eventId()))
        .isTrue();
    assertThat(createdPublicationStatus(quotationId)).isEqualTo("PENDING");
    JsonNode createdPayload = createdPayload(quotationId);
    assertThat(createdPayload.propertyNames())
        .containsExactlyInAnyOrder(
            "orderId",
            "orderNumber",
            "sourceQuotationId",
            "sourceRevisionId",
            "sourceQuotationNumber",
            "sourceRevision",
            "sourceOwnerId",
            "sourceEventId",
            "acceptanceId",
            "acceptedAt",
            "customer",
            "currency",
            "totalAmount",
            "paymentTermDays",
            "route",
            "acceptedTermsVersion",
            "requestedDeliveryDate",
            "deliveryAddress",
            "snapshotHash",
            "lines",
            "createdAt");
    assertThat(createdPayload.path("customer").propertyNames())
        .containsExactlyInAnyOrder("partnerId", "partnerNumber", "displayName", "sourceVersion");
    assertThat(createdPayload.path("lines").path(0).propertyNames())
        .containsExactlyInAnyOrder(
            "orderLineId",
            "sourceQuotationLineId",
            "skuId",
            "skuCode",
            "description",
            "quantity",
            "unit",
            "netUnitPrice",
            "lineTotal",
            "supplyPoolId",
            "supplyType");

    EventDelivery equivalentReplay = delivery(UUID.randomUUID(), quotationId, 'a', PARTNER_ID);
    assertThat(deliveryService.deliver(handler, equivalentReplay))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
    assertOrderGraph(quotationId, 1, 1, 1);
  }

  @Test
  void normalizesLegacyAcceptedHashesAndReplaysHistoricalOrders() throws Exception {
    UUID quotationId = UUID.randomUUID();
    EventDelivery current = delivery(UUID.randomUUID(), quotationId, 'l', PARTNER_ID);
    String bareHash = parsedPayload(current.payloadJson()).snapshotHash();
    EventDelivery legacy = legacySnapshotHash(current);

    assertThat(deliveryService.deliver(handler, legacy))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
    UUID orderId = orderId(quotationId);
    assertThat(snapshotHash("trade_order.trade_order", orderId)).isEqualTo(bareHash);
    assertThat(createdPayload(quotationId).path("snapshotHash").asText()).isEqualTo(bareHash);

    assertThat(
            deliveryService.deliver(
                handler,
                legacySnapshotHash(delivery(UUID.randomUUID(), quotationId, 'l', PARTNER_ID))))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
    assertOrderGraph(quotationId, 1, 1, 1);

    overwriteSnapshotHashForHistory(
        "trade_order.trade_order",
        "trade_order_commercial_snapshot_immutable",
        orderId,
        "sha256:" + bareHash);
    EventDelivery bareReplay = delivery(UUID.randomUUID(), quotationId, 'l', PARTNER_ID);
    assertThat(deliveryService.deliver(handler, bareReplay))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
    assertThat(snapshotHash("trade_order.trade_order", orderId)).isEqualTo("sha256:" + bareHash);
    assertOrderGraph(quotationId, 1, 1, 1);

    overwriteSnapshotHashForHistory(
        "trade_order.trade_order", "trade_order_commercial_snapshot_immutable", orderId, "invalid");
    assertThatThrownBy(() -> handler.handle(bareReplay))
        .isInstanceOfSatisfying(
            EventHandlingException.class,
            failure ->
                assertThat(failure)
                    .returns("ORDER_SOURCE_QUOTATION_CONFLICT", EventHandlingException::failureCode)
                    .returns(false, EventHandlingException::retryable));
  }

  @Test
  void convertsALegacyAcceptedEventWithoutAnOwnerAndKeepsSalesAccessClosed() throws Exception {
    UUID quotationId = UUID.randomUUID();
    EventDelivery legacyDelivery =
        withoutPayloadField(
            mutate(
                delivery(UUID.randomUUID(), quotationId, '9', PARTNER_ID),
                payload -> {
                  payload.put("totalAmount", "100.0000");
                  ObjectNode line = (ObjectNode) payload.path("lines").path(0);
                  line.put("netUnitPrice", "100.0000");
                  line.put("lineTotal", "100.0000");
                  JsonNode id = payload.remove("quotationId");
                  payload.set("quotationId", id);
                }),
            "sourceOwnerId");

    assertThat(deliveryService.deliver(handler, legacyDelivery))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);

    UUID orderId = orderId(quotationId);
    assertThat(
            jdbc.queryForObject(
                "SELECT source_owner_id FROM trade_order.trade_order WHERE id = ?",
                UUID.class,
                orderId))
        .isNull();
    assertThat(createdPayload(quotationId).has("sourceOwnerId")).isFalse();
    assertThat(get("/api/v1/orders/" + orderId, NORTH_MANAGER).status()).isEqualTo(200);
    assertThat(get("/api/v1/orders/" + orderId, NORTH_SALES).status()).isEqualTo(404);
  }

  @Test
  void createsAnImmutableZeroValueOrderFromAValidAcceptedEvent() {
    UUID quotationId = UUID.randomUUID();
    assertThat(
            deliveryService.deliver(
                handler, delivery(UUID.randomUUID(), quotationId, '0', PARTNER_ID)))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
    assertOrderGraph(quotationId, 1, 1, 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT total_amount FROM trade_order.trade_order WHERE source_quotation_id = ?",
                BigDecimal.class,
                quotationId))
        .isEqualTo(new BigDecimal("0.0000"));
  }

  @Test
  void rejectsAppendingALineAfterTheCommercialSnapshotIsSealed() {
    UUID quotationId = UUID.randomUUID();
    deliveryService.deliver(handler, delivery(UUID.randomUUID(), quotationId, '8', PARTNER_ID));
    UUID orderId = orderId(quotationId);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO trade_order.order_line
                      (id, tenant_id, order_id, source_quotation_line_id, line_number,
                       sku_id, sku_code, description, quantity, quantity_unit, currency,
                       net_unit_price, line_total, supply_pool_id, supply_type,
                       created_at, created_by, updated_at, updated_by, version)
                    VALUES (?, ?, ?, ?, 2, ?, 'SKU-002', 'Reserve wine case', 1, 'CASE',
                            'CNY', 120.00, 120.00, NULL, 'DOMESTIC_ON_HAND',
                            CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, 0)
                    """,
                    UUID.randomUUID(),
                    TENANT_ID,
                    orderId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    OWNER_ID,
                    OWNER_ID))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM trade_order.order_line WHERE order_id = ?",
                Integer.class,
                orderId))
        .isEqualTo(1);
  }

  @Test
  void preservesTheWinnerAndRecordsADifferentSnapshotAsFinal() {
    UUID quotationId = UUID.randomUUID();
    EventDelivery winner = delivery(UUID.randomUUID(), quotationId, 'b', PARTNER_ID);
    deliveryService.deliver(handler, winner);

    EventDelivery conflict = delivery(UUID.randomUUID(), quotationId, 'c', PARTNER_ID);
    RuntimeException failure = catchFailure(() -> deliveryService.deliver(handler, conflict));
    assertThat(failureRecorder.record(handler, conflict, failure))
        .isEqualTo(JdbcEventFailureRecorder.FailureOutcome.FAILED_FINAL);

    assertOrderGraph(quotationId, 1, 1, 1);
    assertThat(inboxValue(conflict.eventId(), "status")).isEqualTo("FAILED_FINAL");
    assertThat(inboxValue(conflict.eventId(), "last_error_code"))
        .isEqualTo("ORDER_SOURCE_QUOTATION_CONFLICT");
  }

  @Test
  void rollsBackBeforeCommitThenRecoversWithoutASecondOrder() {
    UUID quotationId = UUID.randomUUID();
    EventDelivery delivery = delivery(UUID.randomUUID(), quotationId, 'd', PARTNER_ID);
    jdbc.execute(
        """
        CREATE FUNCTION platform_event.reject_order_publication_for_test()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
          IF NEW.event_type = 'cellarbridge.order.created.v1' THEN
            RAISE EXCEPTION 'order publication unavailable';
          END IF;
          RETURN NEW;
        END;
        $$
        """);
    jdbc.execute(
        """
        CREATE TRIGGER reject_order_publication_for_test
        BEFORE INSERT ON platform_event.event_publication
        FOR EACH ROW EXECUTE FUNCTION platform_event.reject_order_publication_for_test()
        """);
    try {
      RuntimeException failure = catchFailure(() -> deliveryService.deliver(handler, delivery));
      assertThat(failureRecorder.record(handler, delivery, failure))
          .isEqualTo(JdbcEventFailureRecorder.FailureOutcome.RETRY_SCHEDULED);
    } finally {
      jdbc.execute(
          "DROP TRIGGER IF EXISTS reject_order_publication_for_test ON platform_event.event_publication");
      jdbc.execute("DROP FUNCTION IF EXISTS platform_event.reject_order_publication_for_test()");
    }

    assertOrderGraph(quotationId, 0, 0, 0);
    assertThat(inboxValue(delivery.eventId(), "status")).isEqualTo("FAILED_RETRYABLE");
    jdbc.update(
        """
        UPDATE platform_event.event_inbox
           SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
         WHERE consumer_name = ? AND event_id = ?
        """,
        handler.consumerName(),
        delivery.eventId());

    assertThat(deliveryService.deliver(handler, delivery))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
    assertOrderGraph(quotationId, 1, 1, 1);
    assertThat(inboxNumber(delivery.eventId(), "attempts")).isEqualTo(2);
    assertThat(deliveryService.deliver(handler, delivery))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.ALREADY_PROCESSED);
    assertOrderGraph(quotationId, 1, 1, 1);
  }

  @Test
  void enforcesTenantOwnerAndBuyerPartnerScopeWithClosedResponseShapes() throws Exception {
    UUID buyerQuotationId = UUID.randomUUID();
    EventDelivery buyerOrder =
        delivery(UUID.randomUUID(), buyerQuotationId, 'e', PARTNER_ID, OWNER_ID);
    deliveryService.deliver(handler, buyerOrder);
    UUID buyerOrderId = orderId(buyerQuotationId);

    UUID otherPartner = UUID.fromString("53000000-0000-4000-8000-000000000099");
    UUID otherPartnerQuotationId = UUID.randomUUID();
    deliveryService.deliver(
        handler, delivery(UUID.randomUUID(), otherPartnerQuotationId, 'f', otherPartner, OWNER_ID));
    UUID otherPartnerOrderId = orderId(otherPartnerQuotationId);

    UUID managerOwnedQuotationId = UUID.randomUUID();
    deliveryService.deliver(
        handler,
        delivery(
            UUID.randomUUID(),
            managerOwnedQuotationId,
            '1',
            PARTNER_ID,
            UUID.fromString("11200000-0000-4000-8000-000000000003")));
    UUID managerOwnedOrderId = orderId(managerOwnedQuotationId);

    assertThat(get("/api/v1/orders/" + buyerOrderId, null).status()).isEqualTo(401);
    assertThat(get("/api/v1/orders/" + buyerOrderId, NORTH_BUYER).status()).isEqualTo(403);
    assertThat(get("/api/v1/buyer/orders/" + buyerOrderId, NORTH_MANAGER).status()).isEqualTo(403);
    assertThat(get("/api/v1/orders/" + buyerOrderId, HARBOR_MANAGER).status()).isEqualTo(404);
    assertThat(get("/api/v1/orders/" + managerOwnedOrderId, NORTH_SALES).status()).isEqualTo(404);
    assertThat(get("/api/v1/orders/" + managerOwnedOrderId, NORTH_MANAGER).status()).isEqualTo(200);
    assertThat(get("/api/v1/orders/" + managerOwnedOrderId, NORTH_TRADE).status()).isEqualTo(200);
    assertThat(get("/api/v1/buyer/orders/" + otherPartnerOrderId, NORTH_BUYER).status())
        .isEqualTo(404);
    ApiResponse invalidPage = get("/api/v1/orders?pageSize=0", NORTH_MANAGER);
    assertThat(invalidPage.status()).withFailMessage(invalidPage.raw()).isEqualTo(400);
    assertThat(invalidPage.body().path("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(invalidPage.body().path("retryable").asBoolean()).isFalse();

    ApiResponse buyerDetail = get("/api/v1/buyer/orders/" + buyerOrderId, NORTH_BUYER);
    assertThat(buyerDetail.status()).withFailMessage(buyerDetail.raw()).isEqualTo(200);
    assertThat(buyerDetail.body().propertyNames())
        .containsExactlyInAnyOrder(
            "id",
            "number",
            "sourceQuotationNumber",
            "partnerName",
            "status",
            "total",
            "routeCode",
            "createdAt",
            "sourceQuotation",
            "commercialSnapshot",
            "reservation",
            "fulfillment",
            "settlement",
            "timeline",
            "allowedActions");
    assertThat(allPropertyNames(buyerDetail.body()))
        .doesNotContainAnyElementsOf(BUYER_FORBIDDEN_FIELDS);
    assertThat(buyerDetail.body().path("commercialSnapshot").path("lines").path(0).propertyNames())
        .containsExactlyInAnyOrder(
            "skuCode", "description", "quantity", "netUnitPrice", "lineTotal");
    assertThat(buyerDetail.body().path("reservation").path("status").asText()).isEqualTo("PENDING");

    ApiResponse internalDetail = get("/api/v1/orders/" + buyerOrderId, NORTH_SALES);
    assertThat(internalDetail.status()).withFailMessage(internalDetail.raw()).isEqualTo(200);
    assertThat(allPropertyNames(internalDetail.body()))
        .doesNotContain("sourceEventId", "acceptanceId", "correlationId", "causationId");
    assertThat(internalDetail.body().path("commercialSnapshot").path("snapshotHash").asText())
        .isEqualTo(parsedPayload(buyerOrder.payloadJson()).snapshotHash());

    ApiResponse buyerPage = get("/api/v1/buyer/orders?pageSize=100", NORTH_BUYER);
    assertThat(buyerPage.status()).withFailMessage(buyerPage.raw()).isEqualTo(200);
    assertThat(buyerPage.body().path("items"))
        .allSatisfy(
            item -> assertThat(item.path("partnerName").asText()).isEqualTo("North Cellars Buyer"));
    assertThat(
            java.util.stream.StreamSupport.stream(
                    buyerPage.body().path("items").spliterator(), false)
                .map(item -> item.path("id").asText()))
        .doesNotContain(otherPartnerOrderId.toString());
  }

  @Test
  void convertsARealAcceptedQuotationAndExposesTheProtectedOrderLinkEventually() throws Exception {
    IssuedQuotation issued = issueQuotation();
    ApiResponse publicView = get(issued.publicPath(), null);
    assertThat(publicView.status()).withFailMessage(publicView.raw()).isEqualTo(200);
    String termsVersion = publicView.body().path("termsVersion").asText();
    ApiResponse accepted =
        request(
            "POST",
            issued.publicPath() + "/acceptance",
            null,
            null,
            "order-conversion-acceptance-0001",
            """
            {"acceptedTermsVersion":"%s","buyerReference":"PO-2026-0714-21"}
            """
                .formatted(termsVersion)
                .strip());
    assertThat(accepted.status()).withFailMessage(accepted.raw()).isEqualTo(201);
    assertThat(accepted.body().path("orderCreationStatus").asText()).isEqualTo("PENDING");
    UUID acceptanceId = UUID.fromString(accepted.body().path("acceptanceId").asText());

    EventDelivery acceptedDelivery =
        publicationSource.findReady(handler, 500).stream()
            .filter(event -> event.subject().id().equals(issued.quotationId()))
            .findFirst()
            .orElseThrow();
    assertAcceptedEventSchema(acceptedDelivery);
    QuotationAcceptedV1.Payload acceptedPayload = parsedPayload(acceptedDelivery.payloadJson());
    assertThat(acceptedPayload.acceptedTermsVersion()).isNotBlank();
    assertThat(acceptedPayload.requestedDeliveryDate()).isNotNull();
    assertThat(acceptedPayload.deliveryAddress()).isNotNull();
    assertThat(QuotationSnapshotHashV1.hash(QuotationSnapshotHashV1.Snapshot.from(acceptedPayload)))
        .isEqualTo(acceptedPayload.snapshotHash());
    assertThat(deliveryService.deliver(handler, acceptedDelivery))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
    UUID orderId = orderId(issued.quotationId());
    EventDelivery createdDelivery =
        publicationSource.findReady(quotationHandler, 500).stream()
            .filter(event -> event.subject().id().equals(orderId))
            .findFirst()
            .orElseThrow();
    assertThat(jsonMapper.readTree(createdDelivery.payloadJson()).path("snapshotHash").asText())
        .isEqualTo(acceptedPayload.snapshotHash());

    EventDelivery legacyCreatedDelivery =
        legacySnapshotHash(withoutPayloadField(createdDelivery, "acceptanceId"));
    assertThat(deliveryService.deliver(quotationHandler, legacyCreatedDelivery))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
    assertThat(snapshotHash("quotation.order_link", createdDelivery.eventId()))
        .isEqualTo(acceptedPayload.snapshotHash());
    assertThat(deliveryService.deliver(quotationHandler, legacyCreatedDelivery))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.ALREADY_PROCESSED);

    overwriteSnapshotHashForHistory(
        "quotation.order_link",
        "quotation_order_link_append_only",
        createdDelivery.eventId(),
        "sha256:" + acceptedPayload.snapshotHash());
    assertThat(quotationHandler.handle(createdDelivery).resultReference())
        .isEqualTo(orderId.toString());
    assertThat(snapshotHash("quotation.order_link", createdDelivery.eventId()))
        .isEqualTo("sha256:" + acceptedPayload.snapshotHash());

    ApiResponse converted = get(issued.publicPath(), null);
    assertThat(converted.status()).withFailMessage(converted.raw()).isEqualTo(200);
    assertThat(converted.body().path("status").asText()).isEqualTo("CONVERTED");
    assertThat(converted.body().path("orderCreationStatus").asText()).isEqualTo("CREATED");
    assertThat(converted.body().path("orderId").asText()).isEqualTo(orderId.toString());
    assertThat(converted.body().path("orderNumber").asText()).startsWith("ORD-202607-");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM quotation.order_link WHERE quotation_id = ?",
                Integer.class,
                issued.quotationId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT acceptance_id FROM quotation.order_link WHERE quotation_id = ?",
                UUID.class,
                issued.quotationId()))
        .isEqualTo(acceptanceId);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM quotation.quotation WHERE id = ?",
                String.class,
                issued.quotationId()))
        .isEqualTo("CONVERTED");
  }

  @Test
  void snapshotHashV1MatchesThePublishedGoldenVector() throws Exception {
    JsonNode example =
        jsonMapper.readTree(
            Files.readString(
                contractPath("examples", "events", "quotation-accepted-v1.example.json")));
    QuotationAcceptedV1.Payload payload = parsedPayload(example.path("payload").toString());
    String expected = "a61b70c8847bbc58e64fa9d3dacdcf1277868166ea2c6af5762e89a010a96fad";

    assertThat(example.path("payload").path("snapshotHash").asText()).isEqualTo(expected);
    assertThat(QuotationSnapshotHashV1.hash(QuotationSnapshotHashV1.Snapshot.from(payload)))
        .isEqualTo(expected);

    ObjectNode varied = (ObjectNode) example.path("payload");
    varied.put("totalAmount", "256800.00");
    ((ObjectNode) varied.path("deliveryAddress")).putNull("district").putNull("postalCode");
    ArrayNode lines = (ArrayNode) varied.path("lines");
    ObjectNode second = (ObjectNode) lines.get(0).deepCopy();
    second.put("quotationLineId", "25000000-0000-4000-8000-000000000002");
    second.putNull("supplyPoolId");
    lines.add(second);
    String nullAndLineOrderHash =
        QuotationSnapshotHashV1.hash(
            QuotationSnapshotHashV1.Snapshot.from(parsedPayload(varied.toString())));
    assertThat(nullAndLineOrderHash)
        .isEqualTo("05f4ca8d80dc03577b0ef432a5cfa421f06bf9f6d2d50421a02fed0a16412683");
    lines.insert(0, lines.remove(1));
    assertThat(
            QuotationSnapshotHashV1.hash(
                QuotationSnapshotHashV1.Snapshot.from(parsedPayload(varied.toString()))))
        .isNotEqualTo(nullAndLineOrderHash);
  }

  @Test
  void databaseCatalogMatchesTheCurrentFitnessBaseline() {
    assertThat(
            jdbc.queryForList(
                """
                WITH application_schema(name) AS (VALUES
                  ('identity_access'),('partner'),('catalog'),('inventory'),('trade_planning'),
                  ('quotation'),('trade_order'),('platform_event')
                ), mutable(table_schema,table_name) AS (VALUES
                  ('identity_access','tenant'),('identity_access','user_mapping'),
                  ('partner','partner'),('catalog','wine_product'),('catalog','sku'),
                  ('inventory','warehouse'),('inventory','supply_pool'),('inventory','inventory_lot'),
                  ('trade_planning','evaluation'),('quotation','quotation'),
                  ('quotation','quotation_revision'),('trade_order','trade_order')
                ), owned AS (
                  SELECT table_schema, table_name FROM information_schema.tables
                   WHERE table_type = 'BASE TABLE'
                     AND table_schema IN (SELECT name FROM application_schema)
                ), violations AS (
                  SELECT 'unmanaged-schema:'||n.nspname problem FROM pg_namespace n
                   WHERE n.nspname NOT IN (SELECT name FROM application_schema)
                     AND n.nspname NOT IN ('public','information_schema','pg_catalog') AND n.nspname !~ '^pg_'
                  UNION ALL SELECT 'unmanaged:'||t.table_schema||'.'||t.table_name
                    FROM information_schema.tables t WHERE t.table_type='BASE TABLE'
                     AND t.table_schema NOT IN (SELECT name FROM application_schema)
                     AND t.table_schema NOT IN ('information_schema','pg_catalog')
                     AND t.table_schema !~ '^pg_'
                     AND (t.table_schema,t.table_name) <> ('public','flyway_schema_history')
                  UNION ALL SELECT 'tenant:'||o.table_schema||'.'||o.table_name FROM owned o
                   WHERE (o.table_schema,o.table_name) <> ('identity_access','tenant') AND NOT EXISTS
                     (SELECT FROM information_schema.columns c WHERE c.table_schema=o.table_schema
                       AND c.table_name=o.table_name AND c.column_name='tenant_id' AND c.is_nullable='NO')
                  UNION ALL SELECT 'version:'||m.table_schema||'.'||m.table_name FROM mutable m WHERE NOT EXISTS
                    (SELECT FROM information_schema.columns c WHERE c.table_schema=m.table_schema
                      AND c.table_name=m.table_name AND c.column_name='version' AND c.is_nullable='NO')
                  UNION ALL SELECT 'foreign-key:'||sn.nspname||'.'||s.relname FROM pg_constraint k
                    JOIN pg_class s ON s.oid=k.conrelid JOIN pg_namespace sn ON sn.oid=s.relnamespace
                    JOIN pg_class r ON r.oid=k.confrelid JOIN pg_namespace rn ON rn.oid=r.relnamespace
                   WHERE k.contype='f' AND sn.nspname<>rn.nspname
                     AND (sn.nspname IN (SELECT table_schema FROM owned)
                       OR rn.nspname IN (SELECT table_schema FROM owned))
                  UNION ALL SELECT 'money:'||c.table_schema||'.'||c.table_name||'.'||c.column_name
                    FROM information_schema.columns c JOIN owned o USING (table_schema,table_name)
                   WHERE c.column_name ~ '(^|_)(amount|price|cost|charge|charges|fee|fees|tax|balance|total|subtotal)$'
                     AND NOT (c.column_name='manual_price' AND c.data_type='boolean')
                     AND (c.data_type IS DISTINCT FROM 'numeric'
                       OR c.numeric_precision IS DISTINCT FROM 19 OR c.numeric_scale IS DISTINCT FROM 4)
                  UNION ALL SELECT 'quantity:'||c.table_schema||'.'||c.table_name||'.'||c.column_name
                    FROM information_schema.columns c JOIN owned o USING (table_schema,table_name)
                   WHERE c.column_name LIKE '%quantity'
                     AND (c.data_type IS DISTINCT FROM 'numeric'
                       OR c.numeric_precision IS DISTINCT FROM 19 OR c.numeric_scale IS DISTINCT FROM 6)
                  UNION ALL SELECT 'numeric-unclassified:'||c.table_schema||'.'||c.table_name||'.'||c.column_name
                    FROM information_schema.columns c JOIN owned o USING (table_schema,table_name)
                   WHERE c.data_type='numeric' AND c.column_name NOT LIKE '%quantity'
                     AND c.column_name !~ '(^|_)(amount|price|cost|charge|charges|fee|fees|tax|balance|total|subtotal|score|rate)$'
                  UNION ALL SELECT 'inventory-reservation-check' WHERE NOT EXISTS
                    (SELECT FROM pg_constraint k JOIN pg_class t ON t.oid=k.conrelid
                      JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='inventory'
                      AND t.relname='inventory_lot' AND k.contype='c' AND k.convalidated
                      AND k.conname='ck_inventory_lot_reservation'
                      AND pg_get_constraintdef(k.oid) LIKE '%reserved_quantity <= on_hand_quantity%')
                ) SELECT problem FROM violations
                """,
                String.class))
        .isEmpty();
  }

  @Test
  void rejectsInvalidAndMismatchedSnapshotsBeforeCreatingOrders() throws Exception {
    for (String field :
        List.of(
            "acceptedTermsVersion",
            "requestedDeliveryDate",
            "deliveryAddress",
            "paymentTermDays")) {
      assertInvalid('r', payload -> payload.remove(field));
    }
    assertInvalid('s', payload -> ((ObjectNode) payload.path("customer")).remove("sourceVersion"));
    assertInvalid('n', payload -> ((ObjectNode) payload.path("route")).putNull("code"));
    assertInvalid(
        'y', payload -> ((ObjectNode) payload.path("lines").path(0)).putNull("supplyType"));
    assertInvalid('h', payload -> payload.put("snapshotHash", "sha256:not-a-v1-hash"));

    UUID tamperedQuotation = UUID.randomUUID();
    EventDelivery tampered =
        mutate(
            delivery(UUID.randomUUID(), tamperedQuotation, 't', PARTNER_ID),
            payload ->
                ((ObjectNode) payload.path("customer")).put("displayName", "Tampered Buyer"));
    assertFinalFailure(
        legacySnapshotHash(tampered),
        tamperedQuotation,
        "QUOTATION_ACCEPTED_SNAPSHOT_HASH_MISMATCH");

    EventDelivery unexpected = delivery(UUID.randomUUID(), UUID.randomUUID(), 'u', PARTNER_ID);
    QuotationAcceptedEventHandler broken =
        new QuotationAcceptedEventHandler(null, null, jsonMapper, null);
    RuntimeException failure = catchFailure(() -> deliveryService.deliver(broken, unexpected));
    assertThat(failure).isInstanceOf(NullPointerException.class);
    assertThat(failureRecorder.record(broken, unexpected, failure))
        .isEqualTo(JdbcEventFailureRecorder.FailureOutcome.RETRY_SCHEDULED);
    assertThat(inboxValue(unexpected.eventId(), "status")).isEqualTo("FAILED_RETRYABLE");
    assertThat(inboxValue(unexpected.eventId(), "last_error_code"))
        .isEqualTo("UNEXPECTED_HANDLER_FAILURE");
  }

  private EventDelivery delivery(UUID eventId, UUID quotationId, char variant, UUID partnerId) {
    return delivery(eventId, quotationId, variant, partnerId, OWNER_ID);
  }

  private EventDelivery delivery(
      UUID eventId, UUID quotationId, char variant, UUID partnerId, UUID sourceOwnerId) {
    UUID revisionId = UUID.nameUUIDFromBytes(("revision:" + quotationId).getBytes());
    UUID acceptanceId = UUID.nameUUIDFromBytes(("acceptance:" + quotationId).getBytes());
    String quotationNumber = "QUO-" + quotationId.toString().substring(0, 8).toUpperCase();
    String amount = variant == '0' ? "0" : "100.00";
    String payload =
        """
        {
          "quotationId":"%s",
          "revisionId":"%s",
          "quotationNumber":"%s",
          "revision":1,
          "acceptanceId":"%s",
          "acceptedAt":"2026-07-14T09:00:00Z",
          "sourceOwnerId":"%s",
          "customer":{
            "partnerId":"%s",
            "partnerNumber":"PARTNER-001",
            "displayName":"North Cellars Buyer",
            "sourceVersion":1
          },
          "currency":"CNY",
          "totalAmount":"%s",
          "paymentTermDays":30,
          "route":{
            "code":"SH_GENERAL_TRADE",
            "policyVersion":"trade-route-policy-v1",
            "estimatedDeliveryDate":"2026-08-01"
          },
          "acceptedTermsVersion":"terms-v1",
          "requestedDeliveryDate":"2026-08-01",
          "deliveryAddress":{
            "countryCode":"CN",
            "province":"Shanghai",
            "city":"Shanghai",
            "district":null,
            "line1":"88 Harbor Avenue",
            "postalCode":"200120"
          },
          "snapshotHash":"%s",
          "lines":[{
            "quotationLineId":"61100000-0000-4000-8000-000000000001",
            "skuId":"34000000-0000-4000-8000-000000000001",
            "skuCode":"SKU-001",
            "description":"Synthetic wine case %s",
            "quantity":"1",
            "unit":"CASE",
            "netUnitPrice":"%s",
            "lineTotal":"%s",
            "supplyPoolId":null,
            "supplyType":"DOMESTIC_ON_HAND"
          }]
        }
        """
            .formatted(
                quotationId,
                revisionId,
                quotationNumber,
                acceptanceId,
                sourceOwnerId,
                partnerId,
                amount,
                "0".repeat(64),
                variant,
                amount,
                amount)
            .strip();
    try {
      ObjectNode body = (ObjectNode) jsonMapper.readTree(payload);
      body.put(
          "snapshotHash",
          QuotationSnapshotHashV1.hash(
              QuotationSnapshotHashV1.Snapshot.from(parsedPayload(body.toString()))));
      payload = jsonMapper.writeValueAsString(body);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException(exception);
    }
    return new EventDelivery(
        eventId,
        TENANT_ID,
        "cellarbridge.quotation.accepted.v1",
        1,
        Instant.parse("2026-07-14T09:00:01Z"),
        "quotation",
        new EventDelivery.Subject("QUOTATION", quotationId, quotationNumber),
        UUID.nameUUIDFromBytes(("correlation:" + quotationId).getBytes()),
        UUID.nameUUIDFromBytes(("causation:" + quotationId).getBytes()),
        payload);
  }

  private EventDelivery withoutPayloadField(EventDelivery delivery, String field) throws Exception {
    return mutate(delivery, payload -> payload.remove(field));
  }

  private EventDelivery legacySnapshotHash(EventDelivery delivery) throws Exception {
    return mutate(
        delivery,
        payload -> payload.put("snapshotHash", "sha256:" + payload.path("snapshotHash").asText()));
  }

  private EventDelivery mutate(EventDelivery delivery, Consumer<ObjectNode> mutation)
      throws Exception {
    ObjectNode payload = (ObjectNode) jsonMapper.readTree(delivery.payloadJson());
    mutation.accept(payload);
    return new EventDelivery(
        delivery.eventId(),
        delivery.tenantId(),
        delivery.eventType(),
        delivery.eventVersion(),
        delivery.occurredAt(),
        delivery.producer(),
        delivery.subject(),
        delivery.correlationId(),
        delivery.causationId(),
        jsonMapper.writeValueAsString(payload));
  }

  private QuotationAcceptedV1.Payload parsedPayload(String payload) {
    try {
      return jsonMapper.readValue(payload, QuotationAcceptedV1.Payload.class);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private void assertAcceptedEventSchema(EventDelivery delivery) throws Exception {
    ObjectNode event = jsonMapper.createObjectNode();
    event.put("id", delivery.eventId().toString());
    event.put("type", delivery.eventType());
    event.put("specVersion", "1.0");
    event.put("occurredAt", delivery.occurredAt().toString());
    event.put("tenantId", delivery.tenantId().toString());
    event.put("producer", delivery.producer());
    event.set("subject", jsonMapper.valueToTree(delivery.subject()));
    event.put("correlationId", delivery.correlationId().toString());
    event.put("causationId", delivery.causationId().toString());
    event.set("payload", jsonMapper.readTree(delivery.payloadJson()));
    String base = "https://cellarbridge.dev/schemas/events/";
    String envelopeSchema =
        Files.readString(contractPath("schemas", "events", "event-envelope.schema.json"));
    String acceptedSchema =
        Files.readString(contractPath("schemas", "events", "quotation-accepted-v1.schema.json"));
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder ->
                builder.schemas(
                    Map.of(
                        base + "event-envelope.schema.json", envelopeSchema,
                        base + "quotation-accepted-v1.schema.json", acceptedSchema)));
    assertThat(
            registry
                .getSchema(SchemaLocation.of(base + "quotation-accepted-v1.schema.json"))
                .validate(
                    event,
                    context ->
                        context.executionConfig(config -> config.formatAssertionsEnabled(true))))
        .isEmpty();
  }

  private static Path contractPath(String... path) {
    Path candidate = Path.of("contracts", path);
    return Files.exists(candidate) ? candidate : Path.of("..").resolve(candidate);
  }

  private void assertFinalFailure(EventDelivery delivery, UUID quotationId, String code) {
    RuntimeException failure = catchFailure(() -> deliveryService.deliver(handler, delivery));
    assertThat(failureRecorder.record(handler, delivery, failure))
        .isEqualTo(JdbcEventFailureRecorder.FailureOutcome.FAILED_FINAL);
    assertThat(inboxValue(delivery.eventId(), "last_error_code")).isEqualTo(code);
    assertOrderGraph(quotationId, 0, 0, 0);
  }

  private void assertInvalid(char variant, Consumer<ObjectNode> mutation) throws Exception {
    UUID quotationId = UUID.randomUUID();
    assertFinalFailure(
        mutate(delivery(UUID.randomUUID(), quotationId, variant, PARTNER_ID), mutation),
        quotationId,
        "QUOTATION_ACCEPTED_EVENT_INVALID");
  }

  private UUID orderId(UUID quotationId) {
    return jdbc.queryForObject(
        "SELECT id FROM trade_order.trade_order WHERE source_quotation_id = ?",
        UUID.class,
        quotationId);
  }

  private IssuedQuotation issueQuotation() throws Exception {
    String body =
        """
        {
          "partnerId":"53000000-0000-4000-8000-000000000001",
          "currency":"CNY",
          "requestedDeliveryDate":"%s",
          "expiresAt":"%s",
          "paymentTermDays":30,
          "deliveryAddress":{
            "countryCode":"CN",
            "province":"Shanghai",
            "city":"Shanghai",
            "district":"Pudong",
            "line1":"88 Harbor Avenue",
            "postalCode":"200120"
          },
          "lines":[{
            "skuId":"34000000-0000-4000-8000-000000000001",
            "quantity":{"value":"6","unit":"CASE"},
            "discountRate":"0.0000"
          }]
        }
        """
            .formatted(
                LocalDate.now(ZoneOffset.UTC).plusDays(9), Instant.now().plusSeconds(20L * 86_400L))
            .strip();
    ApiResponse created = request("POST", "/api/v1/quotations", NORTH_SALES, null, null, body);
    assertThat(created.status()).withFailMessage(created.raw()).isEqualTo(201);
    UUID quotationId = UUID.fromString(created.body().path("id").asText());
    assertProblem(
        request(
            "POST", "/api/v1/quotations/" + quotationId + "/issue", NORTH_SALES, null, null, null),
        428,
        "PRECONDITION_REQUIRED");
    assertProblem(
        request(
            "POST",
            "/api/v1/quotations/" + quotationId + "/submission",
            NORTH_SALES,
            "\"0\"",
            null,
            null),
        422,
        "QUOTE_HAS_NO_ELIGIBLE_ROUTE");
    assertProblem(
        request(
            "POST",
            "/api/v1/quotations/" + quotationId + "/issue",
            NORTH_SALES,
            "\"0\"",
            null,
            null),
        409,
        "QUOTE_ROUTE_NOT_ELIGIBLE");
    assertProblem(get("/api/v1/quotations?pageSize=0", NORTH_SALES), 400, "VALIDATION_FAILED");
    ApiResponse evaluated =
        request(
            "POST",
            "/api/v1/quotations/" + quotationId + "/route-evaluations",
            NORTH_SALES,
            "\"0\"",
            null,
            null);
    assertThat(evaluated.status()).withFailMessage(evaluated.raw()).isEqualTo(200);
    ApiResponse stale =
        request(
            "POST",
            "/api/v1/quotations/" + quotationId + "/submission",
            NORTH_SALES,
            "\"0\"",
            null,
            null);
    assertProblem(stale, 412, "RESOURCE_VERSION_CONFLICT");
    assertThat(stale.body().path("currentVersion").asLong()).isEqualTo(1);
    assertThat(stale.body().path("currentState").asText()).isEqualTo("DRAFT");
    ApiResponse submitted =
        request(
            "POST",
            "/api/v1/quotations/" + quotationId + "/submission",
            NORTH_SALES,
            "\"1\"",
            null,
            null);
    assertThat(submitted.status()).withFailMessage(submitted.raw()).isEqualTo(200);
    assertThat(submitted.body().path("status").asText()).isEqualTo("APPROVED");
    ApiResponse issued =
        request(
            "POST",
            "/api/v1/quotations/" + quotationId + "/issue",
            NORTH_SALES,
            "\"2\"",
            null,
            null);
    assertThat(issued.status()).withFailMessage(issued.raw()).isEqualTo(200);
    String portalUrl = issued.body().path("portalUrl").asText();
    return new IssuedQuotation(quotationId, "/api/v1" + portalUrl);
  }

  private ApiResponse get(String path, String token) throws Exception {
    return request("GET", path, token, null, null, null);
  }

  private static void assertProblem(ApiResponse response, int status, String code) {
    assertThat(response.status()).withFailMessage(response.raw()).isEqualTo(status);
    assertThat(response.body().path("status").asInt()).isEqualTo(status);
    assertThat(response.body().path("code").asText()).isEqualTo(code);
    assertThat(response.body().path("type").asText())
        .endsWith(code.toLowerCase().replace('_', '-'));
    assertThat(response.body().path("detail").asText()).isNotBlank();
    assertThat(response.body().path("retryable").asBoolean()).isFalse();
  }

  private ApiResponse request(
      String method, String path, String token, String ifMatch, String idempotencyKey, String body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Accept", "application/json");
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    if (ifMatch != null) {
      request.header("If-Match", ifMatch);
    }
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<String> response =
        httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new ApiResponse(
        response.statusCode(),
        response.body().isBlank()
            ? jsonMapper.createObjectNode()
            : jsonMapper.readTree(response.body()),
        response.body());
  }

  private static Set<String> allPropertyNames(JsonNode node) {
    Set<String> names = new HashSet<>();
    collectPropertyNames(node, names);
    return names;
  }

  private static void collectPropertyNames(JsonNode node, Set<String> names) {
    if (node.isObject()) {
      node.properties()
          .forEach(
              entry -> {
                names.add(entry.getKey());
                collectPropertyNames(entry.getValue(), names);
              });
    } else if (node.isArray()) {
      node.forEach(child -> collectPropertyNames(child, names));
    }
  }

  private void assertOrderGraph(
      UUID quotationId, int expectedOrders, int expectedLines, int expectedPublications) {
    assertThat(count("trade_order.trade_order", "source_quotation_id", quotationId))
        .isEqualTo(expectedOrders);
    Integer lines =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM trade_order.order_line line
              JOIN trade_order.trade_order orders
                ON orders.tenant_id = line.tenant_id AND orders.id = line.order_id
             WHERE orders.source_quotation_id = ?
            """,
            Integer.class,
            quotationId);
    assertThat(lines).isEqualTo(expectedLines);
    Integer publications =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM platform_event.event_publication
             WHERE event_type = 'cellarbridge.order.created.v1'
               AND payload #>> '{payload,sourceQuotationId}' = ?
            """,
            Integer.class,
            quotationId.toString());
    assertThat(publications).isEqualTo(expectedPublications);
  }

  private int count(String table, String column, UUID value) {
    if (!table.equals("trade_order.trade_order") || !column.equals("source_quotation_id")) {
      throw new IllegalArgumentException("Unsupported test query");
    }
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
    return count == null ? -1 : count;
  }

  private String createdPublicationStatus(UUID quotationId) {
    return jdbc.queryForObject(
        """
        SELECT status
          FROM platform_event.event_publication
         WHERE event_type = 'cellarbridge.order.created.v1'
           AND payload #>> '{payload,sourceQuotationId}' = ?
        """,
        String.class,
        quotationId.toString());
  }

  private JsonNode createdPayload(UUID quotationId) {
    String payload =
        jdbc.queryForObject(
            """
            SELECT (payload -> 'payload')::text
              FROM platform_event.event_publication
             WHERE event_type = 'cellarbridge.order.created.v1'
               AND payload #>> '{payload,sourceQuotationId}' = ?
            """,
            String.class,
            quotationId.toString());
    try {
      return jsonMapper.readTree(payload);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String snapshotHash(String table, UUID id) {
    if (!Set.of("trade_order.trade_order", "quotation.order_link").contains(table)) {
      throw new IllegalArgumentException("Unsupported historical fixture table");
    }
    return jdbc.queryForObject(
        "SELECT snapshot_hash FROM " + table + " WHERE id = ?", String.class, id);
  }

  private void overwriteSnapshotHashForHistory(
      String table, String trigger, UUID id, String snapshotHash) {
    String target = table + ":" + trigger;
    if (!target.equals("trade_order.trade_order:trade_order_commercial_snapshot_immutable")
        && !target.equals("quotation.order_link:quotation_order_link_append_only")) {
      throw new IllegalArgumentException("Unsupported historical fixture target");
    }
    jdbc.execute("ALTER TABLE " + table + " DISABLE TRIGGER " + trigger);
    try {
      jdbc.update("UPDATE " + table + " SET snapshot_hash = ? WHERE id = ?", snapshotHash, id);
    } finally {
      jdbc.execute("ALTER TABLE " + table + " ENABLE TRIGGER " + trigger);
    }
  }

  private String inboxValue(UUID eventId, String column) {
    if (!column.equals("status") && !column.equals("last_error_code")) {
      throw new IllegalArgumentException("Unsupported inbox column");
    }
    return jdbc.queryForObject(
        "SELECT "
            + column
            + " FROM platform_event.event_inbox WHERE consumer_name = ? AND event_id = ?",
        String.class,
        handler.consumerName(),
        eventId);
  }

  private int inboxNumber(UUID eventId, String column) {
    if (!column.equals("attempts") && !column.equals("duplicate_count")) {
      throw new IllegalArgumentException("Unsupported inbox column");
    }
    Integer value =
        jdbc.queryForObject(
            "SELECT "
                + column
                + " FROM platform_event.event_inbox WHERE consumer_name = ? AND event_id = ?",
            Integer.class,
            handler.consumerName(),
            eventId);
    return value == null ? -1 : value;
  }

  private static RuntimeException catchFailure(Runnable action) {
    try {
      action.run();
      throw new AssertionError("Expected delivery failure");
    } catch (RuntimeException failure) {
      return failure;
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  record ApiResponse(int status, JsonNode body, String raw) {}

  record IssuedQuotation(UUID quotationId, String publicPath) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class TradeOrderTestConfiguration {

    @Bean
    @Primary
    JwtDecoder tradeOrderJwtDecoder() {
      return token -> {
        String subject =
            switch (token) {
              case NORTH_SALES -> "11000000-0000-4000-8000-000000000001";
              case NORTH_BUYER -> "11000000-0000-4000-8000-000000000002";
              case NORTH_MANAGER -> "11000000-0000-4000-8000-000000000003";
              case NORTH_TRADE -> "11000000-0000-4000-8000-000000000005";
              case HARBOR_MANAGER -> "22000000-0000-4000-8000-000000000001";
              default -> throw new BadJwtException("Token is invalid");
            };
        String tenantCode = token.equals(HARBOR_MANAGER) ? "harbor-cellars" : "north-cellars";
        Instant now = Instant.now();
        return Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .issuer("http://localhost:8081/realms/cellarbridge")
            .subject(subject)
            .audience(List.of("cellarbridge-api"))
            .issuedAt(now.minusSeconds(30))
            .expiresAt(now.plusSeconds(300))
            .claim("tenant_code", tenantCode)
            .build();
      };
    }
  }
}
