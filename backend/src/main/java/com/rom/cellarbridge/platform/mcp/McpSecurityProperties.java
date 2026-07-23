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
    int rateRefillPerSecond,
    int maxConcurrency,
    int maxClientConcurrency,
    int restConnectionReserve,
    Duration requestTimeout,
    Duration statementTimeout) {
  public McpSecurityProperties {
    scope = text(scope) ? scope : "mcp:read";
    allowedClients = copy(allowedClients);
    allowedOrigins = copy(allowedOrigins);
    allowedHosts = copy(allowedHosts);
    maxRequestBytes = maxRequestBytes > 0 ? maxRequestBytes : 65_536;
    rateCapacity = rateCapacity > 0 ? rateCapacity : 30;
    rateRefillPerSecond = rateRefillPerSecond > 0 ? rateRefillPerSecond : 5;
    maxConcurrency = maxConcurrency > 0 ? maxConcurrency : 4;
    maxClientConcurrency = maxClientConcurrency > 0 ? maxClientConcurrency : 2;
    restConnectionReserve = restConnectionReserve > 0 ? restConnectionReserve : 2;
    requestTimeout = durationOr(requestTimeout, Duration.ofSeconds(5));
    statementTimeout = durationOr(statementTimeout, Duration.ofSeconds(3));
  }

  public boolean configured() {
    URI uri = uri(resource);
    return uri != null
        && "https".equalsIgnoreCase(uri.getScheme())
        && text(uri.getHost())
        && uri.getUserInfo() == null
        && uri.getFragment() == null
        && resource.equals(audience)
        && scope.matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+")
        && !allowedClients.isEmpty()
        && !allowedHosts.isEmpty()
        && maxClientConcurrency <= maxConcurrency;
  }

  public boolean configured(int databasePoolSize) {
    return configured() && maxConcurrency <= databasePoolSize - restConnectionReserve;
  }

  public String metadataUri() {
    URI uri = uri(resource);
    if (uri == null) return "";
    String path = uri.getRawPath();
    String inserted =
        "/.well-known/oauth-protected-resource"
            + (path == null || path.isBlank() || "/".equals(path) ? "" : path);
    try {
      return new URI(uri.getScheme(), uri.getRawAuthority(), inserted, uri.getRawQuery(), null)
          .toString();
    } catch (java.net.URISyntaxException exception) {
      return "";
    }
  }

  private static List<String> copy(List<String> values) {
    return values == null
        ? List.of()
        : values.stream().filter(McpSecurityProperties::text).toList();
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

  private static Duration durationOr(Duration value, Duration fallback) {
    return value != null && !value.isNegative() && !value.isZero() ? value : fallback;
  }
}
