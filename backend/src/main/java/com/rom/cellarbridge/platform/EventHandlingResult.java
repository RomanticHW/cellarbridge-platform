package com.rom.cellarbridge.platform;

import java.util.regex.Pattern;

/** Optional safe evidence persisted with a successfully processed inbox record. */
public record EventHandlingResult(String resultReference, String resultHash) {

  private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

  public EventHandlingResult {
    if ((resultReference == null) != (resultHash == null)) {
      throw new IllegalArgumentException(
          "resultReference and resultHash must be supplied together");
    }
    if (resultReference != null && (resultReference.isBlank() || resultReference.length() > 160)) {
      throw new IllegalArgumentException("resultReference must contain 1 to 160 characters");
    }
    if (resultHash != null && !SHA_256.matcher(resultHash).matches()) {
      throw new IllegalArgumentException("resultHash must be a lowercase SHA-256 digest");
    }
  }

  public static EventHandlingResult processed() {
    return new EventHandlingResult(null, null);
  }

  public static EventHandlingResult processed(String resultReference, String resultHash) {
    return new EventHandlingResult(resultReference, resultHash);
  }
}
