package com.rom.cellarbridge.settlement.internal.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@ConditionalOnProperty(
    prefix = "cellarbridge.settlement.overdue",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class SettlementOverdueScheduler {
  private final SettlementService settlement;

  SettlementOverdueScheduler(SettlementService settlement) {
    this.settlement = settlement;
  }

  @Scheduled(
      initialDelayString = "${cellarbridge.settlement.overdue.initial-delay:PT30S}",
      fixedDelayString = "${cellarbridge.settlement.overdue.fixed-delay:PT30S}")
  void scan() {
    settlement.markOverdue(100);
  }
}
