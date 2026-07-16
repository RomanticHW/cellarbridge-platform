package com.rom.cellarbridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
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
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "cellarbridge.build.commit=test-commit")
class FoundationApplicationIntegrationTest extends PostgresIntegrationTestSupport {

  @Value("${local.server.port}")
  private int port;

  @Autowired private Flyway flyway;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  void startsAgainstPostgresAndAppliesAllCurrentMigrations() {
    assertThat(flyway.info().applied())
        .extracting(migration -> migration.getVersion().getVersion())
        .containsExactly("2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14");
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
