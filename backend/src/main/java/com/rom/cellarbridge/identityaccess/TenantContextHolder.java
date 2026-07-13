package com.rom.cellarbridge.identityaccess;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public final class TenantContextHolder {

  private final ThreadLocal<TenantContext> current = new ThreadLocal<>();

  public Optional<TenantContext> current() {
    return Optional.ofNullable(current.get());
  }

  public TenantContext requireCurrent() {
    return current()
        .orElseThrow(() -> new IllegalStateException("Tenant context is not available"));
  }

  public Scope open(TenantContext context) {
    Objects.requireNonNull(context, "context");
    TenantContext previous = current.get();
    current.set(context);
    return new Scope(previous);
  }

  public final class Scope implements AutoCloseable {

    private final TenantContext previous;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Scope(TenantContext previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      if (previous == null) {
        current.remove();
      } else {
        current.set(previous);
      }
    }
  }
}
