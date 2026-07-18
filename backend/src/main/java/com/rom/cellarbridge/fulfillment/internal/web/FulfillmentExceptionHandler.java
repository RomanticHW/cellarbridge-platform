package com.rom.cellarbridge.fulfillment.internal.web;

import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentProblem;
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
@RestControllerAdvice(assignableTypes = FulfillmentPlanController.class)
final class FulfillmentExceptionHandler {
  private final ProblemDetailsFactory problems;

  FulfillmentExceptionHandler(ProblemDetailsFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(FulfillmentProblem.class)
  ResponseEntity<ProblemDetail> handle(FulfillmentProblem exception) {
    HttpStatus status = status(exception.code());
    ProblemDetail body =
        problems.create(
            status, exception.code(), "Fulfillment action rejected", exception.getMessage(), false);
    if (exception.currentVersion() != null)
      body.setProperty("currentVersion", exception.currentVersion());
    return ResponseEntity.status(status).body(body);
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ProblemDetail> invalid(Exception exception) {
    return ResponseEntity.badRequest()
        .body(
            problems.create(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Invalid Fulfillment request",
                "One or more Fulfillment request values are invalid",
                false));
  }

  private static HttpStatus status(String code) {
    return switch (code) {
      case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
      case "PRECONDITION_REQUIRED" -> HttpStatus.PRECONDITION_REQUIRED;
      case "OPTIMISTIC_VERSION_CONFLICT" -> HttpStatus.PRECONDITION_FAILED;
      case "VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED" -> HttpStatus.BAD_REQUEST;
      default -> HttpStatus.CONFLICT;
    };
  }
}
