package com.rom.cellarbridge.platform;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final Tracer tracer;

  public CorrelationIdFilter() {
    this(Tracer.NOOP);
  }

  @Autowired
  CorrelationIdFilter(Tracer tracer) {
    this.tracer = tracer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = normalize(request.getHeader(HEADER_NAME));
    response.setHeader(HEADER_NAME, correlationId);
    Span current = tracer.currentSpan();
    boolean ownsSpan = current == null || current.isNoop();
    boolean mcp = "/mcp".equals(request.getRequestURI());
    Span requestSpan = current;
    if (ownsSpan) {
      requestSpan =
          tracer
              .nextSpan()
              .name("cellarbridge.http.request")
              .tag("http.request.method", request.getMethod());
      if (!mcp) requestSpan.tag("cellarbridge.correlation_id", correlationId);
      requestSpan.start();
    }

    try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_KEY, correlationId);
        Tracer.SpanInScope spanScope = tracer.withSpan(requestSpan)) {
      filterChain.doFilter(request, response);
    } catch (ServletException | IOException | RuntimeException failure) {
      if (!mcp) requestSpan.error(failure);
      throw failure;
    } finally {
      if (ownsSpan) {
        requestSpan.end();
      }
    }
  }

  static String normalize(String candidate) {
    if (candidate != null && SAFE_VALUE.matcher(candidate).matches()) {
      return candidate;
    }
    return UUID.randomUUID().toString();
  }
}
