package com.rom.cellarbridge.inventory.internal.web;

import com.rom.cellarbridge.inventory.internal.application.ReservationOperationException;
import com.rom.cellarbridge.platform.ProblemDetailsFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = InventoryReservationController.class)
final class InventoryReservationExceptionHandler {

  private final ProblemDetailsFactory problems;

  InventoryReservationExceptionHandler(ProblemDetailsFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(ReservationOperationException.class)
  ResponseEntity<ProblemDetail> handleProblem(ReservationOperationException exception) {
    HttpStatus status = status(exception.code());
    return ResponseEntity.status(status)
        .body(
            problems.create(
                status, exception.code(), title(exception.code()), exception.getMessage(), false));
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ProblemDetail> handleValidation(Exception exception) {
    return ResponseEntity.badRequest()
        .body(
            problems.create(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Invalid Reservation operation",
                "One or more Reservation operation values are invalid",
                false));
  }

  private static String title(String code) {
    return switch (code) {
      case "RESOURCE_NOT_FOUND" -> "Reservation not found";
      case "ACCESS_DENIED" -> "Reservation access denied";
      case "IDEMPOTENCY_KEY_REUSED" -> "Idempotency key conflict";
      default -> "Reservation operation rejected";
    };
  }

  private static HttpStatus status(String code) {
    return switch (code) {
      case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
      case "VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED" -> HttpStatus.BAD_REQUEST;
      default -> HttpStatus.CONFLICT;
    };
  }
}
