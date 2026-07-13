package com.rom.cellarbridge.identityaccess.internal.domain;

import com.rom.cellarbridge.identityaccess.TenantId;
import java.util.Objects;
import java.util.Set;

public record RoleTemplate(
    TenantId tenantId, String code, String displayName, Set<Permission> permissions) {

  public RoleTemplate {
    Objects.requireNonNull(tenantId, "tenantId");
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be blank");
    }
    permissions = Set.copyOf(permissions);
  }
}
