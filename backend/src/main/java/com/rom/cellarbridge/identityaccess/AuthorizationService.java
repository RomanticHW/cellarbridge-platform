package com.rom.cellarbridge.identityaccess;

public interface AuthorizationService {

  boolean isAllowed(PermissionCode permission, TenantId resourceTenantId);

  void require(PermissionCode permission, TenantId resourceTenantId);
}
