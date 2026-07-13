package com.rom.cellarbridge.identityaccess.internal.application;

import com.rom.cellarbridge.identityaccess.GlobalRegistryAccess;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.identityaccess.internal.domain.UserMapping;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityAccessRepository {

  @GlobalRegistryAccess
  Optional<UserMapping> findByIssuerAndSubject(String issuer, String subject);

  Optional<UUID> findPublicUserId(TenantId tenantId, UUID userId);

  List<UUID> findPublicUserIds(TenantId tenantId, String usernamePrefix, int pageSize, int offset);
}
