package com.rom.cellarbridge.identityaccess.internal.web;

import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class CurrentUserController {

  private final TenantContextHolder contextHolder;

  CurrentUserController(TenantContextHolder contextHolder) {
    this.contextHolder = contextHolder;
  }

  @GetMapping("/me")
  ResponseEntity<CurrentUserResponse> currentUser() {
    TenantContext context = contextHolder.requireCurrent();
    CurrentUserResponse response =
        new CurrentUserResponse(
            context.userId(),
            context.displayName(),
            new TenantResponse(
                context.tenantId().value(), context.tenantName(), context.tenantStatus()),
            context.roles().stream().sorted().toList(),
            context.permissions().stream().map(PermissionCode::value).sorted().toList());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
  }

  record CurrentUserResponse(
      UUID userId,
      String displayName,
      TenantResponse tenant,
      List<String> roles,
      List<String> permissions) {}

  record TenantResponse(UUID id, String name, String status) {}
}
