package com.rom.cellarbridge.identityaccess.internal.security;

import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.internal.application.IdentityAccessDeniedException;
import com.rom.cellarbridge.platform.ProblemResponseWriter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

  private final ProblemResponseWriter responseWriter;
  private final SecurityAuditLogger auditLogger;
  private final TenantContextHolder contextHolder;

  ProblemAccessDeniedHandler(
      ProblemResponseWriter responseWriter,
      SecurityAuditLogger auditLogger,
      TenantContextHolder contextHolder) {
    this.responseWriter = responseWriter;
    this.auditLogger = auditLogger;
    this.contextHolder = contextHolder;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException, ServletException {
    if (!(accessDeniedException instanceof IdentityAccessDeniedException)) {
      TenantContext context = contextHolder.current().orElse(null);
      auditLogger.authorizationDenied(
          "ACCESS_DENIED",
          context == null ? "unavailable" : context.subjectHash(),
          context == null ? "unavailable" : context.tenantHash());
    }
    responseWriter.write(
        response,
        HttpStatus.FORBIDDEN,
        "ACCESS_DENIED",
        "Access denied",
        "The authenticated user is not allowed to perform this action.",
        false);
  }
}
