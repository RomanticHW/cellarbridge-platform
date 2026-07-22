package com.rom.cellarbridge.exceptioncenter.internal.web;

import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionProblem;
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
@RestControllerAdvice(assignableTypes = ExceptionCaseController.class)
final class ExceptionCaseExceptionHandler {
  private final ProblemDetailsFactory problems;

  ExceptionCaseExceptionHandler(ProblemDetailsFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(ExceptionProblem.class)
  ResponseEntity<ProblemDetail> handle(ExceptionProblem exception) {
    HttpStatus status = status(exception.code());
    ProblemDetail body =
        problems.create(
            status, exception.code(), "Exception action rejected", exception.getMessage(), false);
    if (exception.currentVersion() != null) {
      body.setProperty("currentVersion", exception.currentVersion());
    }
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
                "Invalid exception request",
                "One or more exception request values are invalid",
                false));
  }

  private static HttpStatus status(String code) {
    return switch (code) {
      case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
      case "PRECONDITION_REQUIRED" -> HttpStatus.PRECONDITION_REQUIRED;
      case "RESOURCE_VERSION_CONFLICT" -> HttpStatus.PRECONDITION_FAILED;
      case "VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED" -> HttpStatus.BAD_REQUEST;
      default -> HttpStatus.CONFLICT;
    };
  }
}
