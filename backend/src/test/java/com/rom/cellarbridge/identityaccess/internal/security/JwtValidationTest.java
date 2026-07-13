package com.rom.cellarbridge.identityaccess.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtValidationTest {

  private static final String ISSUER = "http://localhost:8081/realms/cellarbridge";
  private static final String AUDIENCE = "cellarbridge-api";
  private static KeyPair trustedKey;
  private static KeyPair untrustedKey;

  @BeforeAll
  static void createKeys() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    trustedKey = generator.generateKeyPair();
    untrustedKey = generator.generateKeyPair();
  }

  @Test
  void acceptsAValidSignedToken() {
    Jwt jwt =
        decoder(trustedKey)
            .decode(token(trustedKey, ISSUER, AUDIENCE, Instant.now().plusSeconds(60)));

    assertThat(jwt.getSubject()).isEqualTo("11000000-0000-4000-8000-000000000001");
    assertThat(jwt.getClaimAsString("tenant_code")).isEqualTo("north-cellars");
  }

  @Test
  void rejectsExpiredToken() {
    assertRejected(token(trustedKey, ISSUER, AUDIENCE, Instant.now().minusSeconds(60)));
  }

  @Test
  void rejectsWrongIssuer() {
    assertRejected(
        token(trustedKey, "https://issuer.invalid/realm", AUDIENCE, Instant.now().plusSeconds(60)));
  }

  @Test
  void rejectsWrongAudience() {
    assertRejected(token(trustedKey, ISSUER, "another-api", Instant.now().plusSeconds(60)));
  }

  @Test
  void rejectsTokenSignedByAnUntrustedKey() {
    assertRejected(token(untrustedKey, ISSUER, AUDIENCE, Instant.now().plusSeconds(60)));
  }

  private static void assertRejected(String token) {
    assertThatThrownBy(() -> decoder(trustedKey).decode(token))
        .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
  }

  private static JwtDecoder decoder(KeyPair keyPair) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
    decoder.setJwtValidator(JwtConfiguration.validators(ISSUER, AUDIENCE));
    return decoder;
  }

  private static String token(KeyPair keyPair, String issuer, String audience, Instant expiresAt) {
    RSAKey key =
        new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
            .privateKey((RSAPrivateKey) keyPair.getPrivate())
            .keyID("test-key")
            .build();
    ImmutableJWKSet<SecurityContext> keySource = new ImmutableJWKSet<>(new JWKSet(key));
    NimbusJwtEncoder encoder = new NimbusJwtEncoder(keySource);
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject("11000000-0000-4000-8000-000000000001")
            .audience(List.of(audience))
            .issuedAt(now.minusSeconds(120))
            .notBefore(now.minusSeconds(120))
            .expiresAt(expiresAt)
            .claim("tenant_code", "north-cellars")
            .build();
    JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build();
    return encoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
  }
}
