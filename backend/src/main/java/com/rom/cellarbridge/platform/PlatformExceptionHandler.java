package com.rom.cellarbridge.platform;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PlatformExceptionHandler {

  private final ProblemDetailsFactory problemDetailsFactory;

  public PlatformExceptionHandler(ProblemDetailsFactory problemDetailsFactory) {
    this.problemDetailsFactory = problemDetailsFactory;
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception) {
    ProblemDetail problem =
        problemDetailsFactory.create(
            HttpStatus.FORBIDDEN,
            "ACCESS_DENIED",
            "Access denied",
            "The current user cannot access this resource.",
            false);
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpectedException(Exception exception) {
    ProblemDetail problem =
        problemDetailsFactory.create(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "Internal server error",
            "The request could not be completed.",
            true);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
  }
}
