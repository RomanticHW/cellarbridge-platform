package com.rom.cellarbridge.identityaccess;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record TenantContext(
    UUID userId,
    String displayName,
    TenantId tenantId,
    String tenantName,
    String tenantStatus,
    UUID partnerId,
    Set<String> roles,
    Set<String> roleCodes,
    Set<PermissionCode> permissions,
    String subjectHash,
    String tenantHash) {

  public TenantContext {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(tenantName, "tenantName");
    Objects.requireNonNull(tenantStatus, "tenantStatus");
    roles = Set.copyOf(roles);
    roleCodes = Set.copyOf(roleCodes);
    permissions = Set.copyOf(permissions);
    Objects.requireNonNull(subjectHash, "subjectHash");
    Objects.requireNonNull(tenantHash, "tenantHash");
  }

  public boolean hasPermission(PermissionCode permission) {
    return permissions.contains(permission);
  }

  public boolean hasRoleCode(String roleCode) {
    return roleCodes.contains(roleCode);
  }
}
