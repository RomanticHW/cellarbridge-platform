package com.rom.cellarbridge.catalog.internal.application;

import com.rom.cellarbridge.catalog.CatalogQueryException;
import com.rom.cellarbridge.catalog.internal.application.CatalogSearchRepository.CursorPosition;
import com.rom.cellarbridge.catalog.internal.application.CatalogSearchRepository.SearchSort;
import com.rom.cellarbridge.identityaccess.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class CatalogCursorCodec {

  private static final String VERSION = "v2";
  private static final String CAPABILITY = "SUPPLY_SEARCH";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private final byte[] secret;
  private final Duration ttl;
  private final Clock clock;

  CatalogCursorCodec(
      @Value("${cellarbridge.catalog.cursor-secret}") String secret,
      @Value("${cellarbridge.catalog.cursor-ttl:PT15M}") Duration ttl,
      Clock clock) {
    if (secret == null || secret.length() < 32) {
      throw new IllegalArgumentException(
          "Catalog cursor secret must contain at least 32 characters");
    }
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("Catalog cursor TTL must be positive");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.ttl = ttl;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  String encode(TenantId tenantId, String filterHash, SearchSort sort, CursorPosition position) {
    String payload =
        String.join(
            ".",
            encodePart(VERSION),
            encodePart(CAPABILITY),
            encodePart(tenantId.toString()),
            encodePart(filterHash),
            encodePart(sort.externalValue()),
            encodePart(clock.instant().toString()),
            encodePart(position.sortValue()),
            encodePart(position.skuCode()),
            encodePart(position.skuId().toString()));
    return payload + "." + ENCODER.encodeToString(sign(payload));
  }

  CursorPosition decode(TenantId tenantId, String filterHash, SearchSort sort, String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String[] parts = cursor.split("\\.", -1);
      if (parts.length != 10) {
        throw invalidCursor();
      }
      String payload = String.join(".", java.util.Arrays.copyOf(parts, 9));
      if (!MessageDigest.isEqual(sign(payload), DECODER.decode(parts[9]))) {
        throw invalidCursor();
      }
      if (!decodePart(parts[0]).equals(VERSION)
          || !decodePart(parts[1]).equals(CAPABILITY)
          || !decodePart(parts[2]).equals(tenantId.toString())
          || !decodePart(parts[3]).equals(filterHash)
          || !decodePart(parts[4]).equals(sort.externalValue())) {
        throw invalidCursor();
      }
      requireCurrent(decodePart(parts[5]));
      return new CursorPosition(
          decodePart(parts[6]), decodePart(parts[7]), UUID.fromString(decodePart(parts[8])));
    } catch (IllegalArgumentException exception) {
      throw invalidCursor();
    }
  }

  static String filterHash(String canonicalFilter) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonicalFilter.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 20);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String encodePart(String value) {
    return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decodePart(String value) {
    return new String(DECODER.decode(value), StandardCharsets.UTF_8);
  }

  private byte[] sign(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
      throw new IllegalStateException("HMAC cursor signing is unavailable", exception);
    }
  }

  private void requireCurrent(String issuedAtValue) {
    Instant issuedAt = Instant.parse(issuedAtValue);
    Instant now = clock.instant();
    if (issuedAt.isAfter(now) || now.isAfter(issuedAt.plus(ttl))) {
      throw invalidCursor();
    }
  }

  private static CatalogQueryException invalidCursor() {
    return CatalogQueryException.invalidRequest(
        "Cursor is invalid, stale, or bound to other filters");
  }
}
