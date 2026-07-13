package com.rom.cellarbridge.identityaccess.internal.security;

import com.rom.cellarbridge.platform.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public final class SecurityAuditLogger {

  private static final Logger LOGGER = LoggerFactory.getLogger(SecurityAuditLogger.class);
  private final SafeIdentifierHasher hasher;

  SecurityAuditLogger(SafeIdentifierHasher hasher) {
    this.hasher = hasher;
  }

  public void accessDenied(String reasonCode, String subject, String tenant) {
    write("access_denied", reasonCode, hasher.hash(subject), hasher.hash(tenant));
  }

  public void authenticationFailed(String errorCode) {
    write("authentication_failed", errorCode, "unavailable", "unavailable");
  }

  public void authorizationDenied(String errorCode, String subjectHash, String tenantHash) {
    write("authorization_denied", errorCode, subjectHash, tenantHash);
  }

  private void write(
      String securityEvent, String errorCode, String subjectHash, String tenantHash) {
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    LOGGER.warn(
        "securityEvent={} subjectHash={} tenantHash={} correlationId={} errorCode={}",
        securityEvent,
        subjectHash,
        tenantHash,
        correlationId == null ? "unavailable" : correlationId,
        errorCode);
  }
}
