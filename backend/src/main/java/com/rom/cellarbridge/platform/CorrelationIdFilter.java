package com.rom.cellarbridge.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER_NAME = "X-Correlation-ID";
  public static final String MDC_KEY = "correlationId";
  private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = normalize(request.getHeader(HEADER_NAME));
    response.setHeader(HEADER_NAME, correlationId);

    try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_KEY, correlationId)) {
      filterChain.doFilter(request, response);
    }
  }

  static String normalize(String candidate) {
    if (candidate != null && SAFE_VALUE.matcher(candidate).matches()) {
      return candidate;
    }
    return UUID.randomUUID().toString();
  }
}
