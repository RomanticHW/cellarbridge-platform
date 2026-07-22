package com.rom.cellarbridge.platform.internal;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
final class BusinessEventMetrics {

  private static final Map<String, Metric> METRICS =
      Map.ofEntries(
          Map.entry(
              "cellarbridge.quotation.approval-requested.v1",
              new Metric("cellarbridge.quotation.lifecycle", "stage", "submitted")),
          Map.entry(
              "cellarbridge.quotation.approved.v1",
              new Metric("cellarbridge.quotation.lifecycle", "stage", "approved")),
          Map.entry(
              "cellarbridge.quotation.accepted.v1",
              new Metric("cellarbridge.quotation.lifecycle", "stage", "accepted")),
          Map.entry(
              "cellarbridge.order.created.v1",
              new Metric("cellarbridge.order.conversion", "outcome", "created")),
          Map.entry(
              "cellarbridge.inventory.reservation-confirmed.v1",
              new Metric("cellarbridge.reservation.outcome", "outcome", "confirmed")),
          Map.entry(
              "cellarbridge.inventory.reservation-failed.v1",
              new Metric("cellarbridge.reservation.outcome", "outcome", "failed")),
          Map.entry(
              "cellarbridge.fulfillment.step-overdue.v1",
              new Metric("cellarbridge.fulfillment.overdue", "outcome", "detected")),
          Map.entry(
              "cellarbridge.exception.opened.v1",
              new Metric("cellarbridge.exception.lifecycle", "stage", "opened")),
          Map.entry(
              "cellarbridge.exception.closed.v1",
              new Metric("cellarbridge.exception.lifecycle", "stage", "recovered")),
          Map.entry(
              "cellarbridge.settlement.payment-recorded.v1",
              new Metric("cellarbridge.settlement.activity", "action", "payment_recorded")),
          Map.entry(
              "cellarbridge.settlement.payment-reversed.v1",
              new Metric("cellarbridge.settlement.activity", "action", "payment_reversed")));

  private final MeterRegistry meters;

  BusinessEventMetrics(MeterRegistry meters) {
    this.meters = meters;
  }

  void published(String eventType) {
    Metric metric = METRICS.get(eventType);
    if (metric != null) {
      meters.counter(metric.name(), metric.tagKey(), metric.tagValue()).increment();
    }
  }

  void deliveryOutcome(String eventType, String outcome) {
    if ("cellarbridge.quotation.accepted.v1".equals(eventType)
        && "already_processed".equals(outcome)) {
      meters.counter("cellarbridge.order.conversion", "outcome", "replayed").increment();
    }
    if ("cellarbridge.order.created.v1".equals(eventType) && "retry_scheduled".equals(outcome)) {
      meters.counter("cellarbridge.reservation.retry", "outcome", "scheduled").increment();
    }
  }

  static Map<String, Metric> definitions() {
    return METRICS;
  }

  record Metric(String name, String tagKey, String tagValue) {}
}
