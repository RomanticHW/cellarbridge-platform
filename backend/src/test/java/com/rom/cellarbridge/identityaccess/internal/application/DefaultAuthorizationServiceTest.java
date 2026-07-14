package com.rom.cellarbridge.identityaccess.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.identityaccess.internal.security.SecurityAuditLogger;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class DefaultAuthorizationServiceTest {

  private static final TenantId TENANT_A =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final TenantId TENANT_B =
      TenantId.of(UUID.fromString("20000000-0000-4000-8000-000000000001"));
  private final TenantContextHolder holder = new TenantContextHolder();
  private final SecurityAuditLogger auditLogger = mock(SecurityAuditLogger.class);
  private final DefaultAuthorizationService service =
      new DefaultAuthorizationService(holder, auditLogger);

  @Test
  void allowsOnlyPermissionAndTenantMatches() {
    try (TenantContextHolder.Scope ignored = holder.open(context())) {
      assertThat(service.isAllowed(PermissionCode.PARTNER_READ, TENANT_A)).isTrue();
      assertThat(service.isAllowed(PermissionCode.PARTNER_CREATE, TENANT_A)).isFalse();
      assertThat(service.isAllowed(PermissionCode.PARTNER_READ, TENANT_B)).isFalse();
    }
  }

  @Test
  void deniesAndAuditsMissingPermissionOrCrossTenantAccess() {
    try (TenantContextHolder.Scope ignored = holder.open(context())) {
      assertThatThrownBy(() -> service.require(PermissionCode.PARTNER_READ, TENANT_B))
          .isInstanceOf(AccessDeniedException.class)
          .hasMessage("Access denied");
    }
    verify(auditLogger).authorizationDenied("ACCESS_DENIED", "subject-one", "tenant-one");
  }

  private static TenantContext context() {
    return new TenantContext(
        UUID.fromString("11200000-0000-4000-8000-000000000001"),
        "North Sales",
        TENANT_A,
        "North Cellars",
        "ACTIVE",
        null,
        Set.of("Sales Representative"),
        Set.of("sales-representative"),
        Set.of(PermissionCode.PARTNER_READ),
        "subject-one",
        "tenant-one");
  }
}
