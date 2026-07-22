package com.rom.cellarbridge.platform.internal;

import com.rom.cellarbridge.platform.LocalEventHandler;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Query-backed operational gauges evaluated only when a metrics backend scrapes them. */
@Component
final class OperationalMetrics {

  private final NamedParameterJdbcTemplate jdbc;

  OperationalMetrics(
      NamedParameterJdbcTemplate jdbc, MeterRegistry meters, List<LocalEventHandler> handlers) {
    this.jdbc = jdbc;
    handlers.forEach(
        handler -> {
          Gauge.builder(
                  "cellarbridge.event.publication.backlog",
                  this,
                  metrics -> metrics.pendingFor(handler))
              .tag("consumer", handler.consumerName())
              .tag("event_type", handler.eventType())
              .description("Events waiting for a registered local consumer")
              .register(meters);
          Gauge.builder(
                  "cellarbridge.event.publication.oldest.age.seconds",
                  this,
                  metrics -> metrics.oldestAgeFor(handler))
              .tag("consumer", handler.consumerName())
              .tag("event_type", handler.eventType())
              .description("Age of the oldest event waiting for a registered local consumer")
              .register(meters);
        });
    Gauge.builder("cellarbridge.projection.lag.seconds", this, OperationalMetrics::projectionLag)
        .description("Age of the oldest audit reporting event still awaiting projection")
        .register(meters);
    Gauge.builder(
            "cellarbridge.exception.open.critical", this, OperationalMetrics::openCriticalCases)
        .description("Open critical exception cases")
        .register(meters);
  }

  private double pendingFor(LocalEventHandler handler) {
    return number(
        """
        SELECT count(*)
          FROM platform_event.event_publication publication
         WHERE publication.event_type = :eventType
           AND NOT EXISTS (
             SELECT 1
               FROM platform_event.event_inbox inbox
              WHERE inbox.tenant_id = publication.tenant_id
                AND inbox.event_id = publication.event_id
                AND inbox.consumer_name = :consumerName
                AND inbox.status IN ('PROCESSED', 'FAILED_FINAL'))
        """,
        parameters(handler));
  }

  private double oldestAgeFor(LocalEventHandler handler) {
    return number(
        """
        SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(publication.occurred_at))), 0)
          FROM platform_event.event_publication publication
         WHERE publication.event_type = :eventType
           AND NOT EXISTS (
             SELECT 1
               FROM platform_event.event_inbox inbox
              WHERE inbox.tenant_id = publication.tenant_id
                AND inbox.event_id = publication.event_id
                AND inbox.consumer_name = :consumerName
                AND inbox.status IN ('PROCESSED', 'FAILED_FINAL'))
        """,
        parameters(handler));
  }

  private double projectionLag() {
    return number(
        """
        SELECT COALESCE(
          EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(event_occurred_at))), 0)
          FROM audit_reporting.projector_inbox
         WHERE status IN ('PROCESSING', 'PENDING')
        """,
        new MapSqlParameterSource());
  }

  private double openCriticalCases() {
    return number(
        """
        SELECT count(*)
          FROM exception_center.exception_case
         WHERE severity = 'CRITICAL'
           AND status NOT IN ('RESOLVED', 'CLOSED')
        """,
        new MapSqlParameterSource());
  }

  private double number(String sql, MapSqlParameterSource parameters) {
    Number value = jdbc.queryForObject(sql, parameters, Number.class);
    return value == null ? 0 : value.doubleValue();
  }

  private static MapSqlParameterSource parameters(LocalEventHandler handler) {
    return new MapSqlParameterSource()
        .addValue("consumerName", handler.consumerName())
        .addValue("eventType", handler.eventType());
  }
}
