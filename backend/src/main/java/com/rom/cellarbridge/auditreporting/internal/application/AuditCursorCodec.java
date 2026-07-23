package com.rom.cellarbridge.auditreporting.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class AuditCursorCodec {
  private static final String VERSION = "v2";
  private static final String TEMPORAL_KIND = "TEMPORAL";
  private static final String WORK_ITEM_KIND = "WORK_ITEM";
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final byte[] secret;
  private final Duration ttl;
  private final Clock clock;

  AuditCursorCodec(
      @Value("${cellarbridge.audit-reporting.cursor-secret}") String secret,
      @Value("${cellarbridge.audit-reporting.cursor-ttl:PT15M}") Duration ttl,
      Clock clock) {
    if (secret == null || secret.length() < 32) {
      throw new IllegalArgumentException("Audit cursor secret must contain at least 32 characters");
    }
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("Audit cursor TTL must be positive");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.ttl = ttl;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  String encode(TenantId tenantId, String queryFingerprint, Instant occurredAt, UUID id) {
    return encode(tenantId, queryFingerprint, null, null, occurredAt, id);
  }

  String encode(
      TenantId tenantId,
      String queryFingerprint,
      Instant windowFrom,
      Instant windowTo,
      Instant occurredAt,
      UUID id) {
    return signed(
        TEMPORAL_KIND,
        tenantId,
        queryFingerprint,
        text(windowFrom),
        text(windowTo),
        occurredAt.toString(),
        id.toString());
  }

  Position decode(TenantId tenantId, String queryFingerprint, String cursor) {
    if (cursor == null || cursor.isBlank()) return new Position(null, null);
    try {
      String[] values = values(cursor, tenantId, queryFingerprint, TEMPORAL_KIND, 9);
      return new Position(
          Instant.parse(values[7]),
          UUID.fromString(values[8]),
          instant(values[5]),
          instant(values[6]));
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  String encodeWorkItem(
      TenantId tenantId, String queryFingerprint, Instant dueAt, String priority, UUID id) {
    return signed(WORK_ITEM_KIND, tenantId, queryFingerprint, text(dueAt), priority, id.toString());
  }

  WorkItemPosition decodeWorkItem(TenantId tenantId, String queryFingerprint, String cursor) {
    if (cursor == null || cursor.isBlank()) return new WorkItemPosition(null, null, null);
    try {
      String[] values = values(cursor, tenantId, queryFingerprint, WORK_ITEM_KIND, 8);
      Instant dueAt = "-".equals(values[5]) ? null : Instant.parse(values[5]);
      if (values[6].isBlank()) throw invalid();
      return new WorkItemPosition(dueAt, values[6], UUID.fromString(values[7]));
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private String[] values(
      String cursor, TenantId tenantId, String queryFingerprint, String kind, int length) {
    String[] values = verifiedBody(cursor).split("\\|", -1);
    if (values.length != length
        || !VERSION.equals(values[0])
        || !kind.equals(values[1])
        || !tenantId.value().toString().equals(values[2])
        || !queryFingerprint.equals(values[3])) {
      throw invalid();
    }
    requireCurrent(values[4]);
    return values;
  }

  private String signed(
      String kind, TenantId tenantId, String queryFingerprint, String... position) {
    String body =
        String.join(
            "|",
            VERSION,
            kind,
            tenantId.value().toString(),
            queryFingerprint,
            clock.instant().toString(),
            String.join("|", position));
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
    return ENCODER.encodeToString(bodyBytes) + "." + ENCODER.encodeToString(hmac(bodyBytes));
  }

  private String verifiedBody(String cursor) {
    String[] parts = cursor.split("\\.", -1);
    if (parts.length != 2) throw invalid();
    byte[] body = DECODER.decode(parts[0]);
    byte[] suppliedSignature = DECODER.decode(parts[1]);
    if (!MessageDigest.isEqual(hmac(body), suppliedSignature)) throw invalid();
    return new String(body, StandardCharsets.UTF_8);
  }

  private byte[] hmac(byte[] value) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
      return mac.doFinal(value);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
    }
  }

  private void requireCurrent(String issuedAtValue) {
    Instant issuedAt = Instant.parse(issuedAtValue);
    Instant now = clock.instant();
    if (issuedAt.isAfter(now) || now.isAfter(issuedAt.plus(ttl))) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Cursor is invalid, stale, or bound to another tenant, query, or authorization scope");
  }

  private static Instant instant(String value) {
    return "-".equals(value) ? null : Instant.parse(value);
  }

  private static String text(Instant value) {
    return value == null ? "-" : value.toString();
  }

  record Position(Instant occurredAt, UUID id, Instant windowFrom, Instant windowTo) {
    Position(Instant occurredAt, UUID id) {
      this(occurredAt, id, null, null);
    }
  }

  record WorkItemPosition(Instant dueAt, String priority, UUID id) {}
}
