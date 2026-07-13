package com.rom.cellarbridge.catalog.internal.application;

import com.rom.cellarbridge.catalog.CatalogQueryException;
import com.rom.cellarbridge.catalog.internal.application.CatalogSearchRepository.CursorPosition;
import com.rom.cellarbridge.catalog.internal.application.CatalogSearchRepository.SearchSort;
import com.rom.cellarbridge.identityaccess.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class CatalogCursorCodec {

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private final byte[] secret;

  CatalogCursorCodec(@Value("${cellarbridge.catalog.cursor-secret}") String secret) {
    if (secret == null || secret.length() < 32) {
      throw new IllegalArgumentException(
          "Catalog cursor secret must contain at least 32 characters");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  String encode(TenantId tenantId, String filterHash, SearchSort sort, CursorPosition position) {
    String payload =
        String.join(
            ".",
            encodePart(tenantId.toString()),
            encodePart(filterHash),
            encodePart(sort.externalValue()),
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
      if (parts.length != 7) {
        throw invalidCursor();
      }
      String payload = String.join(".", java.util.Arrays.copyOf(parts, 6));
      if (!MessageDigest.isEqual(sign(payload), DECODER.decode(parts[6]))) {
        throw invalidCursor();
      }
      if (!decodePart(parts[0]).equals(tenantId.toString())
          || !decodePart(parts[1]).equals(filterHash)
          || !decodePart(parts[2]).equals(sort.externalValue())) {
        throw invalidCursor();
      }
      return new CursorPosition(
          decodePart(parts[3]), decodePart(parts[4]), UUID.fromString(decodePart(parts[5])));
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

  private static CatalogQueryException invalidCursor() {
    return CatalogQueryException.invalidRequest(
        "Cursor is invalid, stale, or bound to other filters");
  }
}
