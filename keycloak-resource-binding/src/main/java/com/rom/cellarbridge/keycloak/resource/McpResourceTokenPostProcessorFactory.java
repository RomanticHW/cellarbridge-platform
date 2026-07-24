package com.rom.cellarbridge.keycloak.resource;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.protocol.oidc.token.TokenPostProcessor;
import org.keycloak.protocol.oidc.token.TokenPostProcessorFactory;

public final class McpResourceTokenPostProcessorFactory implements TokenPostProcessorFactory {
  private boolean enabled;

  @Override
  public void init(Config.Scope config) {
    enabled = config.getBoolean("enabled", false);
  }

  @Override
  public TokenPostProcessor create(KeycloakSession session) {
    return new McpResourceTokenPostProcessor(session, enabled);
  }

  @Override
  public String getId() {
    return ResourceBinding.PROVIDER_ID;
  }
}
