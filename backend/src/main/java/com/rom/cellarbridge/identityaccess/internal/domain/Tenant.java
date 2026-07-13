package com.rom.cellarbridge.identityaccess.internal.domain;

import com.rom.cellarbridge.identityaccess.TenantId;
import java.util.Objects;

public record Tenant(TenantId id, String code, String displayName, TenantStatus status) {

  public Tenant {
    Objects.requireNonNull(id, "id");
    requireText(code, "code");
    requireText(displayName, "displayName");
    Objects.requireNonNull(status, "status");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
