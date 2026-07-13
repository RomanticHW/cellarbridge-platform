package com.rom.cellarbridge.identityaccess;

import java.util.Objects;
import java.util.UUID;

public record TenantId(UUID value) {

  public TenantId {
    Objects.requireNonNull(value, "value");
  }

  public static TenantId of(UUID value) {
    return new TenantId(value);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
