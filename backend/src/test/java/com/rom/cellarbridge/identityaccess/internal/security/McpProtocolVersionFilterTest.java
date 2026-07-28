package com.rom.cellarbridge.identityaccess.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.platform.mcp.McpSecuritySupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

class McpProtocolVersionFilterTest {

  @ParameterizedTest
  @CsvSource({
    "2025-03-26, false",
    "2025-06-18, false",
    "2025-11-25, false",
    "2099-12-31-secret-marker, true"
  })
  void classifiesPostProtocolVersions(String protocol, boolean rejected) {
    assertThat(rejects("POST", "/mcp", protocol)).isEqualTo(rejected);
  }

  @Test
  void allowsMissingHeaderGetAndNonMcpRoutes() {
    assertThat(rejects("POST", "/mcp", null)).isFalse();
    assertThat(rejects("GET", "/mcp", "2099-12-31")).isFalse();
    assertThat(rejects("POST", "/api/v1/me", "2099-12-31")).isFalse();
  }

  private static boolean rejects(String method, String path, String protocol) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    if (protocol != null) request.addHeader("MCP-Protocol-Version", protocol);
    return McpSecuritySupport.hasUnsupportedProtocol(request);
  }
}
