package com.rom.cellarbridge.auditreporting.internal.infrastructure;

import com.rom.cellarbridge.auditreporting.internal.application.ProjectionEventSource;
import com.rom.cellarbridge.auditreporting.internal.application.ProjectionEventSource.SourceState;
import com.rom.cellarbridge.auditreporting.internal.application.ProjectionEventSource.Watermark;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectionEventSource implements ProjectionEventSource {
  private final NamedParameterJdbcTemplate jdbc;

  public JdbcProjectionEventSource(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<EventDelivery> all(TenantId tenantId) {
    return jdbc.query(
        """
        SELECT event_id, tenant_id, event_type, event_version, occurred_at, producer,
               subject_type, subject_id, subject_number, correlation_id, causation_id,
               (payload -> 'payload')::text AS business_payload
          FROM platform_event.event_publication
         WHERE tenant_id = :tenantId
         ORDER BY occurred_at, event_id
        """,
        new MapSqlParameterSource("tenantId", tenantId.value()),
        (resultSet, rowNumber) ->
            new EventDelivery(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getInt("event_version"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getString("producer"),
                new EventDelivery.Subject(
                    resultSet.getString("subject_type"),
                    resultSet.getObject("subject_id", UUID.class),
                    resultSet.getString("subject_number")),
                resultSet.getObject("correlation_id", UUID.class),
                resultSet.getObject("causation_id", UUID.class),
                resultSet.getString("business_payload")));
  }

  @Override
  public SourceState sourceState(TenantId tenantId, Set<String> eventTypes) {
    if (eventTypes == null || eventTypes.isEmpty()) {
      throw new IllegalArgumentException("Projection event types are required");
    }
    List<SourceState> rows =
        jdbc.query(
            """
            SELECT event_id, occurred_at, count(*) OVER () AS event_count
              FROM platform_event.event_publication
             WHERE tenant_id = :tenantId
               AND event_type IN (:eventTypes)
             ORDER BY occurred_at DESC, event_id DESC
             LIMIT 1
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("eventTypes", eventTypes),
            (resultSet, rowNumber) ->
                new SourceState(
                    new Watermark(
                        resultSet.getTimestamp("occurred_at").toInstant(),
                        resultSet.getObject("event_id", UUID.class)),
                    resultSet.getLong("event_count")));
    return rows.isEmpty() ? SourceState.empty() : rows.getFirst();
  }
}
