package com.rom.cellarbridge.identityaccess.internal.security;

import com.rom.cellarbridge.platform.mcp.McpSecurityProperties;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SecurityProperties.class, McpSecurityProperties.class})
class JwtConfiguration {

  @Bean
  JwtDecoder jwtDecoder(SecurityProperties properties) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
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
          return consistent && resolved != null && properties.allowedClientSet().contains(resolved)
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
    return token -> {
      Jwt jwt = delegate.decode(token);
      OAuth2TokenValidatorResult result = validator.validate(jwt);
      if (result.hasErrors()) {
        throw new JwtValidationException("Token validation failed", result.getErrors());
      }
      return jwt;
    };
  }
}
