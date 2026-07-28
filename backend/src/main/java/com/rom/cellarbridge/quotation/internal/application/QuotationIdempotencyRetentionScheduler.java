package com.rom.cellarbridge.quotation.internal.application;

import com.rom.cellarbridge.platform.SchedulerTelemetry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@ConditionalOnProperty(
    prefix = "cellarbridge.quotation.idempotency-retention",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class QuotationIdempotencyRetentionScheduler {

  private final QuotationIdempotencyRetentionService retention;
  private final SchedulerTelemetry telemetry;

  QuotationIdempotencyRetentionScheduler(
      QuotationIdempotencyRetentionService retention, SchedulerTelemetry telemetry) {
    this.retention = retention;
    this.telemetry = telemetry;
  }

  @Scheduled(
      initialDelayString = "${cellarbridge.quotation.idempotency-retention.initial-delay:PT1H}",
      fixedDelayString = "${cellarbridge.quotation.idempotency-retention.fixed-delay:PT1H}")
  void purgeExpired() {
    telemetry.run("quotation-idempotency-retention", () -> retention.purgeExpired());
  }
}
