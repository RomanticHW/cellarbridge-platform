package com.rom.cellarbridge.tradeorder.internal.web;

import com.rom.cellarbridge.platform.ProblemDetailsFactory;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderProblem;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrderDomainException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.rom.cellarbridge.tradeorder")
final class TradeOrderExceptionHandler {

  private final ProblemDetailsFactory problemDetailsFactory;

  TradeOrderExceptionHandler(ProblemDetailsFactory problemDetailsFactory) {
    this.problemDetailsFactory = problemDetailsFactory;
  }

  @ExceptionHandler(TradeOrderProblem.class)
  ResponseEntity<ProblemDetail> handleProblem(TradeOrderProblem exception) {
    ProblemDetail problem =
        problemDetailsFactory.create(
            exception.status(),
            exception.code(),
            title(exception.code()),
            exception.getMessage(),
            false);
    return ResponseEntity.status(exception.status()).body(problem);
  }

  @ExceptionHandler(TradeOrderDomainException.class)
  ResponseEntity<ProblemDetail> handleDomainProblem(TradeOrderDomainException exception) {
    HttpStatus status = status(exception);
    ProblemDetail problem =
        problemDetailsFactory.create(
            status, exception.code(), title(exception.code()), exception.safeMessage(), false);
    return ResponseEntity.status(status).body(problem);
  }

  @ExceptionHandler({MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
  ResponseEntity<ProblemDetail> handleBadRequest(Exception exception) {
    ProblemDetail problem =
        problemDetailsFactory.create(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_FAILED",
            "Invalid order request",
            "The order request could not be processed",
            false);
    return ResponseEntity.badRequest().body(problem);
  }

  private static String title(String code) {
    return switch (code) {
      case "RESOURCE_NOT_FOUND" -> "Trade Order not found";
      case "ACCESS_DENIED" -> "Order access denied";
      default -> "Order request rejected";
    };
  }

  private static HttpStatus status(TradeOrderDomainException exception) {
    return switch (exception.kind()) {
      case VALIDATION -> HttpStatus.BAD_REQUEST;
      case BUSINESS_RULE -> HttpStatus.UNPROCESSABLE_ENTITY;
      case STATE_CONFLICT -> HttpStatus.CONFLICT;
      case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
    };
  }
}
