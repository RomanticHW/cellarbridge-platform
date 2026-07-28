package com.rom.cellarbridge.platform.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cellarbridge.mcp.security")
public record McpSecurityProperties(
    String resource,
    String audience,
    String scope,
    List<String> allowedClients,
    List<String> allowedOrigins,
    List<String> allowedHosts,
    int maxRequestBytes,
    int rateCapacity,
    double rateRefillPerSecond,
    int maxConcurrency,
    int maxClientConcurrency,
    int restConnectionReserve,
    Duration requestTimeout,
    Duration statementTimeout) {
  public McpSecurityProperties {
    allowedClients = copy(allowedClients);
    allowedOrigins = copy(allowedOrigins);
    allowedHosts = copy(allowedHosts);
  }

  public boolean configured() {
    URI uri = uri(resource);
    return uri != null
        && "https".equalsIgnoreCase(uri.getScheme())
        && text(uri.getHost())
        && uri.getUserInfo() == null
        && "/mcp".equals(uri.getRawPath())
        && uri.getRawQuery() == null
        && uri.getFragment() == null
        && resource.equals(audience)
        && text(scope)
        && scope.matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+")
        && !allowedClients.isEmpty()
        && allowedClients.stream().allMatch(McpSecurityProperties::text)
        && allowedOrigins.stream().allMatch(McpSecurityProperties::text)
        && !allowedHosts.isEmpty()
        && allowedHosts.stream().allMatch(McpSecurityProperties::text)
        && maxRequestBytes > 0
        && maxRequestBytes <= 1_048_576
        && rateCapacity > 0
        && rateRefillPerSecond > 0
        && Double.isFinite(rateRefillPerSecond)
        && maxConcurrency > 0
        && maxClientConcurrency > 0
        && maxClientConcurrency <= maxConcurrency
        && restConnectionReserve > 0
        && bounded(requestTimeout)
        && bounded(statementTimeout);
  }

  public boolean configured(int databasePoolSize) {
    return configured()
        && databasePoolSize > restConnectionReserve
        && maxConcurrency <= databasePoolSize - restConnectionReserve;
  }

  public String metadataUri() {
    URI uri = uri(resource);
    if (uri == null) return "";
    return "%s://%s/.well-known/oauth-protected-resource/mcp"
        .formatted(uri.getScheme(), uri.getRawAuthority());
  }

  public String metadataResource() {
    return configured() ? resource : "https://invalid.invalid/mcp";
  }

  public String metadataScope() {
    return text(scope) ? scope : "mcp:unavailable";
  }

  public int requestTimeoutMillis() {
    return bounded(requestTimeout) ? Math.toIntExact(requestTimeout.toMillis()) : 5_000;
  }

  private static List<String> copy(List<String> values) {
    return values == null
        ? List.of()
        : values.stream().map(value -> value == null ? "" : value).toList();
  }

  private static URI uri(String value) {
    try {
      return text(value) ? URI.create(value) : null;
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static boolean text(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean bounded(Duration value) {
    if (value == null || value.isNegative() || value.isZero()) return false;
    try {
      long millis = value.toMillis();
      return millis >= 1 && millis <= Integer.MAX_VALUE;
    } catch (ArithmeticException exception) {
      return false;
    }
  }
}
