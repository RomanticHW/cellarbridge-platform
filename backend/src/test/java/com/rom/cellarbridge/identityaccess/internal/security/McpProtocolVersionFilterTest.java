package com.rom.cellarbridge.identityaccess.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpProtocolVersionFilterTest {

  private final McpProtocolVersionFilter filter = new McpProtocolVersionFilter();

  @ParameterizedTest
  @ValueSource(strings = {"2025-03-26", "2025-06-18", "2025-11-25"})
  void acceptsSupportedProtocolVersions(String protocolVersion) throws Exception {
    FilterResult result = execute("POST", "/mcp", protocolVersion);

    assertThat(result.chainCalled()).isTrue();
    assertThat(result.response().getStatus()).isEqualTo(200);
  }

  @Test
  void allowsAnInitializeRequestWithoutAProtocolVersionHeader() throws Exception {
    FilterResult result = execute("POST", "/mcp", null);

    assertThat(result.chainCalled()).isTrue();
    assertThat(result.response().getStatus()).isEqualTo(200);
  }

  @Test
  void rejectsAnUnsupportedProtocolVersionWithAFixedNonCacheableError() throws Exception {
    String unsupportedVersion = "2099-12-31-secret-marker";

    FilterResult result = execute("POST", "/mcp", unsupportedVersion);

    assertThat(result.chainCalled()).isFalse();
    assertThat(result.response().getStatus()).isEqualTo(400);
    assertThat(result.response().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    assertThat(result.response().getContentType()).isEqualTo("application/json;charset=UTF-8");
    assertThat(result.response().getContentAsString())
        .isEqualTo(
            "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,"
                + "\"message\":\"Unsupported MCP protocol version\"}}")
        .doesNotContain(unsupportedVersion);
  }

  @Test
  void leavesGetAndNonMcpRequestsToTheirConfiguredHandlers() throws Exception {
    FilterResult get = execute("GET", "/mcp", "2099-12-31");
    FilterResult otherPath = execute("POST", "/api/v1/me", "2099-12-31");

    assertThat(get.chainCalled()).isTrue();
    assertThat(otherPath.chainCalled()).isTrue();
  }

  private FilterResult execute(String method, String path, String protocolVersion)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    if (protocolVersion != null) {
      request.addHeader(McpProtocolVersionFilter.PROTOCOL_VERSION_HEADER, protocolVersion);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean();

    filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

    return new FilterResult(chainCalled.get(), response);
  }

  private record FilterResult(boolean chainCalled, MockHttpServletResponse response) {}
}
