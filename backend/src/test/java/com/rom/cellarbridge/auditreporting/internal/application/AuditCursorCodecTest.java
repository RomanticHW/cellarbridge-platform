package com.rom.cellarbridge.auditreporting.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.identityaccess.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditCursorCodecTest {
  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final TenantId OTHER_TENANT =
      TenantId.of(UUID.fromString("20000000-0000-4000-8000-000000000001"));
  private static final String SECRET = "audit-cursor-test-secret-with-32-characters";

  private final AuditCursorCodec codec = new AuditCursorCodec(SECRET);

  @Test
  void roundTripsOnlyForTheBoundTenantAndQueryFingerprint() {
    Instant occurredAt = Instant.parse("2026-07-22T12:00:00Z");
    UUID id = UUID.fromString("31200000-0000-4000-8000-000000000001");
    String cursor = codec.encode(TENANT, "query-one", occurredAt, id);

    assertThat(codec.decode(TENANT, "query-one", cursor))
        .isEqualTo(new AuditCursorCodec.Position(occurredAt, id));
    assertThatThrownBy(() -> codec.decode(TENANT, "query-two", cursor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("query");
    assertThatThrownBy(() -> codec.decode(OTHER_TENANT, "query-one", cursor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenant");
  }

  @Test
  void rejectsThePreviousTenantOnlyCursorShape() {
    String previousBody =
        TENANT.value()
            + "|"
            + Instant.parse("2026-07-22T12:00:00Z")
            + "|"
            + UUID.fromString("31200000-0000-4000-8000-000000000001");
    String previousCursor =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                (previousBody + "|" + ProjectionDefinition.sha256(SECRET + "|" + previousBody))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));

    assertThatThrownBy(() -> codec.decode(TENANT, "query-one", previousCursor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stale");
  }
}
