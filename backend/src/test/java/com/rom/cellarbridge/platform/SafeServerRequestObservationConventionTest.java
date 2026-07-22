package com.rom.cellarbridge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SafeServerRequestObservationConventionTest {

  @Test
  void excludesCapabilityBearingRawUrlsFromTraceAttributes() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/portal/quotations/synthetic-capability-token");
    ServerRequestObservationContext context =
        new ServerRequestObservationContext(request, new MockHttpServletResponse());

    assertThat(new SafeServerRequestObservationConvention().getHighCardinalityKeyValues(context))
        .isEmpty();
  }
}
