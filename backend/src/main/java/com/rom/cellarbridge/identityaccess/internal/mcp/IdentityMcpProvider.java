package com.rom.cellarbridge.identityaccess.internal.mcp;

import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.platform.mcp.McpCallSupport;
import com.rom.cellarbridge.platform.mcp.McpCapabilitySupport;
import com.rom.cellarbridge.platform.mcp.McpReadPayload;
import com.rom.cellarbridge.platform.mcp.McpSchemaSupport;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public final class IdentityMcpProvider {

  private static final Map<String, Object> CURRENT_USER_SCHEMA = currentUserSchema();

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
        CURRENT_USER_SCHEMA,
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

  private static Map<String, Object> currentUserSchema() {
    Map<String, Object> tenant =
        McpSchemaSupport.object(
            Map.of(
                "id", McpSchemaSupport.string("uuid"),
                "name", McpSchemaSupport.string(160),
                "status", McpSchemaSupport.enumeration(List.of("ACTIVE", "SUSPENDED"))));
    return McpSchemaSupport.object(
        Map.of(
            "userId", McpSchemaSupport.string("uuid"),
            "displayName", McpSchemaSupport.string(160),
            "partnerId", McpSchemaSupport.nullable(McpSchemaSupport.string("uuid")),
            "tenant", tenant,
            "roles", McpSchemaSupport.array(McpSchemaSupport.string(160), 1000),
            "permissions",
                McpSchemaSupport.array(
                    McpSchemaSupport.enumeration(
                        Arrays.stream(PermissionCode.values())
                            .map(PermissionCode::value)
                            .sorted()
                            .toList()),
                    1000)));
  }
}
