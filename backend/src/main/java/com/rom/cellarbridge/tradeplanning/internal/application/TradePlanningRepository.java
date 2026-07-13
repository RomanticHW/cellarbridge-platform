package com.rom.cellarbridge.tradeplanning.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteEvaluation;
import java.util.Optional;
import java.util.UUID;

public interface TradePlanningRepository {

  void save(
      TenantId tenantId,
      UUID partnerId,
      UUID actorId,
      String inputSummary,
      RouteEvaluation evaluation);

  Optional<RouteEvaluation> find(TenantId tenantId, UUID evaluationId);
}
