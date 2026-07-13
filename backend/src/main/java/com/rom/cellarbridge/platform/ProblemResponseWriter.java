package com.rom.cellarbridge.platform;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class ProblemResponseWriter {

  private final JsonMapper jsonMapper;
  private final ProblemDetailsFactory problemDetailsFactory;

  public ProblemResponseWriter(JsonMapper jsonMapper, ProblemDetailsFactory problemDetailsFactory) {
    this.jsonMapper = jsonMapper;
    this.problemDetailsFactory = problemDetailsFactory;
  }

  public void write(
      HttpServletResponse response,
      HttpStatus status,
      String code,
      String title,
      String detail,
      boolean retryable)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    jsonMapper.writeValue(
        response.getOutputStream(),
        problemDetailsFactory.create(status, code, title, detail, retryable));
  }
}
