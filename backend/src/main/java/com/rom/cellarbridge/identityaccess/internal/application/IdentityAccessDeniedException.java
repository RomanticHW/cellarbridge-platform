package com.rom.cellarbridge.identityaccess.internal.application;

import org.springframework.security.access.AccessDeniedException;

public final class IdentityAccessDeniedException extends AccessDeniedException {

  public IdentityAccessDeniedException() {
    super("Access denied");
  }
}
