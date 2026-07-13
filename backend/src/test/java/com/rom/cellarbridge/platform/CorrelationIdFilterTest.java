package com.rom.cellarbridge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  void preservesSafeIncomingCorrelationId() throws Exception {
    String supplied = "quote-flow_2026.07";
    FilterResult result = applyFilter(supplied);

    assertThat(result.responseValue()).isEqualTo(supplied);
    assertThat(result.observedMdcValue()).isEqualTo(supplied);
  }

  @Test
  void createsCorrelationIdWhenHeaderIsMissing() throws Exception {
    FilterResult result = applyFilter(null);

    assertThat(result.responseValue()).matches("[0-9a-f-]{36}");
    assertThat(result.observedMdcValue()).isEqualTo(result.responseValue());
  }

  @Test
  void replacesMaliciousOrOverlongCorrelationId() throws Exception {
    FilterResult malicious = applyFilter("unsafe\r\nvalue");
    FilterResult overlong = applyFilter("x".repeat(65));

    assertThat(malicious.responseValue()).matches("[0-9a-f-]{36}");
    assertThat(overlong.responseValue()).matches("[0-9a-f-]{36}");
  }

  private FilterResult applyFilter(String supplied) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> observed = new AtomicReference<>();
    if (supplied != null) {
      request.addHeader(CorrelationIdFilter.HEADER_NAME, supplied);
    }

    filter.doFilter(
        request,
        response,
        (ignoredRequest, ignoredResponse) -> observed.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    return new FilterResult(response.getHeader(CorrelationIdFilter.HEADER_NAME), observed.get());
  }

  private record FilterResult(String responseValue, String observedMdcValue) {}
}
