package com.rom.cellarbridge.tradeplanning.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rom.cellarbridge.catalog.CatalogItemStatus;
import com.rom.cellarbridge.catalog.CatalogSearchQuery;
import com.rom.cellarbridge.catalog.CatalogSearchQuery.CatalogSearchItem;
import com.rom.cellarbridge.catalog.CatalogSearchQuery.CatalogSkuView;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.InventorySupplyQuery;
import com.rom.cellarbridge.inventory.InventorySupplyQuery.RouteAvailability;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.partner.PartnerEligibilityService;
import com.rom.cellarbridge.partner.PartnerEligibilityService.EligibilitySnapshot;
import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.EvaluationCommand;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.LineDemand;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteEvaluation;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TradePlanningApplicationServiceTest {

  private static final TenantId TENANT_ID =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final UUID PARTNER_ID = UUID.fromString("53000000-0000-4000-8000-000000000001");
  private static final UUID ACTOR_ID = UUID.fromString("11000000-0000-4000-8000-000000000003");
  private static final UUID SKU_ID = UUID.fromString("34000000-0000-4000-8000-000000000001");
  private static final UUID LINE_ID = UUID.fromString("74000000-0000-4000-8000-000000000001");
  private static final UUID SKU_ID_2 = UUID.fromString("34000000-0000-4000-8000-000000000002");
  private static final UUID LINE_ID_2 = UUID.fromString("74000000-0000-4000-8000-000000000002");
  private static final UUID SUPPLY_POOL_ID =
      UUID.fromString("36000000-0000-4000-8000-000000000001");
  private static final Instant CLOCK_VALUE = Instant.parse("2026-07-14T12:00:00.123456789Z");
  private static final Instant NOW = CLOCK_VALUE.truncatedTo(ChronoUnit.MICROS);

  private final PartnerEligibilityService partnerEligibilityService =
      mock(PartnerEligibilityService.class);
  private final CatalogSearchQuery catalogSearchQuery = mock(CatalogSearchQuery.class);
  private final InventorySupplyQuery inventorySupplyQuery = mock(InventorySupplyQuery.class);
  private final TradePlanningRepository repository = mock(TradePlanningRepository.class);
  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private final CountingClock clock = new CountingClock(CLOCK_VALUE);
  private final TradePlanningApplicationService service =
      new TradePlanningApplicationService(
          partnerEligibilityService,
          catalogSearchQuery,
          inventorySupplyQuery,
          repository,
          jsonMapper,
          clock);

  @Test
  void hashesTheUnitAwarePolicyInputThatIsPersisted() throws Exception {
    when(partnerEligibilityService.requireActive(TENANT_ID, PARTNER_ID))
        .thenReturn(
            new EligibilitySnapshot(
                PARTNER_ID,
                "PARTNER-001",
                "Aurora Market Services",
                7,
                Set.of("SH_GENERAL_TRADE", "NB_BONDED_B2B"),
                Set.of("CN"),
                Set.of("CNY"),
                30,
                null,
                NOW));
    when(catalogSearchQuery.get(TENANT_ID, SKU_ID))
        .thenReturn(
            new CatalogSearchItem(
                new CatalogSkuView(
                    SKU_ID,
                    "CB-MTV-2019-750X6",
                    "Moonlit Terrace",
                    "Producer",
                    "Region",
                    "CN",
                    "RED_WINE",
                    "2019",
                    750,
                    6,
                    "CASE",
                    CatalogItemStatus.ACTIVE,
                    0,
                    NOW),
                List.of()));
    when(inventorySupplyQuery.findRouteAvailability(TENANT_ID, Set.of(SKU_ID), NOW))
        .thenReturn(
            List.of(
                new RouteAvailability(
                    SUPPLY_POOL_ID,
                    SKU_ID,
                    "NB_BONDED_B2B",
                    SupplyType.DOMESTIC_ON_HAND,
                    "CNY",
                    QuantityUnit.CASE,
                    new BigDecimal("7"),
                    NOW,
                    "HIGH",
                    "INV-READY-2026-01",
                    NOW),
                new RouteAvailability(
                    SUPPLY_POOL_ID,
                    SKU_ID,
                    "SH_GENERAL_TRADE",
                    SupplyType.DOMESTIC_ON_HAND,
                    "CNY",
                    QuantityUnit.CASE,
                    new BigDecimal("56"),
                    NOW,
                    "HIGH",
                    "INV-READY-2026-01",
                    NOW),
                new RouteAvailability(
                    SUPPLY_POOL_ID,
                    SKU_ID,
                    "SH_GENERAL_TRADE",
                    SupplyType.DOMESTIC_ON_HAND,
                    "CNY",
                    QuantityUnit.BOTTLE,
                    new BigDecimal("10"),
                    NOW,
                    "MEDIUM",
                    "INV-READY-2026-01",
                    NOW)));

    RouteEvaluation evaluation = service.evaluate(TENANT_ID, command());

    ArgumentCaptor<String> inputSummary = ArgumentCaptor.forClass(String.class);
    verify(repository)
        .save(eq(TENANT_ID), eq(PARTNER_ID), eq(ACTOR_ID), inputSummary.capture(), any());
    JsonNode input = jsonMapper.readTree(inputSummary.getValue());
    assertThat(input.path("schemaVersion").asInt()).isEqualTo(3);
    assertThat(input.path("routePolicyVersion").asText()).isEqualTo("ROUTE-2026-03");
    assertThat(input.path("supplyDecisionPolicyVersion").asText())
        .isEqualTo("SUPPLY-DECISION-2026-01");
    assertThat(input.path("evaluationTime").asText()).isEqualTo(NOW.toString());
    assertThat(input.path("lines").path(0).path("quotationLineId").asText())
        .isEqualTo(LINE_ID.toString());
    assertThat(input.path("lines").path(0).path("requestedQuantity").asText())
        .isEqualTo("6.000000");
    assertThat(input.path("lines").path(0).path("quantityUnit").asText()).isEqualTo("CASE");
    assertThat(input.path("lines").path(0).path("moqCaseEquivalentQuantity").asText())
        .isEqualTo("6.000000");
    assertThat(input.path("lines").path(0).path("preferredSupplyPoolId").asText())
        .isEqualTo(SUPPLY_POOL_ID.toString());
    assertThat(input.path("availability")).hasSize(3);
    assertThat(input.path("availability").path(0).path("quantityUnit").asText()).isEqualTo("CASE");
    assertThat(input.path("availability").path(1).path("quantityUnit").asText())
        .isEqualTo("BOTTLE");
    assertThat(input.path("availability").path(2).path("quantityUnit").asText()).isEqualTo("CASE");
    assertThat(evaluation.inputHash()).isEqualTo(sha256(inputSummary.getValue()));
    assertThat(evaluation.evaluatedAt()).isEqualTo(NOW);
    assertThat(evaluation.override().occurredAt()).isEqualTo(NOW);
    assertThat(evaluation.supplyDecision().decidedAt()).isEqualTo(NOW);
    assertThat(evaluation.supplyDecision().sourceRouteInputHash())
        .isEqualTo(evaluation.inputHash());
    assertThat(clock.calls()).isEqualTo(1);
    verify(inventorySupplyQuery).findRouteAvailability(TENANT_ID, Set.of(SKU_ID), NOW);
  }

  @Test
  void canonicalSnapshotIgnoresInputOrderButHashesMaterialChanges() throws Exception {
    EligibilitySnapshot firstPartner =
        partner(
            new LinkedHashSet<>(List.of("SH_GENERAL_TRADE", "NB_BONDED_B2B")),
            new LinkedHashSet<>(List.of("CN", "HK")),
            new LinkedHashSet<>(List.of("CNY", "HKD")));
    EligibilitySnapshot reversedPartner =
        partner(
            new LinkedHashSet<>(List.of("NB_BONDED_B2B", "SH_GENERAL_TRADE")),
            new LinkedHashSet<>(List.of("HK", "CN")),
            new LinkedHashSet<>(List.of("HKD", "CNY")));
    LineDemand first =
        new LineDemand(
            LINE_ID,
            SKU_ID,
            new BigDecimal("6"),
            TradePlanningQuantityUnit.CASE,
            new BigDecimal("6"),
            null);
    LineDemand second =
        new LineDemand(
            LINE_ID_2,
            SKU_ID_2,
            new BigDecimal("2"),
            TradePlanningQuantityUnit.BOTTLE,
            new BigDecimal("1"),
            null);
    List<RouteAvailability> availability =
        List.of(
            routeAvailability(SUPPLY_POOL_ID, SKU_ID, "SH_GENERAL_TRADE", QuantityUnit.CASE, "6"),
            routeAvailability(
                UUID.fromString("36000000-0000-4000-8000-000000000002"),
                SKU_ID_2,
                "NB_BONDED_B2B",
                QuantityUnit.BOTTLE,
                "2"));
    String firstJson =
        jsonMapper.writeValueAsString(
            RouteEvaluationInputSnapshotV3.create(
                NOW, snapshotCommand("CNY", List.of(first, second)), firstPartner, availability));
    String reversedJson =
        jsonMapper.writeValueAsString(
            RouteEvaluationInputSnapshotV3.create(
                NOW,
                snapshotCommand("CNY", List.of(second, first)),
                reversedPartner,
                availability.reversed()));
    String changedJson =
        jsonMapper.writeValueAsString(
            RouteEvaluationInputSnapshotV3.create(
                NOW, snapshotCommand("HKD", List.of(first, second)), firstPartner, availability));

    assertThat(reversedJson).isEqualTo(firstJson);
    assertThat(sha256(reversedJson)).isEqualTo(sha256(firstJson));
    assertThat(sha256(changedJson)).isNotEqualTo(sha256(firstJson));
    assertThat(jsonMapper.readTree(firstJson).propertyNames())
        .containsExactly(
            "schemaVersion",
            "routePolicyVersion",
            "supplyDecisionPolicyVersion",
            "evaluationTime",
            "actorId",
            "managerOverrideAllowed",
            "requestedRouteCode",
            "overrideReason",
            "partner",
            "commercial",
            "lines",
            "availability");
  }

  private static EvaluationCommand command() {
    return new EvaluationCommand(
        PARTNER_ID,
        "CNY",
        "CN",
        LocalDate.of(2026, 8, 1),
        30,
        List.of(
            new LineDemand(
                LINE_ID,
                SKU_ID,
                new BigDecimal("6"),
                TradePlanningQuantityUnit.CASE,
                new BigDecimal("6"),
                SUPPLY_POOL_ID)),
        TradeRouteCode.NB_BONDED_B2B,
        "Use bonded delivery",
        ACTOR_ID,
        true);
  }

  private static EvaluationCommand snapshotCommand(String currency, List<LineDemand> lines) {
    return new EvaluationCommand(
        PARTNER_ID,
        currency,
        "CN",
        LocalDate.of(2026, 8, 1),
        30,
        lines,
        null,
        null,
        ACTOR_ID,
        true);
  }

  private static EligibilitySnapshot partner(
      Set<String> routes, Set<String> regions, Set<String> currencies) {
    return new EligibilitySnapshot(
        PARTNER_ID,
        "PARTNER-001",
        "Aurora Market Services",
        7,
        routes,
        regions,
        currencies,
        30,
        null,
        NOW);
  }

  private static RouteAvailability routeAvailability(
      UUID pool, UUID sku, String route, QuantityUnit unit, String quantity) {
    return new RouteAvailability(
        pool,
        sku,
        route,
        SupplyType.DOMESTIC_ON_HAND,
        "CNY",
        unit,
        new BigDecimal(quantity),
        NOW,
        "HIGH",
        "INV-READY-2026-01",
        NOW);
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static final class CountingClock extends Clock {
    private final Instant value;
    private int calls;

    private CountingClock(Instant value) {
      this.value = value;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      calls++;
      return value;
    }

    private int calls() {
      return calls;
    }
  }
}
