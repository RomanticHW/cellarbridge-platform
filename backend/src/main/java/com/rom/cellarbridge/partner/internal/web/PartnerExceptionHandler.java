package com.rom.cellarbridge.partner.internal.web;

import com.rom.cellarbridge.partner.internal.application.PartnerProblemException;
import com.rom.cellarbridge.platform.ProblemDetailsFactory;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.rom.cellarbridge.partner")
final class PartnerExceptionHandler {

  private final ProblemDetailsFactory problemDetailsFactory;

  PartnerExceptionHandler(ProblemDetailsFactory problemDetailsFactory) {
    this.problemDetailsFactory = problemDetailsFactory;
  }

  @ExceptionHandler(PartnerProblemException.class)
  ResponseEntity<ProblemDetail> handlePartnerProblem(PartnerProblemException exception) {
    ProblemDetail problem =
        problemDetailsFactory.create(
            exception.status(),
            exception.code(),
            title(exception.code()),
            exception.getMessage(),
            false);
    if (!exception.fields().isEmpty()) {
      List<Map<String, String>> errors =
          exception.fields().stream()
              .map(
                  field ->
                      Map.of(
                          "field", field,
                          "code", "REQUIRED",
                          "message", "Required before submission"))
              .toList();
      problem.setProperty("errors", errors);
    }
    if (exception.currentVersion() != null) {
      problem.setProperty("currentVersion", exception.currentVersion());
      problem.setProperty("currentState", exception.currentState().name());
    }
    if (exception.code().equals("RESOURCE_NOT_FOUND")) {
      problem.setInstance(URI.create("/api/v1/partners"));
    }
    return ResponseEntity.status(exception.status()).body(problem);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
    ProblemDetail problem =
        problemDetailsFactory.create(
            org.springframework.http.HttpStatus.BAD_REQUEST,
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
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ProblemDetail> handleRequestValidation(Exception exception) {
    ProblemDetail problem =
        problemDetailsFactory.create(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "VALIDATION_FAILED",
            "Validation failed",
            "One or more request values are invalid",
            false);
    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> handleMalformedRequest(HttpMessageNotReadableException exception) {
    ProblemDetail problem =
        problemDetailsFactory.create(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "MALFORMED_REQUEST",
            "Malformed request",
            "The request body is not valid JSON for this operation",
            false);
    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ProblemDetail> handleInvalidInput(IllegalArgumentException exception) {
    ProblemDetail problem =
        problemDetailsFactory.create(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "VALIDATION_FAILED",
            "Validation failed",
            "The request contains an invalid value",
            false);
    return ResponseEntity.badRequest().body(problem);
  }

  private static String title(String code) {
    return switch (code) {
      case "RESOURCE_NOT_FOUND" -> "Partner not found";
      case "RESOURCE_VERSION_CONFLICT" -> "Partner changed";
      case "PRECONDITION_REQUIRED" -> "Precondition required";
      case "PARTNER_PROFILE_INCOMPLETE" -> "Partner profile is incomplete";
      case "PARTNER_DUPLICATE_IDENTIFIER", "PARTNER_POTENTIAL_DUPLICATE" ->
          "Potential duplicate partner";
      case "PARTNER_REVIEWER_CONFLICT" -> "Independent review required";
      default -> "Partner request rejected";
    };
  }
}
