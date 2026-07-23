package com.rom.cellarbridge.keycloak.resource;

import org.keycloak.OAuth2Constants;
import org.keycloak.OAuthErrorException;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.representations.idm.ClientPolicyExecutorConfigurationRepresentation;
import org.keycloak.services.clientpolicy.ClientPolicyContext;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.services.clientpolicy.context.AuthorizationRequestContext;
import org.keycloak.services.clientpolicy.context.TokenRefreshContext;
import org.keycloak.services.clientpolicy.context.TokenRequestContext;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProvider;

public final class McpResourcePolicyExecutor
    implements ClientPolicyExecutorProvider<ClientPolicyExecutorConfigurationRepresentation> {
  private final KeycloakSession session;
  private final boolean enabled;

  McpResourcePolicyExecutor(KeycloakSession session, boolean enabled) {
    this.session = session;
    this.enabled = enabled;
  }

  @Override
  public String getProviderId() {
    return ResourceBinding.PROVIDER_ID;
  }

  @Override
  public void executeOnEvent(ClientPolicyContext context) throws ClientPolicyException {
    if (!enabled || !ResourceBinding.protects(client(context))) {
      return;
    }
    String canonical = ResourceBinding.canonical(session);
    switch (context.getEvent()) {
      case AUTHORIZATION_REQUEST -> {
        if (canonical == null) {
          reject();
        }
        authorize((AuthorizationRequestContext) context, canonical);
      }
      case TOKEN_REQUEST ->
          session.setAttribute(
              ResourceBinding.REQUEST_VERIFIED,
              canonical != null && exchange((TokenRequestContext) context, canonical));
      case TOKEN_REFRESH ->
          session.setAttribute(
              ResourceBinding.REQUEST_VERIFIED,
              canonical != null
                  && ResourceBinding.oneExact(
                      ((TokenRefreshContext) context).getParams().get(OAuth2Constants.RESOURCE),
                      canonical));
      default -> {
        // This policy only constrains authorization-code and refresh flows.
      }
    }
  }

  private static ClientModel client(ClientPolicyContext context) {
    return switch (context) {
      case AuthorizationRequestContext value -> value.getClient();
      case TokenRequestContext value -> value.getClientSession().getClient();
      case TokenRefreshContext value -> value.getClient();
      default -> null;
    };
  }

  private static void authorize(AuthorizationRequestContext context, String canonical)
      throws ClientPolicyException {
    require(context.getRequestParameters().get(OAuth2Constants.RESOURCE), canonical);
    context.getAuthenticationSession().setClientNote(ResourceBinding.SESSION_NOTE, canonical);
  }

  private static boolean exchange(TokenRequestContext context, String canonical) {
    return ResourceBinding.oneExact(context.getParams().get(OAuth2Constants.RESOURCE), canonical)
        && ResourceBinding.allExact(
            canonical,
            context.getParseResult().getCodeData().getResource(),
            context.getClientSession().getNote(ResourceBinding.SESSION_NOTE));
  }

  private static void require(java.util.List<String> values, String canonical)
      throws ClientPolicyException {
    if (!ResourceBinding.oneExact(values, canonical)) {
      reject();
    }
  }

  private static void reject() throws ClientPolicyException {
    throw new ClientPolicyException(OAuthErrorException.INVALID_TARGET, ResourceBinding.ERROR);
  }
}
