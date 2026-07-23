package com.rom.cellarbridge.identityaccess.internal.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

final class McpProtocolVersionFilter extends OncePerRequestFilter {

  static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

  private static final Set<String> SUPPORTED_VERSIONS =
      Set.of("2025-03-26", "2025-06-18", "2025-11-25");
  private static final byte[] UNSUPPORTED_VERSION_RESPONSE =
      """
      {"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Unsupported MCP protocol version"}}
      """
          .strip()
          .getBytes(StandardCharsets.UTF_8);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String protocolVersion = request.getHeader(PROTOCOL_VERSION_HEADER);
    if (isMcpPost(request)
        && protocolVersion != null
        && !SUPPORTED_VERSIONS.contains(protocolVersion)) {
      rejectUnsupportedVersion(response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static boolean isMcpPost(HttpServletRequest request) {
    return HttpMethod.POST.matches(request.getMethod()) && "/mcp".equals(request.getRequestURI());
  }

  private static void rejectUnsupportedVersion(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentLength(UNSUPPORTED_VERSION_RESPONSE.length);
    response.getOutputStream().write(UNSUPPORTED_VERSION_RESPONSE);
  }
}
