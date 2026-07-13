package com.rom.cellarbridge.identityaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class TenantContextSnapshotTest {

  private final TenantContextHolder holder = new TenantContextHolder();

  @Test
  void explicitlyPropagatesToAPooledThreadAndClearsAfterTheTask() throws Exception {
    TenantContext context = context();
    TenantContextSnapshot snapshot;
    try (TenantContextHolder.Scope ignored = holder.open(context)) {
      snapshot = TenantContextSnapshot.capture(holder);
    }

    try (var executor = Executors.newFixedThreadPool(1)) {
      TenantContext propagated =
          executor.submit(snapshot.wrap(holder, holder::requireCurrent)).get();
      assertThat(propagated).isEqualTo(context);
      assertThat(executor.submit(holder::current).get()).isEmpty();
    }
  }

  @Test
  void propagatesToAVirtualThreadWithoutUsingAnInheritableGlobal() throws Exception {
    TenantContext context = context();
    TenantContextSnapshot snapshot;
    try (TenantContextHolder.Scope ignored = holder.open(context)) {
      snapshot = TenantContextSnapshot.capture(holder);
    }

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      assertThat(executor.submit(snapshot.wrap(holder, holder::requireCurrent)).get())
          .isEqualTo(context);
    }
    assertThat(holder.current()).isEmpty();
  }

  @Test
  void nestedScopesRestoreThePreviousContext() {
    TenantContext outer = context();
    TenantContext inner =
        new TenantContext(
            UUID.fromString("22200000-0000-4000-8000-000000000001"),
            "Harbor Manager",
            TenantId.of(UUID.fromString("20000000-0000-4000-8000-000000000001")),
            "Harbor Cellars",
            "ACTIVE",
            Set.of("Sales Manager"),
            Set.of(PermissionCode.PARTNER_REVIEW),
            "subject-two",
            "tenant-two");

    try (TenantContextHolder.Scope ignoredOuter = holder.open(outer)) {
      try (TenantContextHolder.Scope ignoredInner = holder.open(inner)) {
        assertThat(holder.requireCurrent()).isEqualTo(inner);
      }
      assertThat(holder.requireCurrent()).isEqualTo(outer);
    }
    assertThat(holder.current()).isEmpty();
  }

  private static TenantContext context() {
    return new TenantContext(
        UUID.fromString("11200000-0000-4000-8000-000000000001"),
        "North Sales",
        TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001")),
        "North Cellars",
        "ACTIVE",
        Set.of("Sales Representative"),
        Set.of(PermissionCode.PARTNER_READ),
        "subject-one",
        "tenant-one");
  }
}
