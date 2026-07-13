package com.rom.cellarbridge.architecture;

import com.rom.cellarbridge.CellarBridgeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

  @Test
  void verifiesSpringModulithBoundariesAndCycles() {
    ApplicationModules.of(CellarBridgeApplication.class).verify();
  }
}
