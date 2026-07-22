package com.rom.cellarbridge.tradeorder.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rom.cellarbridge.fulfillment.FulfillmentCompletedV1;
import com.rom.cellarbridge.fulfillment.FulfillmentPlanCreatedV1;
import com.rom.cellarbridge.fulfillment.FulfillmentStepStartedV1;
import com.rom.cellarbridge.fulfillment.PublicMilestoneReachedV1;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.tradeorder.TradeOrderStatus;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderRepository.FulfillmentFact;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.CommercialSnapshot;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Customer;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.DeliveryAddress;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Line;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Route;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TradeOrderFulfillmentEventServiceTest {
  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final Instant OCCURRED_AT = Instant.parse("2026-07-18T08:00:00Z");

  private final TradeOrderRepository repository = mock(TradeOrderRepository.class);
  private final TradeOrderFulfillmentEventService service =
      new TradeOrderFulfillmentEventService(repository);

  @Test
  void advancesTheOrderAndKeepsOnlyPublicMilestonesCustomerVisible() {
    TradeOrder reserved = order().reservationSucceeded(OCCURRED_AT.minusSeconds(4));
    TradeOrder ready = reserved.markReadyForFulfillment(OCCURRED_AT.minusSeconds(3));
    TradeOrder inProgress = ready.beginFulfillment(OCCURRED_AT.minusSeconds(2));
    TradeOrder fulfilled = inProgress.fulfill(OCCURRED_AT.plusSeconds(3));
    when(repository.findForUpdate(TENANT, reserved.id()))
        .thenReturn(
            Optional.of(reserved),
            Optional.of(ready),
            Optional.of(inProgress),
            Optional.of(fulfilled));
    when(repository.hasTimelineEvent(eq(TENANT), eq(reserved.id()), any())).thenReturn(false);

    service.planCreated(
        delivery(FulfillmentPlanCreatedV1.TYPE, reserved.id()), reserved.id(), OCCURRED_AT);
    service.stepStarted(
        delivery(FulfillmentStepStartedV1.TYPE, reserved.id()),
        reserved.id(),
        "WAREHOUSE_PICKING",
        OCCURRED_AT.plusSeconds(1));
    service.completed(
        delivery(FulfillmentCompletedV1.TYPE, reserved.id()),
        reserved.id(),
        OCCURRED_AT.plusSeconds(3));
    service.publicMilestone(
        delivery(PublicMilestoneReachedV1.TYPE, reserved.id()),
        reserved.id(),
        "SIGNED_DELIVERY",
        "Signed delivery",
        OCCURRED_AT.plusSeconds(3));

    ArgumentCaptor<TradeOrder> transitioned = ArgumentCaptor.forClass(TradeOrder.class);
    ArgumentCaptor<FulfillmentFact> transitionFact = ArgumentCaptor.forClass(FulfillmentFact.class);
    verify(repository, times(3))
        .saveFulfillmentTransition(
            eq(TENANT), any(), transitioned.capture(), transitionFact.capture(), any());
    assertThat(transitioned.getAllValues())
        .extracting(TradeOrder::status)
        .containsExactly(
            TradeOrderStatus.READY_FOR_FULFILLMENT,
            TradeOrderStatus.IN_FULFILLMENT,
            TradeOrderStatus.FULFILLED);
    assertThat(transitionFact.getAllValues())
        .extracting(FulfillmentFact::visibility)
        .containsExactly("INTERNAL", "INTERNAL", "CUSTOMER");

    ArgumentCaptor<FulfillmentFact> publicFact = ArgumentCaptor.forClass(FulfillmentFact.class);
    verify(repository)
        .appendFulfillmentMilestone(eq(TENANT), eq(fulfilled), publicFact.capture(), any());
    assertThat(publicFact.getValue().code()).isEqualTo("SIGNED_DELIVERY");
    assertThat(publicFact.getValue().safeMessage()).isEqualTo("Signed delivery");
    assertThat(publicFact.getValue().visibility()).isEqualTo("CUSTOMER");
  }

  private static EventDelivery delivery(String type, UUID orderId) {
    return new EventDelivery(
        UUID.randomUUID(),
        TENANT.value(),
        type,
        1,
        OCCURRED_AT,
        "fulfillment",
        new EventDelivery.Subject("FULFILLMENT_PLAN", UUID.randomUUID(), "FUL-TEST"),
        UUID.randomUUID(),
        orderId,
        "{}");
  }

  private static TradeOrder order() {
    UUID skuId = UUID.randomUUID();
    Line line =
        new Line(
            UUID.randomUUID(),
            UUID.randomUUID(),
            skuId,
            "SKU-TEST",
            "Fulfillment test item",
            BigDecimal.ONE,
            "CASE",
            new BigDecimal("100.0000"),
            new BigDecimal("100.0000"),
            null,
            null,
            "DOMESTIC_ON_HAND");
    CommercialSnapshot snapshot =
        new CommercialSnapshot(
            1,
            new Customer(UUID.randomUUID(), "CUS-TEST", "Fulfillment Customer", 1),
            "CNY",
            new BigDecimal("100.0000"),
            30,
            new Route("SH_GENERAL_TRADE", "ROUTE-2026-01", LocalDate.parse("2026-08-01")),
            "TERMS-1",
            LocalDate.parse("2026-08-01"),
            new DeliveryAddress("CN", "Shanghai", "Shanghai", "Pudong", "1 Test Road", "200000"),
            List.of(line));
    return TradeOrder.createLegacy(
        UUID.randomUUID(),
        TENANT,
        "ORD-FULFILLMENT-TEST",
        UUID.randomUUID(),
        UUID.randomUUID(),
        "QUO-FULFILLMENT-TEST",
        1,
        UUID.randomUUID(),
        UUID.randomUUID(),
        OCCURRED_AT.minusSeconds(10),
        UUID.randomUUID(),
        snapshot,
        "a".repeat(64),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        OCCURRED_AT.minusSeconds(5));
  }
}
