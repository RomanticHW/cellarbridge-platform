package com.rom.cellarbridge.auditreporting.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ProjectionEventSource {
  List<EventDelivery> all(TenantId tenantId);

  SourceState sourceState(TenantId tenantId, Set<String> eventTypes);

  record SourceState(Watermark watermark, long eventCount) {
    public SourceState {
      if (eventCount < 0 || (watermark == null) != (eventCount == 0)) {
        throw new IllegalArgumentException("Source watermark and count are inconsistent");
      }
    }

    public static SourceState empty() {
      return new SourceState(null, 0);
    }
  }

  record Watermark(Instant occurredAt, UUID eventId) {
    public Watermark {
      if (occurredAt == null || eventId == null) {
        throw new IllegalArgumentException("Watermark is incomplete");
      }
    }
  }
}
