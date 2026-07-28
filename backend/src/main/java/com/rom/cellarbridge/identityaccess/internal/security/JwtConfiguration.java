package com.rom.cellarbridge.identityaccess.internal.security;

import com.rom.cellarbridge.platform.mcp.McpSecurityProperties;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SecurityProperties.class, McpSecurityProperties.class})
class JwtConfiguration {

  @Bean
  JwtDecoder jwtDecoder(SecurityProperties properties, McpSecurityProperties mcp) {
    int timeout = mcp.requestTimeoutMillis();
    var requests = new SimpleClientHttpRequestFactory();
    requests.setConnectTimeout(Duration.ofMillis(Math.min(timeout, 2_000)));
    requests.setReadTimeout(Duration.ofMillis(timeout));
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
            .restOperations(new RestTemplate(requests))
            .build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
    return decoder;
  }

  static OAuth2TokenValidator<Jwt> validators(String issuer, String audience) {
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer), new AudienceValidator(audience));
  }

  static OAuth2TokenValidator<Jwt> mcpValidators(McpSecurityProperties properties) {
    OAuth2Error invalidClient =
        new OAuth2Error("invalid_token", "The token client is not allowed", null);
    OAuth2Error invalidResource =
        new OAuth2Error("invalid_token", "The token resource is not allowed", null);
    OAuth2TokenValidator<Jwt> client =
        token -> {
          Object azpClaim = token.getClaim("azp");
          Object clientIdClaim = token.getClaim("client_id");
          String azp = azpClaim instanceof String value ? value : null;
          String clientId = clientIdClaim instanceof String value ? value : null;
          String resolved = azp == null ? clientId : azp;
          boolean consistent =
              (azpClaim == null || azp != null)
                  && (clientIdClaim == null || clientId != null)
                  && (azp == null || clientId == null || Objects.equals(azp, clientId));
          return consistent && resolved != null && properties.allowedClients().contains(resolved)
              ? OAuth2TokenValidatorResult.success()
              : OAuth2TokenValidatorResult.failure(invalidClient);
        };
    OAuth2TokenValidator<Jwt> resource =
        token -> {
          Object claim = token.getClaim("resource");
          return claim instanceof String value && properties.resource().equals(value)
              ? OAuth2TokenValidatorResult.success()
              : OAuth2TokenValidatorResult.failure(invalidResource);
        };
    return new DelegatingOAuth2TokenValidator<>(
        new AudienceValidator(properties.audience()), resource, client);
  }

  static JwtDecoder validated(JwtDecoder delegate, OAuth2TokenValidator<Jwt> validator) {
    return token -> validate(delegate.decode(token), validator);
  }

  static JwtDecoder validated(
      JwtDecoder delegate,
      OAuth2TokenValidator<Jwt> validator,
      Duration timeout,
      int maxConcurrency) {
    return validated(
        delegate,
        validator,
        timeout,
        maxConcurrency,
        task -> Thread.ofVirtual().name("mcp-jwt-decode").start(task));
  }

  static JwtDecoder validated(
      JwtDecoder delegate,
      OAuth2TokenValidator<Jwt> validator,
      Duration timeout,
      int maxConcurrency,
      Consumer<Runnable> starter) {
    Semaphore permits = new Semaphore(Math.max(1, maxConcurrency));
    return token -> {
      if (!permits.tryAcquire())
        throw new JwtException(
            "Token decoding capacity unavailable", new RejectedExecutionException());
      AtomicBoolean pending = new AtomicBoolean(true);
      FutureTask<Jwt> task = new FutureTask<>(() -> delegate.decode(token));
      try {
        starter.accept(
            () -> {
              if (!pending.compareAndSet(true, false)) return;
              try {
                task.run();
              } finally {
                permits.release();
              }
            });
      } catch (RuntimeException | Error failure) {
        if (pending.compareAndSet(true, false)) permits.release();
        throw failure;
      }
      try {
        return validate(task.get(timeout.toMillis(), TimeUnit.MILLISECONDS), validator);
      } catch (TimeoutException | InterruptedException failure) {
        task.cancel(true);
        if (pending.compareAndSet(true, false)) permits.release();
        boolean interrupted = failure instanceof InterruptedException;
        if (interrupted) Thread.currentThread().interrupt();
        throw new JwtException(
            interrupted ? "Token decoding interrupted" : "Token decoding timed out", failure);
      } catch (ExecutionException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof JwtException jwt) throw jwt;
        if (cause instanceof Error error) throw error;
        throw new JwtException("Token decoding failed", cause);
      }
    };
  }

  private static Jwt validate(Jwt jwt, OAuth2TokenValidator<Jwt> validator) {
    OAuth2TokenValidatorResult result = validator.validate(jwt);
    if (result.hasErrors())
      throw new JwtValidationException("Token validation failed", result.getErrors());
    return jwt;
  }
}
