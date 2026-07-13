package com.rom.cellarbridge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DefaultLogFieldSanitizerTest {

  private final DefaultLogFieldSanitizer sanitizer = new DefaultLogFieldSanitizer();

  @Test
  void redactsCredentialFields() {
    assertThat(sanitizer.sanitize("Authorization", "Bearer value")).isEqualTo("[redacted]");
    assertThat(sanitizer.sanitize("refresh_token", "value")).isEqualTo("[redacted]");
  }

  @Test
  void removesControlCharactersAndCapsLength() {
    assertThat(sanitizer.sanitize("message", "first\nsecond\tvalue"))
        .isEqualTo("first second value");
    assertThat(sanitizer.sanitize("message", "x".repeat(300))).hasSize(256);
  }

  @Test
  void preservesNull() {
    assertThat(sanitizer.sanitize("message", null)).isNull();
  }
}
