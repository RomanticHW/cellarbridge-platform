package com.rom.cellarbridge.identityaccess.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.identityaccess.internal.application.IdentityAccessRepository;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest
class JdbcIdentityAccessRepositoryIntegrationTest extends PostgresIntegrationTestSupport {

  private static final TenantId TENANT_A =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final TenantId TENANT_B =
      TenantId.of(UUID.fromString("20000000-0000-4000-8000-000000000001"));
  private static final UUID TENANT_B_USER = UUID.fromString("21200000-0000-4000-8000-000000000001");
  private static final UUID NORTH_BUYER_PARTNER =
      UUID.fromString("53000000-0000-4000-8000-000000000001");

  @Autowired private IdentityAccessRepository repository;

  @Test
  void tenantPredicatePrecedesFilterAndPagination() {
    assertThat(repository.findPublicUserIds(TENANT_A, "north.", 2, 0))
        .containsExactly(
            UUID.fromString("11200000-0000-4000-8000-000000000004"),
            UUID.fromString("11200000-0000-4000-8000-000000000009"));
    assertThat(repository.findPublicUserIds(TENANT_A, "north.", 2, 2))
        .containsExactly(
            UUID.fromString("11200000-0000-4000-8000-000000000002"),
            UUID.fromString("11200000-0000-4000-8000-000000000008"));
    assertThat(repository.findPublicUserIds(TENANT_A, "north.", 2, 4))
        .containsExactly(
            UUID.fromString("11200000-0000-4000-8000-000000000003"),
            UUID.fromString("11200000-0000-4000-8000-000000000007"));
    assertThat(repository.findPublicUserIds(TENANT_A, "north.", 2, 6))
        .containsExactly(
            UUID.fromString("11200000-0000-4000-8000-000000000001"),
            UUID.fromString("11200000-0000-4000-8000-000000000099"));
    assertThat(repository.findPublicUserIds(TENANT_A, "north.", 2, 8))
        .containsExactly(
            UUID.fromString("11200000-0000-4000-8000-000000000005"),
            UUID.fromString("11200000-0000-4000-8000-000000000006"));
    assertThat(repository.findPublicUserIds(TENANT_B, "north.", 100, 0)).isEmpty();
  }

  @Test
  void cannotGuessAUserFromAnotherTenant() {
    assertThat(repository.findPublicUserId(TENANT_A, TENANT_B_USER)).isEmpty();
    assertThat(repository.findPublicUserId(TENANT_B, TENANT_B_USER)).contains(TENANT_B_USER);
  }

  @Test
  void resolvesRolesAndStablePermissionsFromTheLocalMapping() {
    var mapping =
        repository
            .findByIssuerAndSubject(
                "http://localhost:8081/realms/cellarbridge", "11000000-0000-4000-8000-000000000001")
            .orElseThrow();

    assertThat(mapping.tenant().id()).isEqualTo(TENANT_A);
    assertThat(mapping.partnerId()).isNull();
    assertThat(mapping.roles()).extracting("code").containsExactly("sales-representative");
    assertThat(mapping.roles().getFirst().permissions())
        .extracting(permission -> permission.code().value())
        .contains("partner:read", "quotation:create");
  }

  @Test
  void resolvesOptionalBuyerPartnerScopeFromTheLocalMapping() {
    var mapping =
        repository
            .findByIssuerAndSubject(
                "http://localhost:8081/realms/cellarbridge", "11000000-0000-4000-8000-000000000002")
            .orElseThrow();

    assertThat(mapping.tenant().id()).isEqualTo(TENANT_A);
    assertThat(mapping.partnerId()).isEqualTo(NORTH_BUYER_PARTNER);
    assertThat(mapping.roles()).extracting("code").containsExactly("customer-buyer");
  }
}
