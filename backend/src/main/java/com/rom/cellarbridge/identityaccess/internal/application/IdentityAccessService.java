package com.rom.cellarbridge.identityaccess.internal.application;

import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.internal.domain.Permission;
import com.rom.cellarbridge.identityaccess.internal.domain.RoleTemplate;
import com.rom.cellarbridge.identityaccess.internal.domain.TenantStatus;
import com.rom.cellarbridge.identityaccess.internal.domain.UserMapping;
import com.rom.cellarbridge.identityaccess.internal.domain.UserStatus;
import com.rom.cellarbridge.identityaccess.internal.security.SafeIdentifierHasher;
import com.rom.cellarbridge.identityaccess.internal.security.SecurityAuditLogger;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class IdentityAccessService {

  private static final String TENANT_CLAIM = "tenant_code";
  private final IdentityAccessRepository repository;
  private final SecurityAuditLogger auditLogger;
  private final SafeIdentifierHasher hasher;

  IdentityAccessService(
      IdentityAccessRepository repository,
      SecurityAuditLogger auditLogger,
      SafeIdentifierHasher hasher) {
    this.repository = repository;
    this.auditLogger = auditLogger;
    this.hasher = hasher;
  }

  public TenantContext resolve(Jwt jwt) {
    String subject = jwt.getSubject();
    String claimedTenant = jwt.getClaimAsString(TENANT_CLAIM);
    UserMapping mapping =
        repository
            .findByIssuerAndSubject(jwt.getIssuer().toString(), subject)
            .orElseThrow(() -> denied("IDENTITY_MAPPING_NOT_FOUND", subject, claimedTenant));

    if (claimedTenant == null || !mapping.tenant().code().equals(claimedTenant)) {
      throw denied("TENANT_MAPPING_CONFLICT", subject, claimedTenant);
    }
    if (mapping.tenant().status() != TenantStatus.ACTIVE) {
      throw denied("TENANT_SUSPENDED", subject, claimedTenant);
    }
    if (mapping.status() != UserStatus.ACTIVE) {
      throw denied("USER_SUSPENDED", subject, claimedTenant);
    }

    Set<String> roles =
        mapping.roles().stream()
            .map(RoleTemplate::displayName)
            .collect(Collectors.toUnmodifiableSet());
    Set<String> roleCodes =
        mapping.roles().stream().map(RoleTemplate::code).collect(Collectors.toUnmodifiableSet());
    Set<PermissionCode> permissions =
        mapping.roles().stream()
            .flatMap(role -> role.permissions().stream())
            .map(Permission::code)
            .collect(Collectors.toUnmodifiableSet());
    return new TenantContext(
        mapping.userId(),
        mapping.displayName(),
        mapping.tenant().id(),
        mapping.tenant().displayName(),
        mapping.tenant().status().name(),
        mapping.partnerId(),
        roles,
        roleCodes,
        permissions,
        hasher.hash(subject),
        hasher.hash(mapping.tenant().id().toString()));
  }

  private AccessDeniedException denied(String reasonCode, String subject, String tenant) {
    auditLogger.accessDenied(reasonCode, subject, tenant);
    return new IdentityAccessDeniedException();
  }
}
