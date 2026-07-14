package com.rom.cellarbridge.identityaccess.internal.infrastructure;

import com.rom.cellarbridge.identityaccess.GlobalRegistryAccess;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.identityaccess.internal.application.IdentityAccessRepository;
import com.rom.cellarbridge.identityaccess.internal.domain.Permission;
import com.rom.cellarbridge.identityaccess.internal.domain.RoleTemplate;
import com.rom.cellarbridge.identityaccess.internal.domain.Tenant;
import com.rom.cellarbridge.identityaccess.internal.domain.TenantStatus;
import com.rom.cellarbridge.identityaccess.internal.domain.UserMapping;
import com.rom.cellarbridge.identityaccess.internal.domain.UserStatus;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdentityAccessRepository implements IdentityAccessRepository {

  private static final String USER_MAPPING_QUERY =
      """
      SELECT um.id AS mapping_id,
             um.user_id,
             um.issuer,
             um.external_subject,
             um.username,
             um.display_name,
             um.status AS user_status,
             um.partner_id,
             t.id AS tenant_id,
             t.code AS tenant_code,
             t.display_name AS tenant_name,
             t.status AS tenant_status
        FROM identity_access.user_mapping um
        JOIN identity_access.tenant t ON t.id = um.tenant_id
       WHERE um.issuer = :issuer
         AND um.external_subject = :subject
      """;

  private final NamedParameterJdbcTemplate jdbc;

  JdbcIdentityAccessRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @GlobalRegistryAccess
  public Optional<UserMapping> findByIssuerAndSubject(String issuer, String subject) {
    List<UserRow> rows =
        jdbc.query(
            USER_MAPPING_QUERY,
            new MapSqlParameterSource().addValue("issuer", issuer).addValue("subject", subject),
            (resultSet, rowNumber) ->
                new UserRow(
                    resultSet.getObject("mapping_id", UUID.class),
                    resultSet.getObject("user_id", UUID.class),
                    resultSet.getString("issuer"),
                    resultSet.getString("external_subject"),
                    resultSet.getString("username"),
                    resultSet.getString("display_name"),
                    UserStatus.valueOf(resultSet.getString("user_status")),
                    resultSet.getObject("partner_id", UUID.class),
                    new Tenant(
                        TenantId.of(resultSet.getObject("tenant_id", UUID.class)),
                        resultSet.getString("tenant_code"),
                        resultSet.getString("tenant_name"),
                        TenantStatus.valueOf(resultSet.getString("tenant_status")))));
    if (rows.size() > 1) {
      throw new IllegalStateException("External identity has conflicting local mappings");
    }
    return rows.stream().findFirst().map(this::toMapping);
  }

  @Override
  public Optional<UUID> findPublicUserId(TenantId tenantId, UUID userId) {
    List<UUID> values =
        jdbc.query(
            """
            SELECT user_id
              FROM identity_access.user_mapping
             WHERE tenant_id = :tenantId
               AND user_id = :userId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("userId", userId),
            (resultSet, rowNumber) -> resultSet.getObject("user_id", UUID.class));
    return values.stream().findFirst();
  }

  @Override
  public List<UUID> findPublicUserIds(
      TenantId tenantId, String usernamePrefix, int pageSize, int offset) {
    if (pageSize < 1 || pageSize > 100 || offset < 0) {
      throw new IllegalArgumentException("Invalid page bounds");
    }
    String escapedPrefix =
        usernamePrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return jdbc.query(
        """
        SELECT user_id
          FROM identity_access.user_mapping
         WHERE tenant_id = :tenantId
           AND username LIKE :usernamePrefix ESCAPE '\\'
         ORDER BY username, id
         LIMIT :pageSize OFFSET :offset
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("usernamePrefix", escapedPrefix + "%")
            .addValue("pageSize", pageSize)
            .addValue("offset", offset),
        (resultSet, rowNumber) -> resultSet.getObject("user_id", UUID.class));
  }

  private UserMapping toMapping(UserRow row) {
    return new UserMapping(
        row.mappingId(),
        row.userId(),
        row.issuer(),
        row.externalSubject(),
        row.username(),
        row.displayName(),
        row.status(),
        row.partnerId(),
        row.tenant(),
        loadRoles(row.tenant().id(), row.mappingId()));
  }

  private List<RoleTemplate> loadRoles(TenantId tenantId, UUID mappingId) {
    return jdbc.query(
        """
        SELECT rt.code, rt.display_name, rt.permission_codes
          FROM identity_access.user_mapping_role umr
          JOIN identity_access.role_template rt
            ON rt.tenant_id = umr.tenant_id
           AND rt.code = umr.role_code
         WHERE umr.tenant_id = :tenantId
           AND umr.user_mapping_id = :mappingId
         ORDER BY rt.code
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("mappingId", mappingId),
        (resultSet, rowNumber) ->
            new RoleTemplate(
                tenantId,
                resultSet.getString("code"),
                resultSet.getString("display_name"),
                permissions(resultSet.getArray("permission_codes"))));
  }

  private static Set<Permission> permissions(Array value) throws SQLException {
    return Arrays.stream((String[]) value.getArray())
        .map(Permission::fromStoredValue)
        .collect(Collectors.toUnmodifiableSet());
  }

  private record UserRow(
      UUID mappingId,
      UUID userId,
      String issuer,
      String externalSubject,
      String username,
      String displayName,
      UserStatus status,
      UUID partnerId,
      Tenant tenant) {}
}
