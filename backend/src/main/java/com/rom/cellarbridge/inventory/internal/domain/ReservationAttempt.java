package com.rom.cellarbridge.inventory.internal.domain;

import com.rom.cellarbridge.identityaccess.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ReservationAttempt(
    UUID id,
    TenantId tenantId,
    UUID reservationId,
    int attemptNumber,
    String requestHash,
    Trigger trigger,
    Instant startedAt,
    Instant completedAt,
    Outcome outcome,
    String failureCode,
    UUID correlationId,
    UUID causationId) {

  public ReservationAttempt {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(reservationId, "reservationId");
    if (attemptNumber <= 0) {
      throw new IllegalArgumentException("attemptNumber must be positive");
    }
    Reservation.requireHash(requestHash, "requestHash");
    Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(completedAt, "completedAt");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(causationId, "causationId");
    if (completedAt.isBefore(startedAt)) {
      throw new IllegalArgumentException("Attempt completion cannot precede start");
    }
    if (outcome == Outcome.FAILED) {
      Reservation.requireText(failureCode, "failureCode");
    } else if (failureCode != null) {
      throw new IllegalArgumentException("Confirmed Attempt cannot carry a failure code");
    }
  }

  public enum Trigger {
    EVENT,
    MANUAL_RETRY
  }

  public enum Outcome {
    CONFIRMED,
    FAILED
  }

  public record History(TenantId tenantId, UUID reservationId, List<ReservationAttempt> attempts) {

    public History {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(reservationId, "reservationId");
      attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
      for (int index = 0; index < attempts.size(); index++) {
        ReservationAttempt attempt = attempts.get(index);
        if (!tenantId.equals(attempt.tenantId())
            || !reservationId.equals(attempt.reservationId())
            || attempt.attemptNumber() != index + 1
            || index > 0 && !attempts.getFirst().requestHash().equals(attempt.requestHash())) {
          throw new IllegalArgumentException(
              "Attempt history must keep one tenant, Reservation, request hash and continuous append order");
        }
      }
    }

    public static History empty(TenantId tenantId, UUID reservationId) {
      return new History(tenantId, reservationId, List.of());
    }

    public History append(ReservationAttempt attempt) {
      Objects.requireNonNull(attempt, "attempt");
      if (!tenantId.equals(attempt.tenantId())
          || !reservationId.equals(attempt.reservationId())
          || attempt.attemptNumber() != attempts.size() + 1) {
        throw new IllegalArgumentException("Attempt can only be appended at the next number");
      }
      java.util.ArrayList<ReservationAttempt> appended = new java.util.ArrayList<>(attempts);
      appended.add(attempt);
      return new History(tenantId, reservationId, appended);
    }
  }
}
