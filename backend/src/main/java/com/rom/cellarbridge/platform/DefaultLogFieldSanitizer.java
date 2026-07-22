package com.rom.cellarbridge.platform;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class DefaultLogFieldSanitizer implements LogFieldSanitizer {

  private static final String REDACTED = "[redacted]";
  private static final int MAX_LENGTH = 256;
  private static final Set<String> SENSITIVE_FIELDS =
      Set.of(
          "authorization",
          "password",
          "secret",
          "token",
          "access_token",
          "refresh_token",
          "body",
          "cost",
          "margin",
          "email",
          "phone",
          "personal");

  @Override
  public String sanitize(String fieldName, String value) {
    if (value == null) {
      return null;
    }
    String normalizedName = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
    if (SENSITIVE_FIELDS.stream().anyMatch(normalizedName::contains)) {
      return REDACTED;
    }
    String safeValue = value.replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ");
    return safeValue.length() <= MAX_LENGTH ? safeValue : safeValue.substring(0, MAX_LENGTH);
  }
}
