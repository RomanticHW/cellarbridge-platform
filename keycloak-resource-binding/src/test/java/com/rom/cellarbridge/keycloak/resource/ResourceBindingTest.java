package com.rom.cellarbridge.keycloak.resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.MultivaluedHashMap;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.protocol.oidc.token.TokenInterceptorException;
import org.keycloak.protocol.oidc.token.TokenPostProcessorContext;
import org.keycloak.protocol.oidc.utils.OAuth2Code;
import org.keycloak.protocol.oidc.utils.OAuth2CodeParser.ParseResult;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.RefreshToken;
import org.keycloak.services.clientpolicy.ClientPolicyEvent;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.services.clientpolicy.context.AuthorizationRequestContext;
import org.keycloak.services.clientpolicy.context.TokenRequestContext;
import org.keycloak.sessions.AuthenticationSessionModel;

class ResourceBindingTest {
  private static final String RESOURCE = "https://mcp.cellarbridge.example/mcp";
  private static final String OTHER = "https://other.example/mcp";

  @Test
  void acceptsOnlyOneExactHttpsResource() {
    assertTrue(ResourceBinding.isCanonical(RESOURCE));
    assertTrue(ResourceBinding.oneExact(List.of(RESOURCE), RESOURCE));
    assertFalse(ResourceBinding.isCanonical("http://localhost:8080/mcp"));
    assertFalse(ResourceBinding.oneExact(List.of(), RESOURCE));
    assertFalse(ResourceBinding.oneExact(List.of(RESOURCE, RESOURCE), RESOURCE));
    assertFalse(ResourceBinding.oneExact(List.of("https://other.example/mcp"), RESOURCE));
  }

  @Test
  void requiresEveryStoredBindingAndSeedAudience() {
    assertTrue(ResourceBinding.allExact(RESOURCE, RESOURCE, RESOURCE));
    assertFalse(ResourceBinding.allExact(RESOURCE, RESOURCE, null));
    assertTrue(ResourceBinding.contains(new String[] {"cellarbridge-mcp"}, "cellarbridge-mcp"));
    assertFalse(ResourceBinding.contains(null, "cellarbridge-mcp"));
  }

  @Test
  void protectsEveryExplicitlyMarkedClientWithoutRelyingOnItsId() {
    assertTrue(ResourceBinding.protects("true"));
    assertFalse(ResourceBinding.protects("false"));
    assertFalse(ResourceBinding.protects((String) null));
  }

  @Test
  void disabledProvidersAreStrictNoOpsBeforeReadingContext() {
    assertDoesNotThrow(() -> new McpResourceTokenPostProcessor(null, false).process(null));
    assertDoesNotThrow(() -> new McpResourcePolicyExecutor(null, false).executeOnEvent(null));
  }

  @Test
  void authorizationRequiresOneExactResourceBeforeSavingTheBinding() throws Exception {
    Fixture fixture = fixture();
    AuthorizationRequestContext context = mock();
    AuthenticationSessionModel authentication = mock();
    var parameters = new MultivaluedHashMap<String, String>();
    when(context.getEvent()).thenReturn(ClientPolicyEvent.AUTHORIZATION_REQUEST);
    when(context.getAuthenticationSession()).thenReturn(authentication);
    when(context.getClient()).thenReturn(fixture.client());
    when(context.getRequestParameters()).thenReturn(parameters);
    var executor = new McpResourcePolicyExecutor(fixture.session(), true);

    for (List<String> invalid :
        List.of(List.<String>of(), List.of(RESOURCE, RESOURCE), List.of(OTHER))) {
      parameters.put(OAuth2Constants.RESOURCE, new java.util.ArrayList<>(invalid));
      assertThrows(ClientPolicyException.class, () -> executor.executeOnEvent(context));
    }

    parameters.putSingle(OAuth2Constants.RESOURCE, RESOURCE);
    executor.executeOnEvent(context);
    verify(authentication).setClientNote(ResourceBinding.SESSION_NOTE, RESOURCE);
  }

  @Test
  void tokenExchangeRejectsStoredMismatchAndMissingCodeWithAControlledError() throws Exception {
    Fixture fixture = fixture();
    TokenRequestContext context = mock();
    ParseResult parsed = mock();
    OAuth2Code code = mock();
    AuthenticatedClientSessionModel clientSession = mock();
    var parameters = new MultivaluedHashMap<String, String>();
    parameters.putSingle(OAuth2Constants.GRANT_TYPE, OAuth2Constants.AUTHORIZATION_CODE);
    parameters.putSingle(OAuth2Constants.RESOURCE, RESOURCE);
    when(context.getEvent()).thenReturn(ClientPolicyEvent.TOKEN_REQUEST);
    when(context.getParams()).thenReturn(parameters);
    when(context.getParseResult()).thenReturn(parsed);
    when(context.getClientSession()).thenReturn(clientSession);
    when(parsed.getClientSession()).thenReturn(clientSession);
    when(parsed.getCodeData()).thenReturn(code);
    when(clientSession.getClient()).thenReturn(fixture.client());
    when(clientSession.getNote(ResourceBinding.SESSION_NOTE)).thenReturn(RESOURCE);
    when(code.getResource()).thenReturn(OTHER);
    var executor = new McpResourcePolicyExecutor(fixture.session(), true);

    assertThrows(ClientPolicyException.class, () -> executor.executeOnEvent(context));
    when(parsed.getCodeData()).thenReturn(null);
    assertThrows(ClientPolicyException.class, () -> executor.executeOnEvent(context));

    when(parsed.getCodeData()).thenReturn(code);
    when(code.getResource()).thenReturn(RESOURCE);
    executor.executeOnEvent(context);
    verify(fixture.session())
        .setAttribute(
            eq(ResourceBinding.REQUEST_VERIFIED), any(ResourceBinding.Verification.class));
  }

