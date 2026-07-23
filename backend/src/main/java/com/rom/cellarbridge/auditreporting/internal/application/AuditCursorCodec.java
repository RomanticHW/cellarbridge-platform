package com.rom.cellarbridge.auditreporting.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class AuditCursorCodec {
  private final String secret;

  AuditCursorCodec(@Value("${cellarbridge.audit-reporting.cursor-secret}") String secret) {
    if (secret == null || secret.length() < 32) {
      throw new IllegalArgumentException("Audit cursor secret must contain at least 32 characters");
    }
    this.secret = secret;
  }

  String encode(TenantId tenantId, String queryFingerprint, Instant occurredAt, UUID id) {
    String body = tenantId.value() + "|" + queryFingerprint + "|" + occurredAt + "|" + id;
    String signed = body + "|" + ProjectionDefinition.sha256(secret + "|" + body);
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(signed.getBytes(StandardCharsets.UTF_8));
  }

  Position decode(TenantId tenantId, String queryFingerprint, String cursor) {
    if (cursor == null || cursor.isBlank()) return new Position(null, null);
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] values = decoded.split("\\|", -1);
      if (values.length != 5
          || !tenantId.value().toString().equals(values[0])
          || !queryFingerprint.equals(values[1])) {
        throw invalid();
      }
      String body = values[0] + "|" + values[1] + "|" + values[2] + "|" + values[3];
      if (!ProjectionDefinition.sha256(secret + "|" + body).equals(values[4])) throw invalid();
      return new Position(Instant.parse(values[2]), UUID.fromString(values[3]));
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Audit cursor is invalid, stale, or bound to another tenant or query");
  }

  record Position(Instant occurredAt, UUID id) {}
}
