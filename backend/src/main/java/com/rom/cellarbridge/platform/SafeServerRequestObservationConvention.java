package com.rom.cellarbridge.platform;

import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/** Keeps route templates in server observations without exporting raw request URLs. */
@Component
public final class SafeServerRequestObservationConvention
    extends DefaultServerRequestObservationConvention {

  @Override
  public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext context) {
    return KeyValues.empty();
  }
}
