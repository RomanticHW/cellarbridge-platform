package com.rom.cellarbridge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityEvidenceMatrixTest {

  @Test
  void matrixCoversTenantRoleOwnershipStateAndFieldBoundaries() throws Exception {
    Path workingDirectory = Path.of("").toAbsolutePath();
    Path repositoryRoot =
        workingDirectory.getFileName().toString().equals("backend")
            ? workingDirectory.getParent()
            : workingDirectory;
    Path matrix = repositoryRoot.resolve("docs/evidence/security/authorization-matrix.csv");
    List<String[]> rows =
        Files.readAllLines(matrix).stream()
            .skip(1)
            .filter(line -> !line.isBlank())
            .map(line -> line.split(",", -1))
            .toList();

    assertThat(rows).hasSizeGreaterThanOrEqualTo(24).allSatisfy(row -> assertThat(row).hasSize(9));
    assertThat(rows).extracting(row -> row[1]).contains("list", "detail", "action");
    assertThat(rows).extracting(row -> row[2]).contains("current", "other");
    assertThat(rows).extracting(row -> row[7]).contains("allowed", "denied");
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row[3]).isNotBlank();
              assertThat(row[4]).isNotBlank();
              assertThat(row[5]).isNotBlank();
              assertThat(row[6]).isNotBlank();
              assertThat(row[8]).endsWith("Test");
            });
  }
}
