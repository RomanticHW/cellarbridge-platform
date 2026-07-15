package com.rom.cellarbridge.tradeorder.internal.web;

import com.rom.cellarbridge.tradeorder.TradeOrderStatus;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderApplicationService.DetailView;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderApplicationService.PageView;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderApplicationService.TimelineView;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.CommercialSnapshot;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.DeliveryAddress;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Line;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class TradeOrderWebMapper {

  private TradeOrderWebMapper() {}

  static OrderPageResponse internalPage(PageView page) {
    return new OrderPageResponse(
        page.items().stream().map(TradeOrderWebMapper::internalSummary).toList(),
        new PageInfoResponse(page.nextCursor(), page.hasNext(), page.pageSize()));
  }

  static BuyerOrderPageResponse buyerPage(PageView page) {
    return new BuyerOrderPageResponse(
        page.items().stream().map(TradeOrderWebMapper::buyerSummary).toList(),
        new PageInfoResponse(page.nextCursor(), page.hasNext(), page.pageSize()));
  }

  static OrderDetailResponse internalDetail(DetailView view) {
    TradeOrder order = view.order();
    CommercialSnapshot snapshot = order.commercialSnapshot();
    return new OrderDetailResponse(
        order.id(),
        order.number(),
        order.sourceQuotationNumber(),
        snapshot.customer().partnerId(),
        snapshot.customer().displayName(),
        order.status(),
        money(snapshot.totalAmount(), snapshot.currency()),
        snapshot.route().code(),
        order.createdAt(),
        order.version(),
        new SourceQuotationResponse(
            order.sourceQuotationId(),
            order.sourceQuotationNumber(),
            order.sourceRevisionId(),
            order.sourceRevision()),
        new CommercialSnapshotResponse(
            new CustomerSnapshotResponse(
                snapshot.customer().partnerId(),
                snapshot.customer().partnerNumber(),
                snapshot.customer().displayName(),
                snapshot.customer().sourceVersion()),
            snapshot.lines().stream().map(line -> internalLine(line, snapshot.currency())).toList(),
            snapshot.paymentTermDays(),
            snapshot.acceptedTermsVersion(),
            snapshot.requestedDeliveryDate(),
            address(snapshot.deliveryAddress()),
            new RouteSnapshotResponse(
                snapshot.route().code(),
                snapshot.route().policyVersion(),
                snapshot.route().estimatedDeliveryDate()),
            order.acceptedAt(),
            order.snapshotHash()),
        reservationProjection(),
        notStarted("Fulfillment begins after reservation succeeds"),
        notStarted("Settlement begins after fulfillment"),
        view.timeline().stream().map(TradeOrderWebMapper::internalTimeline).toList(),
        List.of());
  }

  static BuyerOrderDetailResponse buyerDetail(DetailView view) {
    TradeOrder order = view.order();
    CommercialSnapshot snapshot = order.commercialSnapshot();
    return new BuyerOrderDetailResponse(
        order.id(),
        order.number(),
        order.sourceQuotationNumber(),
        snapshot.customer().displayName(),
        order.status(),
        money(snapshot.totalAmount(), snapshot.currency()),
        snapshot.route().code(),
        order.createdAt(),
        new BuyerSourceQuotationResponse(order.sourceQuotationNumber(), order.sourceRevision()),
        new BuyerCommercialSnapshotResponse(
            new BuyerCustomerSnapshotResponse(
                snapshot.customer().partnerNumber(), snapshot.customer().displayName()),
            snapshot.lines().stream().map(line -> buyerLine(line, snapshot.currency())).toList(),
            snapshot.paymentTermDays(),
            snapshot.acceptedTermsVersion(),
            snapshot.requestedDeliveryDate(),
            address(snapshot.deliveryAddress()),
            new BuyerRouteSnapshotResponse(
                snapshot.route().code(), snapshot.route().estimatedDeliveryDate()),
            order.acceptedAt()),
        reservationProjection(),
        notStarted("Fulfillment begins after reservation succeeds"),
        notStarted("Settlement begins after fulfillment"),
        view.timeline().stream().map(TradeOrderWebMapper::buyerTimeline).toList(),
        List.of());
  }

  private static OrderSummaryResponse internalSummary(TradeOrder order) {
    CommercialSnapshot snapshot = order.commercialSnapshot();
    return new OrderSummaryResponse(
        order.id(),
        order.number(),
        order.sourceQuotationNumber(),
        snapshot.customer().partnerId(),
        snapshot.customer().displayName(),
        order.status(),
        money(snapshot.totalAmount(), snapshot.currency()),
        snapshot.route().code(),
        order.createdAt(),
        order.version());
  }

  private static BuyerOrderSummaryResponse buyerSummary(TradeOrder order) {
    CommercialSnapshot snapshot = order.commercialSnapshot();
    return new BuyerOrderSummaryResponse(
        order.id(),
        order.number(),
        order.sourceQuotationNumber(),
        snapshot.customer().displayName(),
        order.status(),
        money(snapshot.totalAmount(), snapshot.currency()),
        snapshot.route().code(),
        order.createdAt());
  }

  private static OrderLineResponse internalLine(Line line, String currency) {
    return new OrderLineResponse(
        line.id(),
        line.sourceQuotationLineId(),
        line.skuId(),
        line.skuCode(),
        line.description(),
        quantity(line.quantity(), line.unit()),
        money(line.netUnitPrice(), currency),
        money(line.lineTotal(), currency),
        line.supplyPoolId(),
        line.supplyType());
  }

  private static BuyerOrderLineResponse buyerLine(Line line, String currency) {
    return new BuyerOrderLineResponse(
        line.skuCode(),
        line.description(),
        quantity(line.quantity(), line.unit()),
        money(line.netUnitPrice(), currency),
        money(line.lineTotal(), currency));
  }

  private static TimelineResponse internalTimeline(TimelineView entry) {
    return new TimelineResponse(
        entry.id(),
        entry.occurredAt(),
        entry.action(),
        entry.previousState(),
        entry.newState(),
        entry.safeReason(),
        entry.visibility());
  }

  private static BuyerTimelineResponse buyerTimeline(TimelineView entry) {
    return new BuyerTimelineResponse(entry.occurredAt(), entry.action(), entry.newState());
  }

  private static ProcessResponse reservationProjection() {
    return new ProcessResponse("PENDING", "Inventory reservation is pending");
  }

  private static ProcessResponse notStarted(String message) {
    return new ProcessResponse("NOT_STARTED", message);
  }

  private static MoneyResponse money(BigDecimal amount, String currency) {
    return new MoneyResponse(amount.stripTrailingZeros().toPlainString(), currency);
  }

  private static QuantityResponse quantity(BigDecimal value, String unit) {
    return new QuantityResponse(value.stripTrailingZeros().toPlainString(), unit);
  }

  private static AddressResponse address(DeliveryAddress address) {
    return new AddressResponse(
        address.countryCode(),
        address.province(),
        address.city(),
        address.district(),
        address.line1(),
        address.postalCode());
  }

  record MoneyResponse(String amount, String currency) {}

  record QuantityResponse(String value, String unit) {}

  record PageInfoResponse(String nextCursor, boolean hasNext, int pageSize) {}

  record OrderSummaryResponse(
      UUID id,
      String number,
      String sourceQuotationNumber,
      UUID partnerId,
      String partnerName,
      TradeOrderStatus status,
      MoneyResponse total,
      String routeCode,
      Instant createdAt,
      long version) {}

  record BuyerOrderSummaryResponse(
      UUID id,
      String number,
      String sourceQuotationNumber,
      String partnerName,
      TradeOrderStatus status,
      MoneyResponse total,
      String routeCode,
      Instant createdAt) {}

  record OrderPageResponse(List<OrderSummaryResponse> items, PageInfoResponse pageInfo) {}

  record BuyerOrderPageResponse(List<BuyerOrderSummaryResponse> items, PageInfoResponse pageInfo) {}

  record SourceQuotationResponse(UUID id, String number, UUID revisionId, int revision) {}

  record BuyerSourceQuotationResponse(String number, int revision) {}

  record CustomerSnapshotResponse(
      UUID partnerId, String partnerNumber, String displayName, int sourceVersion) {}

  record BuyerCustomerSnapshotResponse(String partnerNumber, String displayName) {}

  record AddressResponse(
      String countryCode,
      String province,
      String city,
      String district,
      String line1,
      String postalCode) {}

  record RouteSnapshotResponse(
      String code, String policyVersion, LocalDate estimatedDeliveryDate) {}

  record BuyerRouteSnapshotResponse(String code, LocalDate estimatedDeliveryDate) {}

  record OrderLineResponse(
      UUID orderLineId,
      UUID sourceQuotationLineId,
      UUID skuId,
      String skuCode,
      String description,
      QuantityResponse quantity,
      MoneyResponse netUnitPrice,
      MoneyResponse lineTotal,
      UUID supplyPoolId,
      String supplyType) {}

  record BuyerOrderLineResponse(
      String skuCode,
      String description,
      QuantityResponse quantity,
      MoneyResponse netUnitPrice,
      MoneyResponse lineTotal) {}

  record CommercialSnapshotResponse(
      CustomerSnapshotResponse customer,
      List<OrderLineResponse> lines,
      int paymentTermDays,
      String acceptedTermsVersion,
      LocalDate requestedDeliveryDate,
      AddressResponse deliveryAddress,
      RouteSnapshotResponse route,
      Instant capturedAt,
      String snapshotHash) {}

  record BuyerCommercialSnapshotResponse(
      BuyerCustomerSnapshotResponse customer,
      List<BuyerOrderLineResponse> lines,
      int paymentTermDays,
      String acceptedTermsVersion,
      LocalDate requestedDeliveryDate,
      AddressResponse deliveryAddress,
      BuyerRouteSnapshotResponse route,
      Instant capturedAt) {}

  record ProcessResponse(String status, String message) {}

  record TimelineResponse(
      UUID id,
      Instant occurredAt,
      String action,
      String previousState,
      TradeOrderStatus newState,
      String safeReason,
      String visibility) {}

  record BuyerTimelineResponse(Instant occurredAt, String action, TradeOrderStatus newState) {}

  record OrderDetailResponse(
      UUID id,
      String number,
      String sourceQuotationNumber,
      UUID partnerId,
      String partnerName,
      TradeOrderStatus status,
      MoneyResponse total,
      String routeCode,
      Instant createdAt,
      long version,
      SourceQuotationResponse sourceQuotation,
      CommercialSnapshotResponse commercialSnapshot,
      ProcessResponse reservation,
      ProcessResponse fulfillment,
      ProcessResponse settlement,
      List<TimelineResponse> timeline,
      List<String> allowedActions) {}

  record BuyerOrderDetailResponse(
      UUID id,
      String number,
      String sourceQuotationNumber,
      String partnerName,
      TradeOrderStatus status,
      MoneyResponse total,
      String routeCode,
      Instant createdAt,
      BuyerSourceQuotationResponse sourceQuotation,
      BuyerCommercialSnapshotResponse commercialSnapshot,
      ProcessResponse reservation,
      ProcessResponse fulfillment,
      ProcessResponse settlement,
      List<BuyerTimelineResponse> timeline,
      List<String> allowedActions) {}
}
