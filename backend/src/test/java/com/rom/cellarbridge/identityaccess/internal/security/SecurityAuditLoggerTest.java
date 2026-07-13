package com.rom.cellarbridge.identityaccess.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rom.cellarbridge.platform.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class SecurityAuditLoggerTest {

  @Test
  void logsOnlySafeIdentitySummariesAndNeverTokenOrHeaderValues() {
    Logger logger = (Logger) LoggerFactory.getLogger(SecurityAuditLogger.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    SecurityAuditLogger auditLogger = new SecurityAuditLogger(new SafeIdentifierHasher());

    try (MDC.MDCCloseable ignored =
        MDC.putCloseable(CorrelationIdFilter.MDC_KEY, "request-safe-123")) {
      auditLogger.accessDenied(
          "TENANT_MAPPING_CONFLICT", "subject-sensitive-marker", "tenant-sensitive-marker");
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list).hasSize(1);
    String message = appender.list.getFirst().getFormattedMessage();
    assertThat(message)
        .contains("securityEvent=access_denied")
        .contains("correlationId=request-safe-123")
        .contains("errorCode=TENANT_MAPPING_CONFLICT")
        .doesNotContain("subject-sensitive-marker")
        .doesNotContain("tenant-sensitive-marker")
        .doesNotContain("Authorization")
        .doesNotContain("Bearer");
  }
}
