package com.rom.cellarbridge.quotation.internal.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@ConditionalOnProperty(
    prefix = "cellarbridge.quotation.expiration",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class QuotationExpirationScheduler {

  private static final int BATCH_SIZE = 50;
  private static final Logger LOGGER = LoggerFactory.getLogger(QuotationExpirationScheduler.class);
  private final UUID owner = UUID.randomUUID();
  private final QuotationExpirationService expirationService;

  QuotationExpirationScheduler(QuotationExpirationService expirationService) {
    this.expirationService = expirationService;
  }

  @Scheduled(
      initialDelayString = "${cellarbridge.quotation.expiration.initial-delay:PT30S}",
      fixedDelayString = "${cellarbridge.quotation.expiration.fixed-delay:PT30S}")
  void expireDueQuotations() {
    expirationService
        .claim(owner, BATCH_SIZE)
        .forEach(
            workItem -> {
              try {
                expirationService.expire(workItem);
              } catch (RuntimeException exception) {
                LOGGER.error(
                    "quotationExpirationFailed workItemId={} errorType={}",
                    workItem.id(),
                    exception.getClass().getSimpleName());
              }
            });
  }
}
