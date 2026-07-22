package com.rom.cellarbridge.auditreporting.internal.web;

import com.rom.cellarbridge.platform.ProblemDetailsFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AuditReportingController.class)
final class AuditReportingExceptionHandler {
  private final ProblemDetailsFactory problems;

  AuditReportingExceptionHandler(ProblemDetailsFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ProblemDetail> invalid(Exception exception) {
    return ResponseEntity.badRequest()
        .body(
            problems.create(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Invalid reporting request",
                "One or more reporting request values are invalid",
                false));
  }
}
