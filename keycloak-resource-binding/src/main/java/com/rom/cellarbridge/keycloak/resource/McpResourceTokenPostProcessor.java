package com.rom.cellarbridge.keycloak.resource;

import org.keycloak.OAuth2Constants;
import org.keycloak.OAuthErrorException;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.protocol.oidc.token.TokenInterceptorException;
import org.keycloak.protocol.oidc.token.TokenPostProcessor;
import org.keycloak.protocol.oidc.token.TokenPostProcessorContext;

public final class McpResourceTokenPostProcessor implements TokenPostProcessor {
  private final KeycloakSession session;
  private final boolean enabled;

  McpResourceTokenPostProcessor(KeycloakSession session, boolean enabled) {
    this.session = session;
    this.enabled = enabled;
  }

  @Override
  public void process(TokenPostProcessorContext context) {
    ClientModel client = context.clientSessionCtx().getClientSession().getClient();
    if (!enabled || !ResourceBinding.protects(client)) {
      return;
    }
    String canonical = ResourceBinding.canonical(session);
    String requested =
        context.clientSessionCtx().getAttribute(OAuth2Constants.RESOURCE, String.class);
    String grant = context.clientSessionCtx().getAttribute(Constants.GRANT_TYPE, String.class);
    if (!Boolean.TRUE.equals(session.getAttribute(ResourceBinding.REQUEST_VERIFIED, Boolean.class))
        || !ResourceBinding.allExact(canonical, requested)) {
      reject();
    }
    if (OAuth2Constants.AUTHORIZATION_CODE.equals(grant)) {
      if (context.code() == null
          || !ResourceBinding.allExact(
              canonical,
              context.code().getResource(),
              context
                  .clientSessionCtx()
                  .getClientSession()
                  .getNote(ResourceBinding.SESSION_NOTE))) {
        reject();
      }
    } else if (OAuth2Constants.REFRESH_TOKEN.equals(grant)) {
      Object bound =
          context.requestRefreshToken() == null
              ? null
              : context.requestRefreshToken().getOtherClaims().get(OAuth2Constants.RESOURCE);
      if (!(bound instanceof String value) || !canonical.equals(value)) {
        reject();
      }
    } else {
      return;
    }
    if (!ResourceBinding.contains(
            context.accessToken().getAudience(), ResourceBinding.RESOURCE_CLIENT_ID)
        || context.refreshToken() == null) {
      reject();
    }
    context.accessToken().audience(canonical);
    context.accessToken().getOtherClaims().put(OAuth2Constants.RESOURCE, canonical);
    context.refreshToken().getOtherClaims().put(OAuth2Constants.RESOURCE, canonical);
  }

  private static void reject() {
    throw new TokenInterceptorException(OAuthErrorException.INVALID_TARGET, ResourceBinding.ERROR);
  }
}
