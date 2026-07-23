package com.rom.cellarbridge.auditreporting.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.identityaccess.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class AuditCursorCodecTest {
  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final TenantId OTHER_TENANT =
      TenantId.of(UUID.fromString("20000000-0000-4000-8000-000000000001"));
  private static final String SECRET = "audit-cursor-test-secret-with-32-characters";
  private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
  private static final Duration TTL = Duration.ofMinutes(15);

  private final AuditCursorCodec codec =
      new AuditCursorCodec(SECRET, TTL, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void roundTripsOnlyForTheBoundTenantAndQueryFingerprint() {
    Instant occurredAt = Instant.parse("2026-07-22T12:00:00Z");
    Instant from = occurredAt.minus(Duration.ofDays(30));
    Instant to = occurredAt.plusSeconds(1);
    UUID id = UUID.fromString("31200000-0000-4000-8000-000000000001");
    String cursor = codec.encode(TENANT, "query-one", from, to, occurredAt, id);

    assertThat(codec.decode(TENANT, "query-one", cursor))
        .isEqualTo(new AuditCursorCodec.Position(occurredAt, id, from, to));
    assertInvalid(() -> codec.decode(TENANT, "query-two", cursor));
    assertInvalid(() -> codec.decode(OTHER_TENANT, "query-one", cursor));
  }

  @Test
  void roundTripsWorkItemPositionsIncludingNullDueDates() {
    UUID id = UUID.fromString("31200000-0000-4000-8000-000000000002");
    Instant dueAt = Instant.parse("2026-07-24T12:00:00Z");

    String dated = codec.encodeWorkItem(TENANT, "work-query", dueAt, "HIGH", id);
    assertThat(codec.decodeWorkItem(TENANT, "work-query", dated))
        .isEqualTo(new AuditCursorCodec.WorkItemPosition(dueAt, "HIGH", id));

    String undated = codec.encodeWorkItem(TENANT, "work-query", null, "CRITICAL", id);
    assertThat(codec.decodeWorkItem(TENANT, "work-query", undated))
        .isEqualTo(new AuditCursorCodec.WorkItemPosition(null, "CRITICAL", id));
    assertInvalid(() -> codec.decodeWorkItem(TENANT, "other-query", dated));
    assertInvalid(() -> codec.decodeWorkItem(OTHER_TENANT, "work-query", dated));
  }

  @Test
  void rejectsTamperingAndAValidCursorOfAnotherPositionKind() {
    Instant occurredAt = Instant.parse("2026-07-22T12:00:00Z");
    UUID id = UUID.fromString("31200000-0000-4000-8000-000000000003");
    String cursor = codec.encode(TENANT, "query-one", occurredAt, id);
    String work = codec.encodeWorkItem(TENANT, "work-query", occurredAt, "HIGH", id);

    assertInvalid(() -> codec.decode(TENANT, "query-one", tamper(cursor)));
    assertInvalid(() -> codec.decodeWorkItem(TENANT, "work-query", tamper(work)));
    assertInvalid(() -> codec.decodeWorkItem(TENANT, "query-one", cursor));
  }

  @Test
  void rejectsExpiredCursorsAndInvalidConfiguration() {
    Instant occurredAt = Instant.parse("2026-07-22T12:00:00Z");
    UUID id = UUID.fromString("31200000-0000-4000-8000-000000000004");
    String current = codec.encode(TENANT, "query-one", occurredAt, id);
    String currentWork = codec.encodeWorkItem(TENANT, "work-query", occurredAt, "HIGH", id);
    AuditCursorCodec afterExpiry =
        new AuditCursorCodec(
            SECRET, TTL, Clock.fixed(NOW.plus(TTL).plusSeconds(1), ZoneOffset.UTC));

    assertInvalid(() -> afterExpiry.decode(TENANT, "query-one", current));
    assertInvalid(() -> afterExpiry.decodeWorkItem(TENANT, "work-query", currentWork));
    assertThatThrownBy(
            () -> new AuditCursorCodec(SECRET, Duration.ZERO, Clock.fixed(NOW, ZoneOffset.UTC)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
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

    assertInvalid(() -> codec.decode(TENANT, "query-one", previousCursor));
  }

  private static void assertInvalid(ThrowingCallable operation) {
    assertThatThrownBy(operation).isInstanceOf(IllegalArgumentException.class);
  }

  private static String tamper(String cursor) {
    String[] parts = cursor.split("\\.", -1);
    byte[] body = java.util.Base64.getUrlDecoder().decode(parts[0]);
    body[body.length - 1] ^= 1;
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(body) + "." + parts[1];
  }
}
