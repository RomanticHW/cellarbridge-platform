package com.rom.cellarbridge.auditreporting.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import java.util.List;

public interface ProjectionEventSource {
  List<EventDelivery> all(TenantId tenantId);
}
