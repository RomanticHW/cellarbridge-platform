package com.rom.cellarbridge.identityaccess.internal.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record UserMapping(
    UUID mappingId,
    UUID userId,
    String issuer,
    String externalSubject,
    String username,
    String displayName,
    UserStatus status,
    Tenant tenant,
    List<RoleTemplate> roles) {

  public UserMapping {
    Objects.requireNonNull(mappingId, "mappingId");
    Objects.requireNonNull(userId, "userId");
    requireText(issuer, "issuer");
    requireText(externalSubject, "externalSubject");
    requireText(username, "username");
    requireText(displayName, "displayName");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(tenant, "tenant");
    roles = List.copyOf(roles);
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
