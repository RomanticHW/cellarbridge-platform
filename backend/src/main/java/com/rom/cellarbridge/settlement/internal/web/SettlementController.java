package com.rom.cellarbridge.settlement.internal.web;

import com.rom.cellarbridge.settlement.PaymentMethod;
import com.rom.cellarbridge.settlement.ReceivableStatus;
import com.rom.cellarbridge.settlement.internal.application.SettlementProblem;
import com.rom.cellarbridge.settlement.internal.application.SettlementService;
import com.rom.cellarbridge.settlement.internal.application.SettlementService.CommandResult;
import com.rom.cellarbridge.settlement.internal.application.SettlementService.DetailView;
import com.rom.cellarbridge.settlement.internal.application.SettlementService.HistoryView;
import com.rom.cellarbridge.settlement.internal.application.SettlementService.MoneyView;
import com.rom.cellarbridge.settlement.internal.application.SettlementService.PageView;
import com.rom.cellarbridge.settlement.internal.application.SettlementService.PaymentView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/receivables")
final class SettlementController {
  private final SettlementService settlement;

  SettlementController(SettlementService settlement) {
    this.settlement = settlement;
  }

  @GetMapping
  PageView list(
      @RequestParam(required = false) Set<ReceivableStatus> status,
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String cursor) {
    return settlement.list(status, pageSize, cursor);
  }

  @GetMapping("/{receivableId}")
  ResponseEntity<ReceivableDetailResponse> get(@PathVariable UUID receivableId) {
    return response(settlement.get(receivableId), HttpStatus.OK, false);
  }

  @PostMapping("/{receivableId}/payments")
  ResponseEntity<ReceivableDetailResponse> recordPayment(
      @PathVariable UUID receivableId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody RecordPaymentRequest request) {
    requireKey(idempotencyKey);
    if (request == null || request.amount() == null) {
      throw new SettlementProblem("VALIDATION_FAILED", "Payment request is required");
    }
    CommandResult result =
        settlement.recordPayment(
            receivableId,
            expectedVersion(ifMatch),
            idempotencyKey,
            decimal(request.amount()),
            request.amount().currency(),
            request.occurredOn(),
            request.method(),
            request.externalReference(),
            request.note());
    return response(
        result.detail(), result.replayed() ? HttpStatus.OK : HttpStatus.CREATED, result.replayed());
  }

  @PostMapping("/{receivableId}/payments/{paymentId}/reversal")
  ResponseEntity<ReceivableDetailResponse> reversePayment(
      @PathVariable UUID receivableId,
      @PathVariable UUID paymentId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody ReversePaymentRequest request) {
    requireKey(idempotencyKey);
    if (request == null || request.amount() == null) {
      throw new SettlementProblem("VALIDATION_FAILED", "Reversal request is required");
    }
    CommandResult result =
        settlement.reversePayment(
            receivableId,
            paymentId,
            expectedVersion(ifMatch),
            idempotencyKey,
            decimal(request.amount()),
            request.amount().currency(),
            request.reason());
    return response(result.detail(), HttpStatus.OK, result.replayed());
  }

  private static ResponseEntity<ReceivableDetailResponse> response(
      DetailView detail, HttpStatus status, boolean replayed) {
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(status)
            .eTag("\"" + detail.summary().version() + "\"")
            .cacheControl(CacheControl.noStore());
    if (replayed) builder.header("Idempotency-Replayed", "true");
    return builder.body(ReceivableDetailResponse.from(detail));
  }

  private static long expectedVersion(String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank()) {
      throw new SettlementProblem("PRECONDITION_REQUIRED", "If-Match is required");
    }
    try {
      String value = ifMatch.trim();
      if (!value.startsWith("\"") || !value.endsWith("\"") || value.length() < 3) {
        throw new NumberFormatException();
      }
      long version = Long.parseLong(value.substring(1, value.length() - 1));
      if (version < 0) throw new NumberFormatException();
      return version;
    } catch (NumberFormatException exception) {
      throw new SettlementProblem("VALIDATION_FAILED", "If-Match must contain a quoted version");
    }
  }

  private static void requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw new SettlementProblem("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
    }
  }

  private static BigDecimal decimal(MoneyRequest money) {
    try {
      return new BigDecimal(money.amount());
    } catch (RuntimeException exception) {
      throw new SettlementProblem("VALIDATION_FAILED", "Money amount is invalid");
    }
  }

  record MoneyRequest(String amount, String currency) {}

  record RecordPaymentRequest(
      MoneyRequest amount,
      LocalDate occurredOn,
      PaymentMethod method,
      String externalReference,
      String note) {}

  record ReversePaymentRequest(MoneyRequest amount, String reason) {}

  record ReceivableDetailResponse(
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
      long version,
      List<PaymentView> payments,
      List<HistoryView> history,
      List<String> allowedActions,
      boolean commercialAmountVisible) {
    static ReceivableDetailResponse from(DetailView detail) {
      var summary = detail.summary();
      return new ReceivableDetailResponse(
          summary.id(),
          summary.number(),
          summary.orderId(),
          summary.orderNumber(),
          summary.partnerId(),
          summary.partnerNumber(),
          summary.partnerName(),
          summary.originalAmount(),
          summary.outstandingAmount(),
          summary.dueDate(),
          summary.status(),
          summary.version(),
          detail.payments(),
          detail.history(),
          detail.allowedActions(),
          detail.commercialAmountVisible());
    }
  }
}
