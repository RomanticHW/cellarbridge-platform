package com.rom.cellarbridge.fulfillment.internal.application;

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

  FulfillmentSlaScheduler(FulfillmentSlaService service) {
    this.service = service;
  }

  @Scheduled(
      initialDelayString = "${cellarbridge.fulfillment.sla.initial-delay:PT30S}",
      fixedDelayString = "${cellarbridge.fulfillment.sla.fixed-delay:PT30S}")
  void scan() {
    service.markOverdue(100);
  }
}
