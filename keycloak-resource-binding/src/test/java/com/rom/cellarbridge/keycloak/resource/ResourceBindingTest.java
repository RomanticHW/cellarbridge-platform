package com.rom.cellarbridge.keycloak.resource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResourceBindingTest {
  private static final String RESOURCE = "https://mcp.cellarbridge.example/mcp";

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
}
