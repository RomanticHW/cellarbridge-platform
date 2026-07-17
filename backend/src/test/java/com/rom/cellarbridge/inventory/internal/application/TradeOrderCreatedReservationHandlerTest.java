package com.rom.cellarbridge.inventory.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.platform.EventHandlingException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DataIntegrityViolationException;

class TradeOrderCreatedReservationHandlerTest {

  @Test
  void classifiesDeadlockAndSerializationForBoundedTechnicalRetry() {
    assertRetryable(new CannotAcquireLockException("deadlock"));
    assertRetryable(new CannotSerializeTransactionException("serialization"));
  }

  @Test
  void classifiesConstraintFailureAsFinalWithoutPublishingABusinessFailure() {
    RuntimeException result =
        TradeOrderCreatedReservationHandler.classifyDataAccess(
            new DataIntegrityViolationException("constraint"));

    assertThat(result).isInstanceOf(EventHandlingException.class);
    EventHandlingException failure = (EventHandlingException) result;
    assertThat(failure.retryable()).isFalse();
    assertThat(failure.failureCode()).isEqualTo("INVENTORY_PERSISTENCE_CONSTRAINT_VIOLATION");
  }

  private static void assertRetryable(RuntimeException exception) {
    RuntimeException result =
        TradeOrderCreatedReservationHandler.classifyDataAccess(
            (org.springframework.dao.DataAccessException) exception);
    assertThat(result).isInstanceOf(EventHandlingException.class);
    EventHandlingException failure = (EventHandlingException) result;
    assertThat(failure.retryable()).isTrue();
    assertThat(failure.failureCode()).isEqualTo("INVENTORY_STORAGE_UNAVAILABLE");
  }
}
