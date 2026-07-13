package com.rom.cellarbridge.identityaccess.internal.security;

import com.rom.cellarbridge.platform.ProblemResponseWriter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ProblemResponseWriter responseWriter;
  private final SecurityAuditLogger auditLogger;

  ProblemAuthenticationEntryPoint(
      ProblemResponseWriter responseWriter, SecurityAuditLogger auditLogger) {
    this.responseWriter = responseWriter;
    this.auditLogger = auditLogger;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authenticationException)
      throws IOException, ServletException {
    boolean tokenPresented = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
    String code = tokenPresented ? "INVALID_ACCESS_TOKEN" : "AUTHENTICATION_REQUIRED";
    auditLogger.authenticationFailed(code);
    responseWriter.write(
        response,
        HttpStatus.UNAUTHORIZED,
        code,
        "Authentication required",
        "A valid access token is required.",
        false);
  }
}
