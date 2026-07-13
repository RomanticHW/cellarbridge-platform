package com.rom.cellarbridge.platform;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class PlatformExceptionHandlerTest {

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new FailingController())
          .setControllerAdvice(new PlatformExceptionHandler(new ProblemDetailsFactory()))
          .build();

  @Test
  void returnsProblemDetailsWithoutExceptionMessageOrStackTrace() throws Exception {
    mockMvc
        .perform(get("/test/failure"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Internal server error"))
        .andExpect(jsonPath("$.detail").value("The request could not be completed."))
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.traceId").isNotEmpty())
        .andExpect(jsonPath("$.retryable").value(true))
        .andExpect(content().string(not(containsString("sensitive-marker"))))
        .andExpect(content().string(not(containsString("stackTrace"))));
  }

  @RestController
  private static class FailingController {

    @GetMapping("/test/failure")
    String fail() {
      throw new IllegalStateException("sensitive-marker");
    }
  }
}
