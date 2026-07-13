package com.rom.cellarbridge.quotation.internal.web;

import com.rom.cellarbridge.platform.ProblemDetailsFactory;
import com.rom.cellarbridge.quotation.internal.domain.QuotationProblem;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.rom.cellarbridge.quotation")
final class QuotationExceptionHandler {

  private final ProblemDetailsFactory problemDetailsFactory;

  QuotationExceptionHandler(ProblemDetailsFactory problemDetailsFactory) {
    this.problemDetailsFactory = problemDetailsFactory;
  }

  @ExceptionHandler(QuotationProblem.class)
  ResponseEntity<ProblemDetail> handleProblem(QuotationProblem exception) {
    ProblemDetail problem =
        problemDetailsFactory.create(
            exception.status(),
            exception.code(),
            title(exception.code()),
            exception.getMessage(),
            false);
    if (exception.currentVersion() != null) {
      problem.setProperty("currentVersion", exception.currentVersion());
      problem.setProperty("currentState", exception.currentState());
    }
    return ResponseEntity.status(exception.status()).body(problem);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
    ProblemDetail problem =
        problemDetailsFactory.create(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_FAILED",
            "Validation failed",
            "One or more request fields are invalid",
            false);
    problem.setProperty(
        "errors",
        exception.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    Map.of(
                        "field",
                        error.getField(),
                        "code",
                        "INVALID",
                        "message",
                        error.getDefaultMessage() == null ? "Invalid" : error.getDefaultMessage()))
            .toList());
    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler({
    HandlerMethodValidationException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ProblemDetail> handleBadRequest(Exception exception) {
    String code =
        exception instanceof HttpMessageNotReadableException
            ? "MALFORMED_REQUEST"
            : "VALIDATION_FAILED";
    ProblemDetail problem =
        problemDetailsFactory.create(
            HttpStatus.BAD_REQUEST,
            code,
            "Invalid quotation request",
            "The request could not be processed",
            false);
    return ResponseEntity.badRequest().body(problem);
  }

  private static String title(String code) {
    return switch (code) {
      case "RESOURCE_NOT_FOUND" -> "Quotation not found";
      case "RESOURCE_VERSION_CONFLICT" -> "Quotation changed";
      case "PRECONDITION_REQUIRED" -> "Precondition required";
      case "QUOTE_EXPIRED" -> "Quotation expired";
      case "QUOTE_HAS_NO_ELIGIBLE_ROUTE" -> "No eligible route";
      case "QUOTE_APPROVAL_REQUIRED" -> "Approval required";
      default -> "Quotation request rejected";
    };
  }
}
