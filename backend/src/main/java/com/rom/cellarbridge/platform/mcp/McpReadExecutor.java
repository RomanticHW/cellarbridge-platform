package com.rom.cellarbridge.platform.mcp;

import com.rom.cellarbridge.platform.CorrelationIdFilter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
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
  private final TransactionTemplate transactions;
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
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setReadOnly(true);
    this.transactions.setTimeout(
        Math.max(1, Math.toIntExact((properties.statementTimeout().toMillis() + 999) / 1000)));
    this.executor =
        new ThreadPoolExecutor(
            properties.maxConcurrency(),
            properties.maxConcurrency(),
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
    long remaining = remainingNanos();
    if (remaining <= 0) {
      security.timeout();
      throw McpSafeException.timeout();
    }
    Callable<T> task =
        () -> {
          try (MDC.MDCCloseable ignored =
                  correlationId == null
                      ? null
                      : MDC.putCloseable(CorrelationIdFilter.MDC_KEY, correlationId);
              Tracer.SpanInScope ignoredSpan =
                  span == null || span.isNoop() ? null : tracer.withSpan(span)) {
            return transactions.execute(
                status -> {
                  jdbc.queryForObject(
                      "SELECT set_config('statement_timeout', ?, true)",
                      String.class,
                      properties.statementTimeout().toMillis() + "ms");
                  return operation.get();
                });
          }
        };
    Future<T> future;
    try {
      future = executor.submit(propagateContext ? executionContext.wrap(task) : task);
    } catch (RejectedExecutionException exception) {
      security.overloaded();
      throw McpSafeException.overloaded();
    }
    try {
      return future.get(remaining, TimeUnit.NANOSECONDS);
    } catch (TimeoutException | InterruptedException exception) {
      future.cancel(true);
      if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
      security.timeout();
      throw McpSafeException.timeout();
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof QueryTimeoutException || cause instanceof TransactionTimedOutException) {
        security.timeout();
        throw McpSafeException.timeout();
      }
      if (cause instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException("MCP read execution failed");
    }
  }

  private long remainingNanos() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet
        && servlet.getRequest().getAttribute(DEADLINE) instanceof Long deadline) {
      return deadline - System.nanoTime();
    }
    return properties.requestTimeout().toNanos();
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }

  public interface ExecutionContext {
    <T> Callable<T> wrap(Callable<T> task);
  }
}
