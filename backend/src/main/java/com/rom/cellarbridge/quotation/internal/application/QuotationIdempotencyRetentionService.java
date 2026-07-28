package com.rom.cellarbridge.quotation.internal.application;

import org.springframework.stereotype.Service;

@Service
public class QuotationIdempotencyRetentionService {

  private static final int BATCH_SIZE = 500;
  private static final int MAX_BATCHES = 10;

  private final QuotationRepository repository;

  QuotationIdempotencyRetentionService(QuotationRepository repository) {
    this.repository = repository;
  }

  public int purgeExpired() {
    int total = 0;
    for (int batch = 0; batch < MAX_BATCHES; batch++) {
      int deleted = repository.deleteExpiredIdempotency(BATCH_SIZE);
      total += deleted;
      if (deleted < BATCH_SIZE) {
        break;
      }
    }
    return total;
  }
}
