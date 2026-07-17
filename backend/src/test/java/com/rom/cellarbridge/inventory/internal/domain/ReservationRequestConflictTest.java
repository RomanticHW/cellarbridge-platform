package com.rom.cellarbridge.inventory.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.identityaccess.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationRequestConflictTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-07-16T14:00:00Z");

  @Test
  void preservesCanonicalAndConflictingIdentityAsAnImmutableFact() {
    ReservationRequestConflict conflict = conflict("a".repeat(64), "b".repeat(64));

    assertThat(conflict.failureCode())
        .isEqualTo(ReservationRequestConflict.RESERVATION_REQUEST_CONFLICT);
    assertThat(conflict.existingRequestHash()).isNotEqualTo(conflict.conflictingRequestHash());
    assertThat(conflict.observedAt()).isEqualTo(NOW);
  }

  @Test
  void rejectsEqualOrMalformedHashes() {
    assertThatThrownBy(() -> conflict("a".repeat(64), "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must differ");
    assertThatThrownBy(() -> conflict("not-a-hash", "b".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existingRequestHash");
  }

  @Test
  void rejectsAnyFailureCodeAlias() {
    assertThatThrownBy(
            () ->
                new ReservationRequestConflict(
                    UUID.randomUUID(),
                    TENANT,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "a".repeat(64),
                    "b".repeat(64),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    NOW,
                    "REQUEST_CONFLICT"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stable failure code");
  }

  private static ReservationRequestConflict conflict(String existingHash, String incomingHash) {
    return new ReservationRequestConflict(
        UUID.randomUUID(),
        TENANT,
        UUID.randomUUID(),
        UUID.randomUUID(),
        existingHash,
        incomingHash,
        UUID.randomUUID(),
        UUID.randomUUID(),
        NOW,
        ReservationRequestConflict.RESERVATION_REQUEST_CONFLICT);
  }
}
