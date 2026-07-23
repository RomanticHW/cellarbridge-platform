package com.rom.cellarbridge.identityaccess.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

class McpCorsConfigurationTest {

  private static final String ALLOWED_ORIGIN = "http://localhost:5173";

  private final SecurityConfiguration configuration = new SecurityConfiguration();

  @Test
  void allowsAConfiguredOriginGetToReachTheMcpTransport() throws Exception {
    MockHttpServletRequest request = mcpGet(ALLOWED_ORIGIN);
    MockHttpServletResponse response = new MockHttpServletResponse();
    CorsConfiguration cors = mcpCors(request);

    boolean continueFilterChain =
        new DefaultCorsProcessor().processRequest(cors, request, response);

    assertThat(cors.getAllowedMethods()).containsExactly("GET", "POST", "OPTIONS");
    assertThat(continueFilterChain).isTrue();
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isEqualTo(ALLOWED_ORIGIN);
  }

  @Test
  void rejectsAnUnconfiguredOriginBeforeTheMcpTransport() throws Exception {
    MockHttpServletRequest request = mcpGet("https://origin.invalid");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean continueFilterChain =
        new DefaultCorsProcessor().processRequest(mcpCors(request), request, response);

    assertThat(continueFilterChain).isFalse();
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
  }

  private CorsConfiguration mcpCors(MockHttpServletRequest request) {
    SecurityProperties properties =
        new SecurityProperties(
            "https://issuer.example",
            "https://issuer.example/jwks",
            "cellarbridge-api",
            List.of(ALLOWED_ORIGIN));
    CorsConfiguration cors =
        configuration.corsConfigurationSource(properties).getCorsConfiguration(request);
    assertThat(cors).isNotNull();
    return cors;
  }

  private static MockHttpServletRequest mcpGet(String origin) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp");
    request.addHeader(HttpHeaders.ORIGIN, origin);
    return request;
  }
}
