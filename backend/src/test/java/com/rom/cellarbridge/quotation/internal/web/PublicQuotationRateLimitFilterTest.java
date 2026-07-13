package com.rom.cellarbridge.quotation.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class PublicQuotationRateLimitFilterTest {

  @Test
  void limitsDecisionAttemptsWithoutEchoingTheCapabilityToken() throws Exception {
    String token = "customer-capability-token-value-that-must-not-be-returned";
    PublicQuotationRateLimitFilter filter =
        new PublicQuotationRateLimitFilter(
            Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC),
            JsonMapper.builder().build());

    for (int attempt = 0; attempt < 30; attempt++) {
      MockHttpServletResponse allowed = invoke(filter, token);
      assertThat(allowed.getStatus()).isEqualTo(200);
    }

    MockHttpServletResponse limited = invoke(filter, token);
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
    assertThat(limited.getContentAsString())
        .contains("\"code\":\"RATE_LIMITED\"")
        .doesNotContain(token);
  }

  private static MockHttpServletResponse invoke(PublicQuotationRateLimitFilter filter, String token)
      throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/portal/quotations/" + token + "/acceptance");
    request.setRemoteAddr("192.0.2.10");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }
}
