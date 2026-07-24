package com.rom.cellarbridge.keycloak.resource;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;

final class ResourceBinding {
  static final String PROVIDER_ID = "cellarbridge-mcp-resource-binding";
  static final String RESOURCE_CLIENT_ID = "cellarbridge-mcp";
  static final String MARKER = "cellarbridge.mcp.resource-enforced";
  static final String SESSION_NOTE = "cellarbridge.mcp.resource";
  static final String REQUEST_VERIFIED = "cellarbridge.mcp.resource.request-verified";
  static final String RESOURCE_URL = "resource_url";
  static final String ERROR = "The resource indicator is missing, duplicated, or does not match";

  private ResourceBinding() {}

  record Verification(String clientId, String grant, String resource) {
    boolean matches(ClientModel client, String actualGrant, String actualResource) {
      return client != null
          && allExact(clientId, client.getClientId())
          && allExact(grant, actualGrant)
          && allExact(resource, actualResource);
    }
  }

  static boolean protects(ClientModel client) {
    return client != null && protects(client.getAttribute(MARKER));
  }

  static boolean protects(String marker) {
    return "true".equals(marker);
  }

  static String canonical(KeycloakSession session) {
    ClientModel resource =
        session.clients().getClientByClientId(session.getContext().getRealm(), RESOURCE_CLIENT_ID);
    String value = resource == null ? null : resource.getAttribute(RESOURCE_URL);
    return isCanonical(value) ? value : null;
  }

  static boolean isCanonical(String value) {
    try {
      URI uri = URI.create(value);
      return "https".equals(uri.getScheme())
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && uri.getFragment() == null;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  static boolean oneExact(List<String> values, String expected) {
    return values != null && values.size() == 1 && expected.equals(values.getFirst());
  }

  static boolean allExact(String expected, String... values) {
    return expected != null && Arrays.stream(values).allMatch(expected::equals);
  }

  static boolean contains(String[] values, String expected) {
    return values != null && Arrays.asList(values).contains(expected);
  }
}
