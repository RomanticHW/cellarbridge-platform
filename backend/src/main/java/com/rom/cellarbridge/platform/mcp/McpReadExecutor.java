package com.rom.cellarbridge.platform.mcp;

import com.rom.cellarbridge.platform.CorrelationIdFilter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public final class McpReadExecutor {
  static final String DEADLINE = McpReadExecutor.class.getName() + ".deadline";
  private final ExecutionContext executionContext;
  private final JdbcTemplate jdbc;
  private final McpSecurityProperties properties;
  private final McpSecuritySupport security;
  private final Tracer tracer;
  private final PlatformTransactionManager transactionManager;
  private final ThreadPoolExecutor executor;

  McpReadExecutor(
      ExecutionContext executionContext,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      McpSecurityProperties properties,
      McpSecuritySupport security,
      Tracer tracer) {
    this.executionContext = executionContext;
    this.jdbc = jdbc;
    this.properties = properties;
    this.security = security;
    this.tracer = tracer;
    this.transactionManager = transactionManager;
    int workers = Math.max(1, properties.maxConcurrency());
    this.executor =
        new ThreadPoolExecutor(
            workers,
            workers,
            0,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            Thread.ofPlatform().name("mcp-read-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  public <T> T execute(Supplier<T> operation) {
    return execute(operation, true);
  }

  public <T> T executeIdentity(Supplier<T> operation) {
    return execute(operation, false);
  }

  private <T> T execute(Supplier<T> operation, boolean propagateContext) {
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    Span span = tracer.currentSpan();
    long deadline = deadlineNanos();
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) throw security.reportFailure(McpSafeException.timeout());
    Callable<T> operationTask =
        () -> {
          try (MDC.MDCCloseable ignored =
                  correlationId == null
                      ? null
                      : MDC.putCloseable(CorrelationIdFilter.MDC_KEY, correlationId);
              Tracer.SpanInScope ignoredSpan =
                  span == null || span.isNoop() ? null : tracer.withSpan(span)) {
            return transaction(statementTimeoutNanos(deadline))
                .execute(
                    status -> {
                      long statementNanos = statementTimeoutNanos(deadline);
                      jdbc.queryForObject(
                          "SELECT set_config('statement_timeout', ?, true)",
                          String.class,
                          Math.max(1, TimeUnit.NANOSECONDS.toMillis(statementNanos)) + "ms");
                      return operation.get();
                    });
          }
        };
    Callable<T> contextual =
        propagateContext ? executionContext.wrap(operationTask) : operationTask;
    McpSecuritySupport.PermitLease worker = security.workerLease();
    AtomicBoolean pending = null;
    FutureTask<T> future;
    try {
      pending = new AtomicBoolean(true);
      AtomicBoolean ownership = pending;
      future =
          new FutureTask<>(contextual) {
            @Override
            public void run() {
              if (!ownership.compareAndSet(true, false)) return;
              try (worker) {
                super.run();
              }
            }
          };
      executor.execute(future);
    } catch (RuntimeException | Error failure) {
      if (pending == null || pending.compareAndSet(true, false)) worker.close();
      if (failure instanceof RejectedExecutionException)
        throw security.reportFailure(McpSafeException.overloaded());
      throw failure;
    }
    try {
      return future.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException | InterruptedException exception) {
      future.cancel(true);
      if (pending.compareAndSet(true, false)) worker.close();
      if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
      throw security.reportFailure(McpSafeException.timeout());
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      McpSafeException safe =
          cause instanceof McpSafeException direct
              ? direct
              : causedBy(cause, QueryTimeoutException.class, TransactionTimedOutException.class)
                  ? McpSafeException.timeout()
                  : causedBy(
                          cause, CannotCreateTransactionException.class, DataAccessException.class)
                      ? McpSafeException.unavailable()
                      : null;
      if (safe != null) throw security.reportFailure(safe);
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error error) throw error;
      throw new IllegalStateException("MCP read execution failed", cause);
    }
  }

  private TransactionTemplate transaction(long timeoutNanos) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setReadOnly(true);
    long seconds = Math.max(1, (timeoutNanos + TimeUnit.SECONDS.toNanos(1) - 1) / 1_000_000_000);
    transaction.setTimeout((int) Math.min(Integer.MAX_VALUE, seconds));
    return transaction;
  }

  private long statementTimeoutNanos(long deadline) {
    long remaining = deadline - System.nanoTime();
    long guard = TimeUnit.MILLISECONDS.toNanos(10);
    if (remaining <= guard) throw McpSafeException.timeout();
    return Math.min(properties.statementTimeout().toNanos(), remaining - guard);
  }

  @SafeVarargs
  private static boolean causedBy(Throwable failure, Class<? extends Throwable>... types) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      for (Class<? extends Throwable> type : types) if (type.isInstance(cause)) return true;
    return false;
  }

  private long deadlineNanos() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet
        && servlet.getRequest().getAttribute(DEADLINE) instanceof Long deadline) return deadline;
    return System.nanoTime() + properties.requestTimeout().toNanos();
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }

  public interface ExecutionContext {
    <T> Callable<T> wrap(Callable<T> task);
  }
}
