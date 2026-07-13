package com.rom.cellarbridge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class UtcClockConfigurationTest {

  @Test
  void suppliesUtcClock() {
    assertThat(new UtcClockConfiguration().utcClock().getZone()).isEqualTo(ZoneOffset.UTC);
  }
}
