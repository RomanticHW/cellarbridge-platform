package com.rom.cellarbridge.identityaccess.internal.domain;

import com.rom.cellarbridge.identityaccess.PermissionCode;
import java.util.Objects;

public record Permission(PermissionCode code) {

  public Permission {
    Objects.requireNonNull(code, "code");
  }

  public static Permission fromStoredValue(String value) {
    return new Permission(PermissionCode.fromValue(value));
  }
}
