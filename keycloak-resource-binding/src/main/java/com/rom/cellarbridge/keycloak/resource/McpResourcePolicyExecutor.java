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
    ClientModel client = enabled ? client(context) : null;
    if (!enabled || !ResourceBinding.protects(client)) {
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
      case TOKEN_REQUEST -> {
        TokenRequestContext token = (TokenRequestContext) context;
        verify(
            client,
            OAuth2Constants.AUTHORIZATION_CODE,
            canonical,
            OAuth2Constants.AUTHORIZATION_CODE.equals(grant(token)) && exchange(token, canonical));
      }
      case TOKEN_REFRESH -> {
        TokenRefreshContext refresh = (TokenRefreshContext) context;
        verify(
            client,
            OAuth2Constants.REFRESH_TOKEN,
            canonical,
            OAuth2Constants.REFRESH_TOKEN.equals(grant(refresh))
                && ResourceBinding.oneExact(
                    refresh.getParams().get(OAuth2Constants.RESOURCE), canonical));
      }
    }
  }

  private static ClientModel client(ClientPolicyContext context) {
    return switch (context) {
      case AuthorizationRequestContext value ->
          value.getAuthenticationSession() == null ? null : value.getClient();
      case TokenRequestContext value ->
          value.getParseResult() == null || value.getClientSession() == null
              ? null
              : value.getClientSession().getClient();
      case TokenRefreshContext value -> value.getClient();
      default -> null;
    };
  }

  private static void authorize(AuthorizationRequestContext context, String canonical)
      throws ClientPolicyException {
    if (!ResourceBinding.oneExact(
        context.getRequestParameters().get(OAuth2Constants.RESOURCE), canonical)) {
      reject();
    }
    context.getAuthenticationSession().setClientNote(ResourceBinding.SESSION_NOTE, canonical);
  }

  private void verify(ClientModel client, String grant, String canonical, boolean valid)
      throws ClientPolicyException {
    session.removeAttribute(ResourceBinding.REQUEST_VERIFIED);
    if (canonical == null || !valid) {
      reject();
    }
    session.setAttribute(
        ResourceBinding.REQUEST_VERIFIED,
        new ResourceBinding.Verification(client.getClientId(), grant, canonical));
  }

  private static String grant(TokenRequestContext context) {
    return context.getParams() == null
        ? null
        : context.getParams().getFirst(OAuth2Constants.GRANT_TYPE);
  }

  private static String grant(TokenRefreshContext context) {
    return context.getParams() == null
        ? null
        : context.getParams().getFirst(OAuth2Constants.GRANT_TYPE);
  }

  private static boolean exchange(TokenRequestContext context, String canonical) {
    var parsed = context.getParseResult();
    return context.getParams() != null
        && parsed != null
        && parsed.getCodeData() != null
        && parsed.getClientSession() != null
        && ResourceBinding.oneExact(context.getParams().get(OAuth2Constants.RESOURCE), canonical)
        && ResourceBinding.allExact(
            canonical,
            parsed.getCodeData().getResource(),
            parsed.getClientSession().getNote(ResourceBinding.SESSION_NOTE));
  }

  private static void reject() throws ClientPolicyException {
    throw new ClientPolicyException(OAuthErrorException.INVALID_TARGET, ResourceBinding.ERROR);
  }
}
