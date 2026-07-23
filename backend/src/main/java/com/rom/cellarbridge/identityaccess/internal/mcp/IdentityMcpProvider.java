package com.rom.cellarbridge.identityaccess.internal.mcp;

import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.platform.mcp.McpCallSupport;
import com.rom.cellarbridge.platform.mcp.McpCapabilitySupport;
import com.rom.cellarbridge.platform.mcp.McpReadPayload;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public final class IdentityMcpProvider {

  private final TenantContextHolder contexts;
  private final McpCallSupport calls;

  public IdentityMcpProvider(TenantContextHolder contexts, McpCallSupport calls) {
    this.contexts = contexts;
    this.calls = calls;
  }

  @Bean
  SyncToolSpecification currentUserMcpTool() {
    return McpCapabilitySupport.readOnlyTool(
        calls,
        "SESSION",
        "cellarbridge_current_user",
        "Current CellarBridge user",
        "Returns the authenticated user's server-mapped tenant, partner, roles, and permissions.",
        Map.of(),
        List.of(),
        arguments -> McpReadPayload.transactional(currentUserData()));
  }

  @McpResource(
      name = "cellarbridge_session_me",
      title = "Current CellarBridge session",
      uri = "cellarbridge://session/me",
      description =
          "Authenticated session identity and effective permissions resolved by CellarBridge.",
      mimeType = "application/json")
  public String currentUserResource() {
    return calls.json(calls.read("SESSION", () -> McpReadPayload.transactional(currentUserData())));
  }

  private CurrentUserData currentUserData() {
    TenantContext context = contexts.requireCurrent();
    return new CurrentUserData(
        context.userId(),
        context.displayName(),
        context.partnerId(),
        new TenantData(context.tenantId().value(), context.tenantName(), context.tenantStatus()),
        context.roles().stream().sorted().toList(),
        context.permissions().stream().map(PermissionCode::value).sorted().toList());
  }

  public record CurrentUserData(
      UUID userId,
      String displayName,
      UUID partnerId,
      TenantData tenant,
      List<String> roles,
      List<String> permissions) {}

  public record TenantData(UUID id, String name, String status) {}
}
