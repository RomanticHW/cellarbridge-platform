package com.rom.cellarbridge.tradeorder.internal.application;

import com.rom.cellarbridge.identityaccess.AuthorizationService;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.tradeorder.TradeOrderStatus;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderRepository.CursorPosition;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderRepository.OrderPage;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeOrderApplicationService {

  private static final int DEFAULT_PAGE_SIZE = 25;
  private static final int MAX_PAGE_SIZE = 100;

  private final TradeOrderRepository repository;
  private final OrderCursorCodec cursorCodec;
  private final TenantContextHolder contextHolder;
  private final AuthorizationService authorizationService;

  TradeOrderApplicationService(
      TradeOrderRepository repository,
      OrderCursorCodec cursorCodec,
      TenantContextHolder contextHolder,
      AuthorizationService authorizationService) {
    this.repository = repository;
    this.cursorCodec = cursorCodec;
    this.contextHolder = contextHolder;
    this.authorizationService = authorizationService;
  }

  @Transactional(readOnly = true)
  public PageView listInternal(
      Set<TradeOrderStatus> statuses, UUID partnerId, Integer requestedPageSize, String cursor) {
    TenantContext context = requireInternalContext();
    int pageSize = pageSize(requestedPageSize);
    UUID ownerScope = ownerScope(context);
    String filterHash =
        filterHash("internal", statuses, partnerId, ownerScope == null ? null : ownerScope);
    CursorPosition after = cursorCodec.decode(context.tenantId(), filterHash, cursor);
    OrderPage page =
        repository.list(
            context.tenantId(), safeStatuses(statuses), partnerId, ownerScope, after, pageSize);
    return page(page, pageSize, context, filterHash, false);
  }

  @Transactional(readOnly = true)
  public PageView listBuyer(
      Set<TradeOrderStatus> statuses, Integer requestedPageSize, String cursor) {
    TenantContext context = requireBuyerContext();
    int pageSize = pageSize(requestedPageSize);
    String filterHash = filterHash("buyer", statuses, context.partnerId(), null);
    CursorPosition after = cursorCodec.decode(context.tenantId(), filterHash, cursor);
    OrderPage page =
        repository.list(
            context.tenantId(), safeStatuses(statuses), context.partnerId(), null, after, pageSize);
    return page(page, pageSize, context, filterHash, true);
  }

  @Transactional(readOnly = true)
  public DetailView getInternal(UUID orderId) {
    TenantContext context = requireInternalContext();
    TradeOrder order =
        repository
            .find(context.tenantId(), orderId, null, ownerScope(context))
            .orElseThrow(TradeOrderApplicationService::notFound);
    return new DetailView(
        order,
        repository.timeline(context.tenantId(), order.id(), false).stream()
            .map(TimelineView::from)
            .toList(),
        false);
  }

  @Transactional(readOnly = true)
  public DetailView getBuyer(UUID orderId) {
    TenantContext context = requireBuyerContext();
    TradeOrder order =
        repository
            .find(context.tenantId(), orderId, context.partnerId(), null)
            .orElseThrow(TradeOrderApplicationService::notFound);
    return new DetailView(
        order,
        repository.timeline(context.tenantId(), order.id(), true).stream()
            .map(TimelineView::from)
            .toList(),
        true);
  }

  private TenantContext requireInternalContext() {
    TenantContext context = contextHolder.requireCurrent();
    authorizationService.require(PermissionCode.ORDER_READ, context.tenantId());
    if (context.partnerId() != null) {
      throw forbidden("Buyer identities must use the Buyer order resource");
    }
    return context;
  }

  private TenantContext requireBuyerContext() {
    TenantContext context = contextHolder.requireCurrent();
    authorizationService.require(PermissionCode.ORDER_READ, context.tenantId());
    if (context.partnerId() == null) {
      throw forbidden("A Buyer partner mapping is required");
    }
    return context;
  }

  private PageView page(
      OrderPage page, int pageSize, TenantContext context, String filterHash, boolean buyer) {
    String nextCursor =
        page.nextPosition() == null
            ? null
            : cursorCodec.encode(context.tenantId(), filterHash, page.nextPosition());
    return new PageView(page.items(), nextCursor, page.hasNext(), pageSize, buyer);
  }

  private static UUID ownerScope(TenantContext context) {
    if (context.hasRoleCode("sales-representative")
        && !context.hasRoleCode("sales-manager")
        && !context.hasRoleCode("tenant-administrator")) {
      return context.userId();
    }
    return null;
  }

  private static int pageSize(Integer requested) {
    if (requested == null) {
      return DEFAULT_PAGE_SIZE;
    }
    if (requested < 1 || requested > MAX_PAGE_SIZE) {
      throw new TradeOrderProblem(
          HttpStatus.BAD_REQUEST,
          "VALIDATION_FAILED",
          "pageSize must be between 1 and " + MAX_PAGE_SIZE);
    }
    return requested;
  }

  private static Set<TradeOrderStatus> safeStatuses(Set<TradeOrderStatus> statuses) {
    return statuses == null ? Set.of() : Set.copyOf(statuses);
  }

  private static String filterHash(
      String scope, Set<TradeOrderStatus> statuses, UUID partnerId, UUID ownerId) {
    String states =
        safeStatuses(statuses).stream()
            .sorted(Comparator.comparing(Enum::name))
            .map(Enum::name)
            .reduce((left, right) -> left + "," + right)
            .orElse("");
    return OrderCursorCodec.filterHash(scope + "|" + states + "|" + partnerId + "|" + ownerId);
  }

  private static TradeOrderProblem notFound() {
    return new TradeOrderProblem(
        HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Trade Order was not found");
  }

  private static TradeOrderProblem forbidden(String message) {
    return new TradeOrderProblem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
  }

  public record PageView(
      List<TradeOrder> items, String nextCursor, boolean hasNext, int pageSize, boolean buyer) {
    public PageView {
      items = List.copyOf(items);
    }
  }

  public record DetailView(TradeOrder order, List<TimelineView> timeline, boolean buyer) {
    public DetailView {
      timeline = List.copyOf(timeline);
    }
  }

  public record TimelineView(
      UUID id,
      Instant occurredAt,
      String action,
      String previousState,
      TradeOrderStatus newState,
      String safeReason,
      String visibility) {

    private static TimelineView from(TradeOrderRepository.TimelineEntry entry) {
      return new TimelineView(
          entry.id(),
          entry.occurredAt(),
          entry.action(),
          entry.previousState(),
          entry.newState(),
          entry.safeReason(),
          entry.visibility());
    }
  }
}
