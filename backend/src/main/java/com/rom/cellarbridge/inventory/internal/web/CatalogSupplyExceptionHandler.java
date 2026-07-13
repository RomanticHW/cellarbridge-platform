package com.rom.cellarbridge.inventory.internal.web;

import com.rom.cellarbridge.catalog.CatalogQueryException;
import com.rom.cellarbridge.platform.ProblemDetailsFactory;
import java.net.URI;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = CatalogSearchController.class)
final class CatalogSupplyExceptionHandler {

  private final ProblemDetailsFactory problemDetailsFactory;

  CatalogSupplyExceptionHandler(ProblemDetailsFactory problemDetailsFactory) {
    this.problemDetailsFactory = problemDetailsFactory;
  }

  @ExceptionHandler(CatalogQueryException.class)
  ResponseEntity<ProblemDetail> handleCatalogProblem(CatalogQueryException exception) {
    boolean notFound = exception.code() == CatalogQueryException.Code.NOT_FOUND;
    HttpStatus status = notFound ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
    String code = notFound ? "RESOURCE_NOT_FOUND" : "VALIDATION_FAILED";
    ProblemDetail problem =
        problemDetailsFactory.create(
            status,
            code,
            notFound ? "SKU not found" : "Catalog search rejected",
            exception.getMessage(),
            false);
    if (notFound) {
      problem.setInstance(URI.create("/api/v1/catalog/skus"));
    }
    return ResponseEntity.status(status).body(problem);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HandlerMethodValidationException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ProblemDetail> handleValidation(Exception exception) {
    ProblemDetail problem =
        problemDetailsFactory.create(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_FAILED",
            "Validation failed",
            "One or more catalog search values are invalid",
            false);
    problem.setProperty(
        "errors",
        java.util.List.of(
            Map.of("field", "query", "code", "INVALID", "message", "Review the supplied filters")));
    return ResponseEntity.badRequest().body(problem);
  }
}
