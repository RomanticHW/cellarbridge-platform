package com.rom.cellarbridge.identityaccess.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.identityaccess.internal.application.IdentityAccessService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class TenantContextFilterTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void establishesContextOnlyForTheRequestAndClearsItBeforeTheNextTask() throws Exception {
    IdentityAccessService identityAccessService = mock(IdentityAccessService.class);
    TenantContextHolder holder = new TenantContextHolder();
    TenantContext context = context();
    Jwt jwt = jwt();
    when(identityAccessService.resolve(jwt)).thenReturn(context);
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    TenantContextFilter filter = new TenantContextFilter(identityAccessService, holder);

    filter.doFilter(
        new MockHttpServletRequest(),
        new MockHttpServletResponse(),
        (request, response) -> assertThat(holder.requireCurrent()).isEqualTo(context));

    assertThat(holder.current()).isEmpty();

    SecurityContextHolder.clearContext();
    filter.doFilter(
        new MockHttpServletRequest(),
        new MockHttpServletResponse(),
        (request, response) -> assertThat(holder.current()).isEmpty());
  }

  private static Jwt jwt() {
    Instant now = Instant.now();
    return Jwt.withTokenValue("test-token")
        .header("alg", "RS256")
        .issuer("http://localhost:8081/realms/cellarbridge")
        .subject("11000000-0000-4000-8000-000000000001")
        .audience(List.of("cellarbridge-api"))
        .issuedAt(now.minusSeconds(30))
        .expiresAt(now.plusSeconds(300))
        .claim("tenant_code", "north-cellars")
        .build();
  }

  private static TenantContext context() {
    return new TenantContext(
        UUID.fromString("11200000-0000-4000-8000-000000000001"),
        "North Sales",
        TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001")),
        "North Cellars",
        "ACTIVE",
        Set.of("Sales Representative"),
        Set.of(PermissionCode.PARTNER_READ),
        "subject-one",
        "tenant-one");
  }
}
