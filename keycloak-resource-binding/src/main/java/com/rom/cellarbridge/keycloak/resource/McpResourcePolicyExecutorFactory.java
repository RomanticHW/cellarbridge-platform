package com.rom.cellarbridge.keycloak.resource;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProvider;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProviderFactory;

public final class McpResourcePolicyExecutorFactory implements ClientPolicyExecutorProviderFactory {
  private boolean enabled;

  @Override
  public void init(Config.Scope config) {
    enabled = config.getBoolean("enabled", false);
  }

  @Override
  public ClientPolicyExecutorProvider create(KeycloakSession session) {
    return new McpResourcePolicyExecutor(session, enabled);
  }

  @Override
  public String getId() {
    return ResourceBinding.PROVIDER_ID;
  }

  @Override
  public String getHelpText() {
    return "Requires the canonical MCP resource on authorization, token, and refresh requests.";
  }

  @Override
  public List<ProviderConfigProperty> getConfigProperties() {
    return List.of();
  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {}

  @Override
  public void close() {}
}
