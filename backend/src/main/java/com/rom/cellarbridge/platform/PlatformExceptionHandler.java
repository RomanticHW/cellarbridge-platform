package com.rom.cellarbridge.platform;

import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PlatformExceptionHandler {

  private static final URI INTERNAL_ERROR_TYPE =
      URI.create("https://cellarbridge.dev/problems/internal-error");

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpectedException(Exception exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");
    problem.setType(INTERNAL_ERROR_TYPE);
    problem.setTitle("Internal server error");
    problem.setProperty("errorCode", "INTERNAL_ERROR");
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    if (correlationId != null) {
      problem.setProperty("correlationId", correlationId);
    }
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
  }
}
