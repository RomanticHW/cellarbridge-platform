package com.rom.cellarbridge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class StructuredLogRedactionTest {

  @Test
  void capturedApplicationLogContainsOnlySanitizedFields() {
    DefaultLogFieldSanitizer sanitizer = new DefaultLogFieldSanitizer();
    Logger logger = (Logger) LoggerFactory.getLogger("cellarbridge.redaction.evidence");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.INFO);

    logger.info(
        "securityAudit authorization={} grossMargin={} outcome={}",
        sanitizer.sanitize("authorization", "Bearer forbidden-value"),
        sanitizer.sanitize("grossMargin", "41.5"),
        sanitizer.sanitize("outcome", "denied"));

    assertThat(appender.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getFormattedMessage()).contains("[redacted]", "outcome=denied");
              assertThat(event.getFormattedMessage())
                  .doesNotContain("forbidden-value", "41.5", "Bearer");
            });
    logger.detachAppender(appender);
  }
}
