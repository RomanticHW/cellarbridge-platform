package com.rom.cellarbridge.identityaccess;

import java.util.Objects;
import java.util.concurrent.Callable;

public record TenantContextSnapshot(TenantContext context) {

  public TenantContextSnapshot {
    Objects.requireNonNull(context, "context");
  }

  public static TenantContextSnapshot capture(TenantContextHolder holder) {
    return new TenantContextSnapshot(holder.requireCurrent());
  }

  public Runnable wrap(TenantContextHolder holder, Runnable task) {
    Objects.requireNonNull(holder, "holder");
    Objects.requireNonNull(task, "task");
    return () -> {
      try (TenantContextHolder.Scope ignored = holder.open(context)) {
        task.run();
      }
    };
  }

  public <T> Callable<T> wrap(TenantContextHolder holder, Callable<T> task) {
    Objects.requireNonNull(holder, "holder");
    Objects.requireNonNull(task, "task");
    return () -> {
      try (TenantContextHolder.Scope ignored = holder.open(context)) {
        return task.call();
      }
    };
  }
}
