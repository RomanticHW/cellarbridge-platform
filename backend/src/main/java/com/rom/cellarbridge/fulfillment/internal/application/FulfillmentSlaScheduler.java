package com.rom.cellarbridge.fulfillment.internal.application;

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
    prefix = "cellarbridge.fulfillment.sla",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class FulfillmentSlaScheduler {
  private final FulfillmentSlaService service;
  private final SchedulerTelemetry telemetry;

  FulfillmentSlaScheduler(FulfillmentSlaService service, SchedulerTelemetry telemetry) {
    this.service = service;
    this.telemetry = telemetry;
  }

  @Scheduled(
      initialDelayString = "${cellarbridge.fulfillment.sla.initial-delay:PT30S}",
      fixedDelayString = "${cellarbridge.fulfillment.sla.fixed-delay:PT30S}")
  void scan() {
    telemetry.run("fulfillment-sla", () -> service.markOverdue(100));
  }
}
