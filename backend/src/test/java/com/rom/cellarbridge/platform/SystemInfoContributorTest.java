package com.rom.cellarbridge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.mock.env.MockEnvironment;

class SystemInfoContributorTest {

  @Test
  void usesSafeFallbacksAndActiveProfile() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");
    SystemInfoContributor contributor =
        new SystemInfoContributor(Optional.empty(), environment, "local");
    Info.Builder builder = new Info.Builder();

    contributor.contribute(builder);

    Map<String, Object> details = builder.build().getDetails();
    assertThat(details.get("cellarbridge").toString()).contains("development", "local", "test");
  }
}
