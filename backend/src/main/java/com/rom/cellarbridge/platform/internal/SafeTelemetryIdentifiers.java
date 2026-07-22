package com.rom.cellarbridge.platform.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

final class SafeTelemetryIdentifiers {

  private SafeTelemetryIdentifiers() {}

  static String tenantHash(UUID tenantId) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(tenantId.toString().getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(digest, 0, 12);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
