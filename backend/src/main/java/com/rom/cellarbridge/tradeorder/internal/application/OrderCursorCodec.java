package com.rom.cellarbridge.tradeorder.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderRepository.CursorPosition;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrderProblem;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
final class OrderCursorCodec {

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private final byte[] secret;

  OrderCursorCodec(@Value("${cellarbridge.order.cursor-secret}") String secret) {
    if (secret == null || secret.length() < 32) {
      throw new IllegalArgumentException("Order cursor secret must contain at least 32 characters");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  String encode(TenantId tenantId, String filterHash, CursorPosition position) {
    String payload = tenantId + "|" + filterHash + "|" + position.createdAt() + "|" + position.id();
    String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    return encoded + "." + ENCODER.encodeToString(sign(encoded));
  }

  CursorPosition decode(TenantId tenantId, String filterHash, String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String[] pieces = cursor.split("\\.", -1);
      if (pieces.length != 2
          || !MessageDigest.isEqual(sign(pieces[0]), DECODER.decode(pieces[1]))) {
        throw invalidCursor();
      }
      String[] values =
          new String(DECODER.decode(pieces[0]), StandardCharsets.UTF_8).split("\\|", -1);
      if (values.length != 4
          || !values[0].equals(tenantId.toString())
          || !values[1].equals(filterHash)) {
        throw invalidCursor();
      }
      return new CursorPosition(Instant.parse(values[2]), UUID.fromString(values[3]));
    } catch (IllegalArgumentException exception) {
      throw invalidCursor();
    }
  }

  static String filterHash(String canonicalFilter) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonicalFilter.getBytes(StandardCharsets.UTF_8)))
          .substring(0, 20);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
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

  private static TradeOrderProblem invalidCursor() {
    return new TradeOrderProblem(
        HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Cursor is invalid or stale");
  }
}
