package com.rom.cellarbridge.quotation.internal.application;

import com.rom.cellarbridge.quotation.QuotationStatus;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.ExpirationWorkItem;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotationExpirationService {

  private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
  private static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-4000-8000-000000000006");

  private final QuotationRepository repository;
  private final Clock clock;

  QuotationExpirationService(QuotationRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional
  public List<ExpirationWorkItem> claim(UUID owner, int batchSize) {
    Instant now = clock.instant();
    return repository.claimExpired(now, owner, now.plus(LEASE_DURATION), batchSize);
  }

  @Transactional
  public void expire(ExpirationWorkItem workItem) {
    Instant now = clock.instant();
    QuotationAggregate before =
        repository.findForUpdate(workItem.tenantId(), workItem.quotationId()).orElse(null);
    if (before == null
        || !before.revision().id().equals(workItem.revisionId())
        || before.status() != QuotationStatus.SENT) {
      repository.completeExpiration(workItem.tenantId(), workItem, now, SYSTEM_ACTOR);
      return;
    }
    if (now.isBefore(before.revision().terms().expiresAt())) {
      return;
    }
    repository.saveExpiration(workItem.tenantId(), before, before.expire(now), SYSTEM_ACTOR);
  }
}
