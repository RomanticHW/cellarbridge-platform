package com.rom.cellarbridge.identityaccess.internal.application;

import com.rom.cellarbridge.identityaccess.AuthorizationService;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.identityaccess.internal.security.SecurityAuditLogger;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class DefaultAuthorizationService implements AuthorizationService {

  private final TenantContextHolder contextHolder;
  private final SecurityAuditLogger auditLogger;

  DefaultAuthorizationService(TenantContextHolder contextHolder, SecurityAuditLogger auditLogger) {
    this.contextHolder = contextHolder;
    this.auditLogger = auditLogger;
  }

  @Override
  public boolean isAllowed(PermissionCode permission, TenantId resourceTenantId) {
    return contextHolder
        .current()
        .filter(context -> context.tenantId().equals(resourceTenantId))
        .filter(context -> context.hasPermission(permission))
        .isPresent();
  }

  @Override
  public void require(PermissionCode permission, TenantId resourceTenantId) {
    if (isAllowed(permission, resourceTenantId)) {
      return;
    }
    TenantContext context = contextHolder.requireCurrent();
    auditLogger.authorizationDenied("ACCESS_DENIED", context.subjectHash(), context.tenantHash());
    throw new AccessDeniedException("Access denied");
  }
}