  @Test
  void tokenClaimsUseTheCanonicalResourceAndVerificationCannotBeReused() {
    Fixture fixture = fixture();
    TokenCall call = tokenCall(fixture, OAuth2Constants.AUTHORIZATION_CODE);
    ResourceBinding.Verification proof =
        new ResourceBinding.Verification(
            fixture.client().getClientId(), OAuth2Constants.AUTHORIZATION_CODE, RESOURCE);
    when(fixture.session().removeAttribute(ResourceBinding.REQUEST_VERIFIED))
        .thenReturn(proof)
        .thenReturn(null);
    var processor = new McpResourceTokenPostProcessor(fixture.session(), true);

    processor.process(call.context());
    assertArrayEquals(new String[] {RESOURCE}, call.access().getAudience());
    assertEquals(RESOURCE, call.access().getOtherClaims().get(OAuth2Constants.RESOURCE));
    assertEquals(RESOURCE, call.refreshClaims().get(OAuth2Constants.RESOURCE));

    call.access().audience(ResourceBinding.RESOURCE_CLIENT_ID);
    assertThrows(TokenInterceptorException.class, () -> processor.process(call.context()));
  }

  @Test
  void changedRefreshBindingAndUnknownGrantFailClosed() {
    Fixture fixture = fixture();
    TokenCall call = tokenCall(fixture, OAuth2Constants.REFRESH_TOKEN);
    RefreshToken requestRefresh = mock();
    when(requestRefresh.getOtherClaims())
        .thenReturn(new HashMap<>(java.util.Map.of(OAuth2Constants.RESOURCE, OTHER)));
    TokenPostProcessorContext refreshContext =
        new TokenPostProcessorContext(
            null,
            requestRefresh,
            call.context().refreshToken(),
            call.access(),
            call.clientContext());
    when(fixture.session().removeAttribute(ResourceBinding.REQUEST_VERIFIED))
        .thenReturn(
            new ResourceBinding.Verification(
                "cellarbridge-mcp-host", OAuth2Constants.REFRESH_TOKEN, RESOURCE),
            new ResourceBinding.Verification(
                "cellarbridge-mcp-host", "client_credentials", RESOURCE));
    var processor = new McpResourceTokenPostProcessor(fixture.session(), true);

    assertThrows(TokenInterceptorException.class, () -> processor.process(refreshContext));
    when(call.clientContext().getAttribute(org.keycloak.models.Constants.GRANT_TYPE, String.class))
        .thenReturn("client_credentials");
    assertThrows(TokenInterceptorException.class, () -> processor.process(refreshContext));
  }

  private static Fixture fixture() {
    KeycloakSession session = mock(KeycloakSession.class, RETURNS_DEEP_STUBS);
    ClientModel resourceClient = mock();
    ClientModel requestClient = mock();
    when(session.clients().getClientByClientId(any(), eq(ResourceBinding.RESOURCE_CLIENT_ID)))
        .thenReturn(resourceClient);
    when(resourceClient.getAttribute(ResourceBinding.RESOURCE_URL)).thenReturn(RESOURCE);
    when(requestClient.getAttribute(ResourceBinding.MARKER)).thenReturn("true");
    when(requestClient.getClientId()).thenReturn("cellarbridge-mcp-host");
    return new Fixture(session, requestClient);
  }

  private static TokenCall tokenCall(Fixture fixture, String grant) {
    ClientSessionContext clientContext = mock();
    AuthenticatedClientSessionModel clientSession = mock();
    OAuth2Code code = mock();
    AccessToken access = new AccessToken();
    RefreshToken refresh = mock();
    var refreshClaims = new HashMap<String, Object>();
    access.audience(ResourceBinding.RESOURCE_CLIENT_ID);
    when(clientContext.getClientSession()).thenReturn(clientSession);
    when(clientContext.getAttribute(OAuth2Constants.RESOURCE, String.class)).thenReturn(RESOURCE);
    when(clientContext.getAttribute(org.keycloak.models.Constants.GRANT_TYPE, String.class))
        .thenReturn(grant);
    when(clientSession.getClient()).thenReturn(fixture.client());
    when(clientSession.getNote(ResourceBinding.SESSION_NOTE)).thenReturn(RESOURCE);
    when(code.getResource()).thenReturn(RESOURCE);
    when(refresh.getOtherClaims()).thenReturn(refreshClaims);
    return new TokenCall(
        new TokenPostProcessorContext(code, null, refresh, access, clientContext),
        clientContext,
        access,
        refreshClaims);
  }

  private record Fixture(KeycloakSession session, ClientModel client) {}

  private record TokenCall(
      TokenPostProcessorContext context,
      ClientSessionContext clientContext,
      AccessToken access,
      HashMap<String, Object> refreshClaims) {}
}
