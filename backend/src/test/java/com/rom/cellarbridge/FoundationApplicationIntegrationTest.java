package com.rom.cellarbridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "cellarbridge.build.commit=test-commit")
class FoundationApplicationIntegrationTest {

  private static final String DATABASE = "cellarbridge";
  private static final String USERNAME = "cellarbridge";
  private static final String PASSWORD = "cellarbridge_test";

  @Container
  static final GenericContainer<?> POSTGRES =
      new GenericContainer<>("postgres:18.4-alpine")
          .withExposedPorts(5432)
          .withEnv("POSTGRES_DB", DATABASE)
          .withEnv("POSTGRES_USER", USERNAME)
          .withEnv("POSTGRES_PASSWORD", PASSWORD)
          .waitingFor(
              Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () ->
            "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ":"
                + POSTGRES.getMappedPort(5432)
                + "/"
                + DATABASE);
    registry.add("spring.datasource.username", () -> USERNAME);
    registry.add("spring.datasource.password", () -> PASSWORD);
  }

  @Value("${local.server.port}")
  private int port;

  @Autowired private Flyway flyway;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  void startsAgainstEmptyPostgresAndRunsEmptyMigrationSet() {
    assertThat(flyway.info().all()).isEmpty();
  }

  @Test
  void exposesLivenessReadinessAndSafeInfo() throws Exception {
    assertEndpointContains("/actuator/health/liveness", "\"status\":\"UP\"");
    assertEndpointContains("/actuator/health/readiness", "\"status\":\"UP\"");
    assertEndpointContains("/actuator/info", "test-commit");
    assertEndpointContains("/actuator/info", "test");
  }

  private void assertEndpointContains(String path, String expected) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains(expected);
  }
}
