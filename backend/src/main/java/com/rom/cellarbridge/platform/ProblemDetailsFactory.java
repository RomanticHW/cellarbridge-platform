package com.rom.cellarbridge.platform;

import java.net.URI;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public final class ProblemDetailsFactory {

  public ProblemDetail create(
      HttpStatus status, String code, String title, String detail, boolean retryable) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(
        URI.create("https://cellarbridge.dev/problems/" + code.toLowerCase().replace('_', '-')));
    problem.setTitle(title);
    problem.setProperty("code", code);
    problem.setProperty("traceId", traceId());
    problem.setProperty("retryable", retryable);
    return problem;
  }

  private static String traceId() {
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    return correlationId == null ? UUID.randomUUID().toString() : correlationId;
  }
}
