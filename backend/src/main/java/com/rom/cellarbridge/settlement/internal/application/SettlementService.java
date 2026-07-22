package com.rom.cellarbridge.settlement.internal.application;

import com.rom.cellarbridge.fulfillment.FulfillmentCompletedV1;
import com.rom.cellarbridge.identityaccess.AuthorizationService;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.PendingEvent;
import com.rom.cellarbridge.platform.ReliableEventPublisher;
import com.rom.cellarbridge.settlement.PaymentMethod;
import com.rom.cellarbridge.settlement.PaymentRecordedV1;
import com.rom.cellarbridge.settlement.PaymentReversedV1;
import com.rom.cellarbridge.settlement.ReceivableCreatedV1;
import com.rom.cellarbridge.settlement.ReceivableOverdueV1;
import com.rom.cellarbridge.settlement.ReceivablePaidV1;
import com.rom.cellarbridge.settlement.ReceivableStatus;
import com.rom.cellarbridge.settlement.internal.application.SettlementStore.CursorPosition;
import com.rom.cellarbridge.settlement.internal.application.SettlementStore.HistoryRecord;
import com.rom.cellarbridge.settlement.internal.application.SettlementStore.OrderSnapshot;
import com.rom.cellarbridge.settlement.internal.application.SettlementStore.PaymentRecord;
import com.rom.cellarbridge.settlement.internal.application.SettlementStore.ReceivableRecord;
import com.rom.cellarbridge.settlement.internal.application.SettlementStore.ReversalRecord;
import com.rom.cellarbridge.settlement.internal.application.SettlementStore.TriggerPolicy;
import com.rom.cellarbridge.settlement.internal.domain.Receivable;
import com.rom.cellarbridge.settlement.internal.domain.SettlementMoney;
import com.rom.cellarbridge.tradeorder.TradeOrderCreatedV1;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {
  private static final int DEFAULT_PAGE_SIZE = 25;
  private static final int MAX_PAGE_SIZE = 100;
  private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{2,99}$");

  private final SettlementStore store;
  private final SettlementCursorCodec cursors;
  private final TenantContextHolder contexts;
  private final AuthorizationService authorization;
  private final ReliableEventPublisher events;
  private final Clock clock;

  SettlementService(
      SettlementStore store,
      SettlementCursorCodec cursors,
      TenantContextHolder contexts,
      AuthorizationService authorization,
      ReliableEventPublisher events,
      Clock clock) {
    this.store = store;
    this.cursors = cursors;
    this.contexts = contexts;
    this.authorization = authorization;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public SnapshotResult captureOrderSnapshot(
      EventDelivery delivery, TradeOrderCreatedV1.Payload payload) {
    TenantId tenantId = new TenantId(delivery.tenantId());
    OrderSnapshot existing = store.orderSnapshot(tenantId, payload.orderId()).orElse(null);
    TriggerPolicy policy =
        existing == null
            ? store.activePolicy()
            : new TriggerPolicy(
                existing.triggerPolicyCode(),
                existing.triggerPolicyVersion(),
                existing.triggerType());
    OrderSnapshot incoming =
        new OrderSnapshot(
            tenantId,
            payload.orderId(),
            payload.orderNumber(),
            payload.customer().partnerId(),
            payload.customer().partnerNumber(),
            payload.customer().displayName(),
            payload.customer().sourceVersion(),
            payload.currency(),
            money(payload.totalAmount()),
            payload.paymentTermDays(),
            policy.code(),
            policy.version(),
            policy.triggerType(),
            delivery.eventId(),
            payload.snapshotHash(),
            payload.acceptedAt(),
            clock.instant());
    if (existing != null) return matchingSnapshot(existing, incoming);
    if (store.insertOrderSnapshot(incoming)) {
      return new SnapshotResult(incoming.orderId(), snapshotEvidence(incoming), false);
    }
    existing = store.orderSnapshot(incoming.tenantId(), incoming.orderId()).orElse(null);
    if (existing == null) {
      throw EventHandlingException.finalFailure("SETTLEMENT_ORDER_SNAPSHOT_CONFLICT");
    }
    return matchingSnapshot(existing, incoming);
  }

  @Transactional
  public CreationResult createFromFulfillment(
      EventDelivery delivery, FulfillmentCompletedV1.Payload payload) {
    TenantId tenantId = new TenantId(delivery.tenantId());
    OrderSnapshot order =
        store
            .orderSnapshot(tenantId, payload.orderId())
            .orElseThrow(
                () -> EventHandlingException.retryable("SETTLEMENT_ORDER_SNAPSHOT_NOT_READY"));
    if (!"FULFILLMENT_COMPLETED".equals(order.triggerType())) {
      return new CreationResult(null, sha256("not-applicable|" + order.orderId()), true);
    }
    ReceivableRecord existing =
        store.findByTrigger(tenantId, order.triggerType(), payload.planId(), true).orElse(null);
    if (existing != null) {
      requireTriggerMatches(existing, order, payload);
      return new CreationResult(existing.id(), receivableEvidence(existing), true);
    }
    if (order.originalAmount().signum() == 0) {
      return new CreationResult(null, sha256("no-charge|" + order.orderId()), true);
    }

    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    UUID receivableId = UUID.randomUUID();
    LocalDate dueDate =
        LocalDate.ofInstant(payload.completedAt(), ZoneOffset.UTC)
            .plusDays(order.paymentTermDays());
    ReceivableRecord created =
        new ReceivableRecord(
            receivableId,
            tenantId,
            store.nextNumber(tenantId, now),
            order.orderId(),
            order.orderNumber(),
            order.partnerId(),
            order.partnerNumber(),
            order.partnerName(),
            order.partnerVersion(),
            order.originalAmount(),
            zero(),
            order.originalAmount(),
            order.currency(),
            dueDate,
            ReceivableStatus.OPEN,
            order.triggerPolicyCode(),
            order.triggerPolicyVersion(),
            order.triggerType(),
            payload.planId(),
            delivery.correlationId(),
            delivery.eventId(),
            now,
            null,
            now,
            null,
            0);
    if (!store.insertReceivable(created)) {
      existing =
          store.findByTrigger(tenantId, order.triggerType(), payload.planId(), true).orElse(null);
      if (existing == null) {
        throw EventHandlingException.finalFailure("RECEIVABLE_TRIGGER_CONFLICT");
      }
      requireTriggerMatches(existing, order, payload);
      return new CreationResult(existing.id(), receivableEvidence(existing), true);
    }
    store.insertHistory(
        new HistoryRecord(
            UUID.randomUUID(),
            tenantId,
            receivableId,
            "RECEIVABLE_CREATED",
            null,
            ReceivableStatus.OPEN,
            created.originalAmount(),
            created.currency(),
            null,
            null,
            delivery.eventId(),
            now));
    events.publish(
        pending(
            created,
            ReceivableCreatedV1.TYPE,
            now,
            delivery.eventId(),
            new ReceivableCreatedV1.Payload(
                created.id(),
                created.number(),
                created.orderId(),
                created.orderNumber(),
                created.partnerId(),
                created.partnerNumber(),
                amount(created.originalAmount()),
                created.currency(),
                created.dueDate(),
                now,
                created.triggerType(),
                created.triggerId(),
                created.triggerPolicyCode(),
                created.triggerPolicyVersion())));
    return new CreationResult(created.id(), receivableEvidence(created), false);
  }

  @Transactional(readOnly = true)
  public PageView list(Set<ReceivableStatus> statuses, Integer requestedPageSize, String cursor) {
    TenantContext context = requireRead();
    int pageSize = pageSize(requestedPageSize);
    Set<ReceivableStatus> safeStatuses = statuses == null ? Set.of() : Set.copyOf(statuses);
    String states =
        safeStatuses.stream()
            .sorted(Comparator.comparing(Enum::name))
            .map(Enum::name)
            .reduce((left, right) -> left + "," + right)
            .orElse("");
    String filterHash =
        SettlementCursorCodec.filterHash(
            context.partnerId() + "|" + states + "|" + visibleMoney(context));
    CursorPosition after = cursors.decode(context.tenantId(), filterHash, cursor);
    var page = store.list(context.tenantId(), context.partnerId(), safeStatuses, after, pageSize);
    boolean moneyVisible = visibleMoney(context);
    String next =
        page.nextPosition() == null
            ? null
            : cursors.encode(context.tenantId(), filterHash, page.nextPosition());
    return new PageView(
        page.items().stream().map(item -> summary(item, moneyVisible)).toList(),
        new PageInfo(next, page.hasNext(), pageSize));
  }

  @Transactional(readOnly = true)
  public DetailView get(UUID receivableId) {
    TenantContext context = requireRead();
    ReceivableRecord value = find(context, receivableId, false);
    return detail(context, value);
  }

  @Transactional
  public CommandResult recordPayment(
      UUID receivableId,
      long expectedVersion,
      String idempotencyKey,
      BigDecimal amount,
      String currency,
      LocalDate occurredOn,
      PaymentMethod method,
      String externalReference,
      String note) {
    TenantContext context = requireWrite(PermissionCode.SETTLEMENT_RECORD_PAYMENT);
    String keyHash = idempotencyHash(idempotencyKey);
    SettlementMoney payment = paymentMoney(amount, currency);
    String reference = paymentReference(externalReference);
    if (occurredOn == null) throw validation("occurredOn is required");
    if (method == null) throw validation("method is required");
    LocalDate paidOn = occurredOn;
    if (paidOn.isAfter(today())) throw validation("Payment date cannot be in the future");
    String safeNote = optionalText(note, 500, "note");
    String requestHash =
        sha256(
            receivableId
                + "|"
                + amount(payment.amount())
                + "|"
                + payment.currency()
                + "|"
                + paidOn
                + "|"
                + method
                + "|"
                + reference
                + "|"
                + Objects.toString(safeNote, ""));

    store.lockPaymentRequest(context.tenantId(), keyHash, reference);
    PaymentRecord replay = store.paymentByIdempotency(context.tenantId(), keyHash).orElse(null);
    if (replay != null) {
      requireHash(replay.requestHash(), requestHash, "IDEMPOTENCY_KEY_REUSED");
      return replayResult(context, receivableId, replay.receivableId());
    }
    replay = store.paymentByReference(context.tenantId(), reference).orElse(null);
    if (replay != null) {
      requireHash(replay.requestHash(), requestHash, "PAYMENT_REFERENCE_REUSED");
      return replayResult(context, receivableId, replay.receivableId());
    }

    ReceivableRecord current = find(context, receivableId, true);
    requireVersion(current, expectedVersion);
    if (!current.currency().equals(payment.currency())) {
      throw new SettlementProblem(
          "PAYMENT_CURRENCY_MISMATCH", "Payment currency must match the receivable");
    }
    Receivable before = domain(current);
    Receivable after;
    try {
      after = before.recordPayment(payment, today());
    } catch (IllegalStateException exception) {
      throw new SettlementProblem(
          "PAYMENT_AMOUNT_EXCEEDS_OUTSTANDING", "Payment amount exceeds the outstanding balance");
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    UUID paymentId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    store.insertPayment(
        new PaymentRecord(
            paymentId,
            context.tenantId(),
            receivableId,
            payment.amount(),
            payment.currency(),
            method,
            reference,
            paidOn,
            safeNote,
            context.userId(),
            keyHash,
            requestHash,
            correlationId,
            now));
    store.updateReceivable(context.tenantId(), current, after, context.userId(), now);
    store.insertHistory(
        history(
            current,
            "PAYMENT_RECORDED",
            current.status(),
            after.status(),
            payment.amount(),
            payment.currency(),
            context.userId(),
            safeNote,
            null,
            now));
    ReceivableRecord updated = find(context, receivableId, false);
    events.publish(
        pending(
            updated,
            PaymentRecordedV1.TYPE,
            now,
            paymentId,
            new PaymentRecordedV1.Payload(
                updated.id(),
                updated.number(),
                updated.orderId(),
                updated.orderNumber(),
                paymentId,
                amount(payment.amount()),
                payment.currency(),
                method.name(),
                reference,
                paidOn,
                now,
                context.userId(),
                amount(updated.outstandingAmount()),
                updated.status().name())));
    if (current.status() != ReceivableStatus.PAID && updated.status() == ReceivableStatus.PAID) {
      events.publish(
          pending(
              updated,
              ReceivablePaidV1.TYPE,
              now,
              paymentId,
              new ReceivablePaidV1.Payload(
                  updated.id(),
                  updated.number(),
                  updated.orderId(),
                  updated.orderNumber(),
                  amount(updated.originalAmount()),
                  updated.currency(),
                  now)));
    }
    return new CommandResult(detail(context, updated), false);
  }

  @Transactional
  public CommandResult reversePayment(
      UUID receivableId,
      UUID paymentId,
      long expectedVersion,
      String idempotencyKey,
      BigDecimal amount,
      String currency,
      String reason) {
    TenantContext context = requireWrite(PermissionCode.SETTLEMENT_REVERSE_PAYMENT);
    String keyHash = idempotencyHash(idempotencyKey);
    SettlementMoney reversal = paymentMoney(amount, currency);
    String safeReason = requiredText(reason, 5, 500, "reason");
    String requestHash =
        sha256(
            receivableId
                + "|"
                + paymentId
                + "|"
                + amount(reversal.amount())
                + "|"
                + reversal.currency()
                + "|"
                + safeReason);
    store.lockReversalRequest(context.tenantId(), keyHash);
    ReversalRecord replay = store.reversalByIdempotency(context.tenantId(), keyHash).orElse(null);
    if (replay != null) {
      requireHash(replay.requestHash(), requestHash, "IDEMPOTENCY_KEY_REUSED");
      return replayResult(context, receivableId, replay.receivableId());
    }

    ReceivableRecord current = find(context, receivableId, true);
    requireVersion(current, expectedVersion);
    PaymentRecord payment =
        store
            .payment(context.tenantId(), receivableId, paymentId, true)
            .orElseThrow(
                () -> new SettlementProblem("RESOURCE_NOT_FOUND", "Payment was not found"));
    if (!current.currency().equals(reversal.currency())
        || !payment.currency().equals(reversal.currency())) {
      throw new SettlementProblem(
          "PAYMENT_CURRENCY_MISMATCH", "Reversal currency must match the payment");
    }
    BigDecimal available =
        payment.amount().subtract(store.reversedAmount(context.tenantId(), paymentId));
    if (reversal.amount().compareTo(available) > 0) {
      throw new SettlementProblem(
          "PAYMENT_REVERSAL_EXCEEDS_AVAILABLE",
          "Reversal amount exceeds the unreversed payment amount");
    }
    Receivable after = domain(current).reversePayment(reversal, today());
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    UUID reversalId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    store.insertReversal(
        new ReversalRecord(
            reversalId,
            context.tenantId(),
            receivableId,
            paymentId,
            reversal.amount(),
            reversal.currency(),
            safeReason,
            context.userId(),
            keyHash,
            requestHash,
            correlationId,
            now));
    store.updateReceivable(context.tenantId(), current, after, context.userId(), now);
    store.insertHistory(
        history(
            current,
            "PAYMENT_REVERSED",
            current.status(),
            after.status(),
            reversal.amount(),
            reversal.currency(),
            context.userId(),
            safeReason,
            null,
            now));
    ReceivableRecord updated = find(context, receivableId, false);
    events.publish(
        pending(
            updated,
            PaymentReversedV1.TYPE,
            now,
            reversalId,
            new PaymentReversedV1.Payload(
                updated.id(),
                updated.number(),
                updated.orderId(),
                updated.orderNumber(),
                paymentId,
                reversalId,
                amount(reversal.amount()),
                reversal.currency(),
                safeReason,
                now,
                context.userId(),
                amount(updated.outstandingAmount()),
                updated.status().name())));
    return new CommandResult(detail(context, updated), false);
  }

  @Transactional
  public int markOverdue(int requestedLimit) {
    if (requestedLimit < 1 || requestedLimit > 500) {
      throw validation("Overdue batch size must be between 1 and 500");
    }
    LocalDate today = today();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    int changed = 0;
    for (ReceivableRecord current : store.lockOverdueCandidates(today, requestedLimit)) {
      Receivable after = domain(current).markOverdue(today);
      if (after.status() == current.status()) continue;
      UUID eventId = UUID.randomUUID();
      store.updateReceivable(current.tenantId(), current, after, null, now);
      store.insertHistory(
          history(
              current,
              "MARKED_OVERDUE",
              current.status(),
              after.status(),
              null,
              null,
              null,
              null,
              eventId,
              now));
      ReceivableRecord updated =
          store.find(current.tenantId(), current.id(), null, false).orElseThrow();
      events.publish(
          new PendingEvent(
              eventId,
              current.tenantId().value(),
              ReceivableOverdueV1.TYPE,
              1,
              now,
              "settlement",
              new PendingEvent.Subject("RECEIVABLE", updated.id(), updated.number()),
              updated.correlationId(),
              updated.causationId(),
              new ReceivableOverdueV1.Payload(
                  updated.id(),
                  updated.number(),
                  updated.orderId(),
                  updated.orderNumber(),
                  updated.dueDate(),
                  amount(updated.outstandingAmount()),
                  updated.currency(),
                  now),
              Map.of()));
      changed++;
    }
    return changed;
  }

  private SnapshotResult matchingSnapshot(OrderSnapshot existing, OrderSnapshot incoming) {
    if (!snapshotEvidence(existing).equals(snapshotEvidence(incoming))) {
      throw EventHandlingException.finalFailure("SETTLEMENT_ORDER_SNAPSHOT_CONFLICT");
    }
    return new SnapshotResult(existing.orderId(), snapshotEvidence(existing), true);
  }

  private static void requireTriggerMatches(
      ReceivableRecord existing, OrderSnapshot order, FulfillmentCompletedV1.Payload payload) {
    if (!existing.orderId().equals(order.orderId())
        || !existing.orderNumber().equals(order.orderNumber())
        || !existing.triggerId().equals(payload.planId())
        || !existing.triggerPolicyCode().equals(order.triggerPolicyCode())
        || existing.triggerPolicyVersion() != order.triggerPolicyVersion()
        || existing.originalAmount().compareTo(order.originalAmount()) != 0
        || !existing.currency().equals(order.currency())) {
      throw EventHandlingException.finalFailure("RECEIVABLE_TRIGGER_CONFLICT");
    }
  }

  private TenantContext requireRead() {
    TenantContext context = contexts.requireCurrent();
    authorization.require(PermissionCode.SETTLEMENT_READ, context.tenantId());
    return context;
  }

  private TenantContext requireWrite(PermissionCode permission) {
    TenantContext context = contexts.requireCurrent();
    authorization.require(permission, context.tenantId());
    if (context.partnerId() != null) {
      throw new SettlementProblem(
          "ACCESS_DENIED", "Buyer identities cannot record financial facts");
    }
    return context;
  }

  private ReceivableRecord find(TenantContext context, UUID receivableId, boolean lock) {
    return store
        .find(context.tenantId(), receivableId, context.partnerId(), lock)
        .orElseThrow(() -> new SettlementProblem("RESOURCE_NOT_FOUND", "Receivable was not found"));
  }

  private CommandResult replayResult(TenantContext context, UUID requested, UUID actual) {
    if (!requested.equals(actual)) {
      throw new SettlementProblem(
          "IDEMPOTENCY_KEY_REUSED", "Idempotency key is bound to another receivable");
    }
    return new CommandResult(detail(context, find(context, actual, false)), true);
  }

  private DetailView detail(TenantContext context, ReceivableRecord value) {
    boolean moneyVisible = visibleMoney(context);
    boolean buyer = context.partnerId() != null;
    List<PaymentRecord> payments = store.payments(context.tenantId(), value.id());
    List<ReversalRecord> reversals = store.reversals(context.tenantId(), value.id());
    List<PaymentView> paymentViews =
        buyer ? List.of() : paymentViews(payments, reversals, moneyVisible);
    List<HistoryView> history =
        store.history(context.tenantId(), value.id()).stream()
            .map(
                item ->
                    new HistoryView(
                        item.id(),
                        item.action(),
                        item.previousStatus(),
                        item.newStatus(),
                        moneyVisible && item.amount() != null
                            ? new MoneyView(amount(item.amount()), item.currency())
                            : null,
                        buyer ? null : item.actorId(),
                        buyer || !moneyVisible ? null : item.safeReason(),
                        item.occurredAt()))
            .toList();
    return new DetailView(
        summary(value, moneyVisible),
        paymentViews,
        history,
        allowedActions(context, value),
        moneyVisible);
  }

  private static List<PaymentView> paymentViews(
      List<PaymentRecord> payments, List<ReversalRecord> reversals, boolean visible) {
    List<PaymentView> result = new ArrayList<>();
    for (PaymentRecord payment : payments) {
      BigDecimal reversed =
          reversals.stream()
              .filter(item -> item.paymentId().equals(payment.id()))
              .map(ReversalRecord::amount)
              .reduce(zero(), BigDecimal::add);
      result.add(
          new PaymentView(
              payment.id(),
              "PAYMENT",
              visible ? new MoneyView(amount(payment.amount()), payment.currency()) : null,
              visible ? payment.externalReference() : "Restricted",
              null,
              null,
              payment.method(),
              payment.occurredOn(),
              payment.recordedAt(),
              visible ? payment.actorId() : null,
              visible
                  ? new MoneyView(amount(payment.amount().subtract(reversed)), payment.currency())
                  : null));
    }
    for (ReversalRecord reversal : reversals) {
      PaymentRecord payment =
          payments.stream()
              .filter(item -> item.id().equals(reversal.paymentId()))
              .findFirst()
              .orElseThrow();
      result.add(
          new PaymentView(
              reversal.id(),
              "REVERSAL",
              visible
                  ? new MoneyView(amount(reversal.amount().negate()), reversal.currency())
                  : null,
              visible ? payment.externalReference() : "Restricted",
              reversal.paymentId(),
              visible ? reversal.reason() : null,
              null,
              LocalDate.ofInstant(reversal.reversedAt(), ZoneOffset.UTC),
              reversal.reversedAt(),
              visible ? reversal.actorId() : null,
              null));
    }
    return result.stream()
        .sorted(Comparator.comparing(PaymentView::recordedAt).thenComparing(PaymentView::id))
        .toList();
  }

  private static SummaryView summary(ReceivableRecord value, boolean moneyVisible) {
    return new SummaryView(
        value.id(),
        value.number(),
        value.orderId(),
        value.orderNumber(),
        value.partnerId(),
        value.partnerNumber(),
        value.partnerName(),
        moneyVisible ? new MoneyView(amount(value.originalAmount()), value.currency()) : null,
        moneyVisible ? new MoneyView(amount(value.outstandingAmount()), value.currency()) : null,
        value.dueDate(),
        value.status(),
        value.version());
  }

  private static List<String> allowedActions(TenantContext context, ReceivableRecord value) {
    if (context.partnerId() != null) return List.of();
    List<String> actions = new ArrayList<>();
    if (context.hasPermission(PermissionCode.SETTLEMENT_RECORD_PAYMENT)
        && value.outstandingAmount().signum() > 0) {
      actions.add("RECORD_PAYMENT");
    }
    if (context.hasPermission(PermissionCode.SETTLEMENT_REVERSE_PAYMENT)
        && value.paidNetAmount().signum() > 0) {
      actions.add("REVERSE_PAYMENT");
    }
    return List.copyOf(actions);
  }

  private static boolean visibleMoney(TenantContext context) {
    return context.partnerId() != null
        || context.hasPermission(PermissionCode.SETTLEMENT_READ_COMMERCIAL_SENSITIVE);
  }

  private static Receivable domain(ReceivableRecord value) {
    return new Receivable(
        value.id(),
        value.tenantId().value(),
        value.number(),
        value.orderId(),
        value.orderNumber(),
        value.partnerId(),
        value.partnerNumber(),
        value.partnerName(),
        value.partnerVersion(),
        new SettlementMoney(value.originalAmount(), value.currency()),
        new SettlementMoney(value.paidNetAmount(), value.currency()),
        value.dueDate(),
        value.status(),
        value.version());
  }

  private static PendingEvent pending(
      ReceivableRecord receivable, String type, Instant at, UUID causationId, Object payload) {
    return new PendingEvent(
        UUID.randomUUID(),
        receivable.tenantId().value(),
        type,
        1,
        at,
        "settlement",
        new PendingEvent.Subject("RECEIVABLE", receivable.id(), receivable.number()),
        receivable.correlationId(),
        causationId,
        payload,
        Map.of());
  }

  private static HistoryRecord history(
      ReceivableRecord value,
      String action,
      ReceivableStatus previous,
      ReceivableStatus next,
      BigDecimal amount,
      String currency,
      UUID actor,
      String reason,
      UUID sourceEvent,
      Instant at) {
    return new HistoryRecord(
        UUID.randomUUID(),
        value.tenantId(),
        value.id(),
        action,
        previous,
        next,
        amount,
        currency,
        actor,
        reason,
        sourceEvent,
        at);
  }

  private static void requireVersion(ReceivableRecord current, long expected) {
    if (current.version() != expected) {
      throw new SettlementProblem(
          "RESOURCE_VERSION_CONFLICT",
          "Receivable changed; reload before retrying",
          current.version());
    }
  }

  private static int pageSize(Integer requested) {
    if (requested == null) return DEFAULT_PAGE_SIZE;
    if (requested < 1 || requested > MAX_PAGE_SIZE) {
      throw validation("pageSize must be between 1 and " + MAX_PAGE_SIZE);
    }
    return requested;
  }

  private static SettlementMoney paymentMoney(BigDecimal amount, String currency) {
    try {
      return SettlementMoney.positive(amount, currency);
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw validation("Payment amount and currency are invalid");
    }
  }

  private static String paymentReference(String value) {
    String reference = requiredText(value, 3, 100, "externalReference");
    if (!REFERENCE.matcher(reference).matches()) {
      throw validation("externalReference contains unsupported characters");
    }
    return reference;
  }

  private static String idempotencyHash(String value) {
    String key = requiredText(value, 20, 200, "Idempotency-Key");
    return sha256(key);
  }

  private static String requiredText(String value, int min, int max, String name) {
    if (value == null) throw validation(name + " is required");
    String trimmed = value.trim();
    if (trimmed.length() < min || trimmed.length() > max) {
      throw validation(name + " must contain " + min + " to " + max + " characters");
    }
    return trimmed;
  }

  private static String optionalText(String value, int max, String name) {
    if (value == null || value.isBlank()) return null;
    String trimmed = value.trim();
    if (trimmed.length() > max)
      throw validation(name + " must contain at most " + max + " characters");
    return trimmed;
  }

  private static void requireHash(String existing, String incoming, String code) {
    if (!existing.equals(incoming)) {
      throw new SettlementProblem(code, "The existing financial fact has different request data");
    }
  }

  private static BigDecimal money(String value) {
    try {
      return new BigDecimal(value).setScale(SettlementMoney.SCALE);
    } catch (RuntimeException exception) {
      throw EventHandlingException.finalFailure("SETTLEMENT_ORDER_EVENT_INVALID");
    }
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(SettlementMoney.SCALE);
  }

  private static LocalDate today(Clock clock) {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private LocalDate today() {
    return today(clock);
  }

  private static String amount(BigDecimal value) {
    return value.setScale(SettlementMoney.SCALE).toPlainString();
  }

  private static String snapshotEvidence(OrderSnapshot value) {
    return sha256(
        value.tenantId()
            + "|"
            + value.orderId()
            + "|"
            + value.orderNumber()
            + "|"
            + value.partnerId()
            + "|"
            + value.partnerNumber()
            + "|"
            + value.partnerName()
            + "|"
            + value.partnerVersion()
            + "|"
            + value.currency()
            + "|"
            + amount(value.originalAmount())
            + "|"
            + value.paymentTermDays()
            + "|"
            + value.triggerPolicyCode()
            + "|"
            + value.triggerPolicyVersion()
            + "|"
            + value.triggerType()
            + "|"
            + value.sourceSnapshotHash()
            + "|"
            + value.acceptedAt());
  }

  private static String receivableEvidence(ReceivableRecord value) {
    return sha256(
        value.id()
            + "|"
            + value.orderId()
            + "|"
            + value.triggerType()
            + "|"
            + value.triggerId()
            + "|"
            + amount(value.originalAmount())
            + "|"
            + value.currency()
            + "|"
            + value.dueDate());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static SettlementProblem validation(String message) {
    return new SettlementProblem("VALIDATION_FAILED", message);
  }

  public record SnapshotResult(UUID orderId, String evidenceHash, boolean replayed) {
    public SnapshotResult {
      if (!HASH.matcher(evidenceHash).matches())
        throw new IllegalArgumentException("invalid evidence hash");
    }
  }

  public record CreationResult(UUID receivableId, String evidenceHash, boolean replayed) {
    public CreationResult {
      if (!HASH.matcher(evidenceHash).matches())
        throw new IllegalArgumentException("invalid evidence hash");
    }
  }

  public record CommandResult(DetailView detail, boolean replayed) {}

  public record MoneyView(String amount, String currency) {}

  public record SummaryView(
      UUID id,
      String number,
      UUID orderId,
      String orderNumber,
      UUID partnerId,
      String partnerNumber,
      String partnerName,
      MoneyView originalAmount,
      MoneyView outstandingAmount,
      LocalDate dueDate,
      ReceivableStatus status,
      long version) {}

  public record PageInfo(String nextCursor, boolean hasNext, int pageSize) {}

  public record PageView(List<SummaryView> items, PageInfo pageInfo) {
    public PageView {
      items = List.copyOf(items);
    }
  }

  public record PaymentView(
      UUID id,
      String type,
      MoneyView amount,
      String externalReference,
      UUID reversedPaymentId,
      String reason,
      PaymentMethod method,
      LocalDate occurredOn,
      Instant recordedAt,
      UUID actorId,
      MoneyView reversibleAmount) {}

  public record HistoryView(
      UUID id,
      String action,
      ReceivableStatus previousStatus,
      ReceivableStatus newStatus,
      MoneyView amount,
      UUID actorId,
      String reason,
      Instant occurredAt) {}

  public record DetailView(
      SummaryView summary,
      List<PaymentView> payments,
      List<HistoryView> history,
      List<String> allowedActions,
      boolean commercialAmountVisible) {
    public DetailView {
      payments = List.copyOf(payments);
      history = List.copyOf(history);
      allowedActions = List.copyOf(allowedActions);
    }
  }
}
