package com.rom.cellarbridge.test;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgresIntegrationTestSupport {

  protected static final String DATABASE = "cellarbridge";
  protected static final String USERNAME = "cellarbridge";
  protected static final String PASSWORD = "cellarbridge_test";

  @Container
  protected static final GenericContainer<?> POSTGRES =
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
}
