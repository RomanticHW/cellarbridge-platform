package com.rom.cellarbridge.identityaccess.internal.security;

import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

  private static final OAuth2Error ERROR =
      new OAuth2Error("invalid_token", "The required audience is missing", null);
  private final String audience;

  AudienceValidator(String audience) {
    this.audience = Objects.requireNonNull(audience, "audience");
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    List<String> audiences = token.getAudience();
    return audiences != null && audiences.size() == 1 && audience.equals(audiences.getFirst())
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(ERROR);
  }
}
