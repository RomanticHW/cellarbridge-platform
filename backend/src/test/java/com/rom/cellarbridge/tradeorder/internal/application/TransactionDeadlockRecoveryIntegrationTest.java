package com.rom.cellarbridge.tradeorder.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class TransactionDeadlockRecoveryIntegrationTest extends PostgresIntegrationTestSupport {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  @Test
  void classifiesARealPostgresDeadlockForBoundedRetryThenPreservesBalances() throws Exception {
    jdbc.execute("DROP TABLE IF EXISTS trade_order.performance_deadlock_probe");
    jdbc.execute(
        "CREATE TABLE trade_order.performance_deadlock_probe (id integer PRIMARY KEY, value integer NOT NULL)");
    jdbc.update(
        "INSERT INTO trade_order.performance_deadlock_probe (id, value) VALUES (1, 0), (2, 0)");
    try {
      CountDownLatch firstUpdates = new CountDownLatch(2);
      TransactionTemplate transactions =
          new TransactionTemplate(new DataSourceTransactionManager(dataSource));
      List<Throwable> outcomes;
      try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
        Future<Throwable> first =
            executor.submit(() -> deadlockingTransaction(transactions, firstUpdates, 1, 2));
        Future<Throwable> second =
            executor.submit(() -> deadlockingTransaction(transactions, firstUpdates, 2, 1));
        outcomes = new ArrayList<>();
        outcomes.add(first.get(20, TimeUnit.SECONDS));
        outcomes.add(second.get(20, TimeUnit.SECONDS));
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }

      List<DataAccessException> deadlocks =
          outcomes.stream()
              .filter(DataAccessException.class::isInstance)
              .map(DataAccessException.class::cast)
              .toList();
      assertThat(deadlocks).hasSize(1);
      assertThat(QuotationAcceptedEventHandler.classifyDataAccess(deadlocks.getFirst()))
          .isInstanceOfSatisfying(
              EventHandlingException.class,
              failure -> {
                assertThat(failure.failureCode()).isEqualTo("ORDER_STORAGE_UNAVAILABLE");
                assertThat(failure.retryable()).isTrue();
              });

      transactions.executeWithoutResult(
          ignored -> {
            jdbc.update(
                "UPDATE trade_order.performance_deadlock_probe SET value = value + 1 WHERE id = 1");
            jdbc.update(
                "UPDATE trade_order.performance_deadlock_probe SET value = value + 1 WHERE id = 2");
          });
      assertThat(
              jdbc.queryForList(
                  "SELECT value FROM trade_order.performance_deadlock_probe ORDER BY id",
                  Integer.class))
          .containsExactly(2, 2);
    } finally {
      jdbc.execute("DROP TABLE IF EXISTS trade_order.performance_deadlock_probe");
    }
  }

  private Throwable deadlockingTransaction(
      TransactionTemplate transactions, CountDownLatch firstUpdates, int firstId, int secondId) {
    try {
      transactions.executeWithoutResult(
          ignored -> {
            jdbc.update(
                "UPDATE trade_order.performance_deadlock_probe SET value = value + 1 WHERE id = ?",
                firstId);
            firstUpdates.countDown();
            try {
              assertThat(firstUpdates.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("Deadlock probe was interrupted", exception);
            }
            jdbc.update(
                "UPDATE trade_order.performance_deadlock_probe SET value = value + 1 WHERE id = ?",
                secondId);
          });
      return null;
    } catch (RuntimeException failure) {
      return failure;
    }
  }
}
