package com.rom.cellarbridge.tradeorder.internal.application;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rom.cellarbridge.identityaccess.AuthorizationService;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.tradeorder.TradeOrderStatus;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderRepository.OrderPage;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradeOrderApplicationServiceTest {

  private static final TenantId TENANT_ID =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final UUID USER_ID = UUID.fromString("11200000-0000-4000-8000-000000000001");

  private final TradeOrderRepository repository = mock(TradeOrderRepository.class);
  private final TenantContextHolder contextHolder = new TenantContextHolder();
  private final AuthorizationService authorizationService = mock(AuthorizationService.class);
  private final TradeOrderApplicationService service =
      new TradeOrderApplicationService(
          repository,
          new OrderCursorCodec("order-cursor-unit-test-secret-value"),
          contextHolder,
          authorizationService);

  @Test
  void scopesSalesRepresentativeByStableRoleCodeWhenDisplayNameChanges() {
    when(repository.list(
            eq(TENANT_ID), eq(Set.<TradeOrderStatus>of()), isNull(), eq(USER_ID), isNull(), eq(25)))
        .thenReturn(new OrderPage(List.of(), null, false));

    try (TenantContextHolder.Scope ignored =
        contextHolder.open(
            context(Set.of("Regional Account Owner"), Set.of("sales-representative")))) {
      service.listInternal(Set.of(), null, 25, null);
    }

    verify(repository)
        .list(
            eq(TENANT_ID), eq(Set.<TradeOrderStatus>of()), isNull(), eq(USER_ID), isNull(), eq(25));
  }

  @Test
  void doesNotInferOwnerScopeFromDisplayName() {
    when(repository.list(
            eq(TENANT_ID), eq(Set.<TradeOrderStatus>of()), isNull(), isNull(), isNull(), eq(25)))
        .thenReturn(new OrderPage(List.of(), null, false));

    try (TenantContextHolder.Scope ignored =
        contextHolder.open(context(Set.of("Sales Representative"), Set.of("sales-manager")))) {
      service.listInternal(Set.of(), null, 25, null);
    }

    verify(repository)
        .list(eq(TENANT_ID), eq(Set.<TradeOrderStatus>of()), isNull(), isNull(), isNull(), eq(25));
  }

  private static TenantContext context(Set<String> roles, Set<String> roleCodes) {
    return new TenantContext(
        USER_ID,
        "North Sales",
        TENANT_ID,
        "North Cellars",
        "ACTIVE",
        null,
        roles,
        roleCodes,
        Set.of(PermissionCode.ORDER_READ),
        "subject-one",
        "tenant-one");
  }
}
