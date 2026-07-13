package com.rom.cellarbridge.identityaccess.internal.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cellarbridge.security")
public record SecurityProperties(
    String issuer, String jwkSetUri, String audience, List<String> allowedOrigins) {

  public SecurityProperties {
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalArgumentException("Security issuer must be configured");
    }
    if (jwkSetUri == null || jwkSetUri.isBlank()) {
      throw new IllegalArgumentException("Security JWK set URI must be configured");
    }
    if (audience == null || audience.isBlank()) {
      throw new IllegalArgumentException("Security audience must be configured");
    }
    allowedOrigins = List.copyOf(allowedOrigins);
  }
}
