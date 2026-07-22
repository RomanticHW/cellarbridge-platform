package com.rom.cellarbridge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.architecture.fixture.domain.IllegalWebDependency;
import com.rom.cellarbridge.identityaccess.GlobalRegistryAccess;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ArchitectureRulesTest {

  private static final String ROOT_PACKAGE = "com.rom.cellarbridge";
  private static final List<String> OWNED_SCHEMAS =
      List.of(
          "identity_access",
          "partner",
          "catalog",
          "inventory",
          "trade_planning",
          "quotation",
          "trade_order",
          "fulfillment",
          "exception_center",
          "settlement",
          "platform_event");
  private static final ArchRule DOMAIN_ISOLATION =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta..",
              "java.sql..",
              "javax.sql..",
              "org.apache.kafka..",
              "io.lettuce..",
              "redis.clients..",
              "..web..",
              "..application..",
              "..infrastructure..")
          .as("FF-ARC-003 domain code is framework and adapter independent");
  private static final DescribedPredicate<JavaClass> REPOSITORY_OR_REPOSITORY_NESTED_TYPE =
      DescribedPredicate.describe(
          "a Repository or a type nested in a Repository",
          type ->
              type.getSimpleName().endsWith("Repository")
                  || type.getEnclosingClass()
                      .map(owner -> owner.getSimpleName().endsWith("Repository"))
                      .orElse(false));

  private final JavaClasses productionClasses =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages(ROOT_PACKAGE);

  @Test
  void ffArc003KeepsDomainIndependentFromFrameworksAndAdapters() {
    DOMAIN_ISOLATION.allowEmptyShould(true).check(productionClasses);
  }

  @Test
  void ffArc004KeepsWebControllersIndependentFromRepositoriesAndTheirNestedTypes() {
    noClasses()
        .that()
        .resideInAPackage("..web..")
        .should()
        .dependOnClassesThat(REPOSITORY_OR_REPOSITORY_NESTED_TYPE)
        .as("FF-ARC-004 web code does not depend on repositories or repository nested types")
        .check(productionClasses);
  }

  @Test
  void ffArc002RejectsCrossModuleInternalDependencies() {
    List<String> violations = new ArrayList<>();
    productionClasses.forEach(
        origin ->
            origin.getDirectDependenciesFromSelf().stream()
                .filter(
                    dependency ->
                        dependency.getTargetClass().getPackageName().contains(".internal"))
                .filter(
                    dependency ->
                        !moduleName(origin.getPackageName())
                            .equals(moduleName(dependency.getTargetClass().getPackageName())))
                .forEach(dependency -> violations.add(dependency.getDescription())));

    assertThat(violations)
        .as("FF-ARC-002 modules do not access another module's internals")
        .isEmpty();
  }

  @Test
  void ffArc005KeepsPublicContractsIndependentFromPersistenceTypes() {
    noClasses()
        .that()
        .arePublic()
        .and()
        .resideOutsideOfPackage("..internal..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..internal.infrastructure..", "..persistence..")
        .as("FF-ARC-005 public events and DTOs do not expose persistence types")
        .check(productionClasses);
  }

  @Test
  void keepsCatalogIndependentFromInventoryPerApprovedDependencyDirection() {
    noClasses()
        .that()
        .resideInAPackage("..catalog..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..inventory..")
        .check(productionClasses);
  }

  @Test
  void keepsQuotationBehindTradePlanningForInventoryCollaboration() {
    noClasses()
        .that()
        .resideInAPackage("..quotation..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..inventory..")
        .check(productionClasses);
    noClasses()
        .that()
        .resideInAPackage("..tradeplanning..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..quotation..")
        .check(productionClasses);
  }

  @Test
  void forbidsGenericDomainDumpingGroundPackages() {
    assertThat(productionClasses.stream())
        .noneMatch(
            javaClass ->
                javaClass
                    .getPackageName()
                    .matches("com\\.rom\\.cellarbridge\\.(common|shared|utils)(\\..*)?"));
  }

  @Test
  void architectureRuleRejectsKnownIllegalFixture() {
    JavaClasses fixture = new ClassFileImporter().importClasses(IllegalWebDependency.class);

    assertThat(DOMAIN_ISOLATION.evaluate(fixture).hasViolation()).isTrue();
  }

  @Test
  void ffData001And005EnforceMigrationOwnershipAndFrozenHistory() throws Exception {
    Path directory = migrationDirectory();
    Path migrations = directory.resolve("migration");
    List<MigrationOwner> rows = migrationOwners(directory);
    List<Path> databaseSqlFiles;
    try (var files = Files.walk(directory)) {
      databaseSqlFiles =
          files
              .filter(Files::isRegularFile)
              .map(directory::relativize)
              .filter(path -> path.getFileName().toString().endsWith(".sql"))
              .sorted()
              .toList();
    }
    assertThat(databaseSqlFiles)
        .allSatisfy(
            path -> {
              assertThat(path.getNameCount()).as("nested database SQL %s", path).isEqualTo(2);
              if (path.startsWith("migration")) {
                assertThat(path.getFileName().toString()).matches("V\\d+__.+\\.sql");
              } else {
                assertThat(path.getName(0).toString()).isEqualTo("demo");
                assertThat(path.getFileName().toString()).matches("R__.+\\.sql");
              }
            });
    List<Path> diskSqlFiles =
        databaseSqlFiles.stream()
            .filter(path -> path.startsWith("migration"))
            .map(path -> path.subpath(1, path.getNameCount()))
            .toList();
    List<String> diskFiles = diskSqlFiles.stream().map(Path::toString).toList();
    assertThat(rows)
        .extracting(MigrationOwner::file)
        .containsExactlyInAnyOrderElementsOf(diskFiles);
    assertThat(rows).extracting(MigrationOwner::version).doesNotHaveDuplicates();
    assertThat(rows).extracting(MigrationOwner::file).doesNotHaveDuplicates();
    assertThat(
            rows.stream()
                .filter(row -> row.version() < 10)
                .map(row -> row.version() + ":" + row.file() + ":" + String.join(";", row.owners()))
                .toList())
        .containsExactly(
            "2:V2__identity_access_tenancy.sql:identity_access",
            "3:V3__partner_onboarding.sql:partner",
            "4:V4__catalog_products_and_search_projection.sql:catalog",
            "5:V5__inventory_supply_model.sql:inventory",
            "6:V6__trade_planning_evaluations.sql:trade_planning",
            "7:V7__quotation_revisions_and_approvals.sql:quotation",
            "8:V8__customer_quotation_decisions.sql:quotation;platform_event",
            "9:V9__trade_order_conversion.sql:identity_access;trade_order;quotation;platform_event");
    rows.forEach(row -> verifyMigration(migrations.resolve(row.file()), row));
    assertThat(rows.stream().filter(row -> !row.exception().isEmpty()).map(MigrationOwner::version))
        .containsExactly(8, 9);

    assertThatCode(
            () ->
                verifyOwnedSql(
                    "UPDATE inventory.lot SET version=version+1; "
                        + "CREATE TRIGGER t BEFORE UPDATE ON inventory.lot "
                        + "EXECUTE FUNCTION inventory.touch()",
                    "inventory"))
        .doesNotThrowAnyException();
    for (String invalid :
        List.of(
            "UPDATE lot SET version=1",
            "DELETE FROM catalog.sku",
            "TRUNCATE inventory.lot, catalog.sku",
            "TRUNCATE inventory.lot, fulfillment.shipment",
            "TRUNCATE inventory.lot, other_lot",
            "CREATE VIEW inventory.queue AS SELECT * FROM fulfillment.shipment",
            "ALTER TABLE inventory.lot SET SCHEMA catalog",
            "ALTER TABLE inventory.parent ATTACH PARTITION child FOR VALUES IN (1)",
            "DROP TABLE inventory.lot CASCADE",
            "UPDATE inventory.lot SET note='--'; DELETE FROM catalog.sku",
            "UPDATE inventory.lot SET version=1; -- '\nDELETE FROM catalog.sku WHERE code='x'",
            "INSERT INTO inventory.lot(id) VALUES (nextval('catalog.lot_id_seq'))",
            "DROP FUNCTION inventory.safe(), unsafe()",
            "COPY inventory.lot TO PROGRAM 'false'",
            "UPDATE inventory.lot SET note=set_config('search_' || 'path','catalog',false)",
            "VACUUM catalog.sku /* UPDATE inventory.lot SET version=1 */",
            "CREATE TABLE inventory.good (id uuid REFERENCES \"catalog\".sku(id))",
            "CREATE TRIGGER t AFTER UPDATE ON inventory.lot EXECUTE PROCEDURE catalog.touch()",
            "UPDATE inventory.lot SET note=$q$'$q$; DELETE FROM catalog.sku WHERE code='x'",
            "UPDATE inventory.lot SET note=E'foo\\'bar'; DELETE FROM catalog.sku WHERE code='x'",
            "DO $$ BEGIN EXECUTE 'UPDATE inventory.lot'; END $$",
            "VACUUM inventory.lot",
            "SET search_path=inventory")) {
      assertThatThrownBy(() -> verifyOwnedSql(invalid, "inventory"))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Test
  void repositoryEntryPointsRequireAnExplicitTenantUnlessMarkedAsGlobalRegistry() {
    List<String> violations =
        productionClasses.stream()
            .filter(javaClass -> javaClass.getSimpleName().endsWith("Repository"))
            .flatMap(javaClass -> javaClass.getMethods().stream())
            .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
            .filter(method -> !method.isAnnotatedWith(GlobalRegistryAccess.class))
            .filter(method -> !method.getOwner().isAnnotatedWith(GlobalRegistryAccess.class))
            .filter(
                method ->
                    method.getRawParameterTypes().stream()
                        .noneMatch(parameter -> parameter.isEquivalentTo(TenantId.class)))
            .map(method -> method.getFullName() + " has no explicit TenantId")
            .toList();

    assertThat(violations).isEmpty();
  }

  private static Path migrationDirectory() {
    Path fromRoot = Path.of("backend", "src", "main", "resources", "db");
    return Files.isDirectory(fromRoot) ? fromRoot : Path.of("src", "main", "resources", "db");
  }

  private static List<MigrationOwner> migrationOwners(Path directory) throws Exception {
    List<String> lines = Files.readAllLines(directory.resolve("migration-ownership.csv"));
    assertThat(lines.getFirst()).isEqualTo("version,file,ownerSchemas,legacyException,sha256");
    return lines.stream().skip(1).map(MigrationOwner::parse).toList();
  }

  private static void verifyMigration(Path file, MigrationOwner row) {
    try {
      String hash =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
      assertThat(row.file()).startsWith("V" + row.version() + "__");
      assertThat(hash).isEqualTo(row.sha256());
      assertThat(row.owners()).allMatch(OWNED_SCHEMAS::contains);
      if (row.version() == 8) {
        assertThat(row.exception()).isEqualTo("ADR-015");
        assertThat(row.owners()).containsExactlyInAnyOrder("quotation", "platform_event");
      } else if (row.version() == 9) {
        assertThat(row.exception()).isEqualTo("ADR-015");
        assertThat(row.owners())
            .containsExactlyInAnyOrder(
                "identity_access", "trade_order", "quotation", "platform_event");
      } else {
        assertThat(row.exception()).isEmpty();
        assertThat(row.owners()).hasSize(1).allMatch(owner -> !owner.isBlank());
      }
      if (row.version() >= 10) {
        verifyOwnedSql(Files.readString(file), row.owners().getFirst());
      }
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot verify migration " + file, exception);
    }
  }

  private static void verifyOwnedSql(String sql, String owner) {
    assertThat(sql).doesNotContain("/*", "*/", "--", "\"", "$", "\\");
    String rawUpper = sql.toUpperCase(java.util.Locale.ROOT);
    assertThat(rawUpper).doesNotContain("SEARCH_PATH");
    String structural = sql.replaceAll("'(?:''|[^'])*'", "''");
    String upper = structural.toUpperCase(java.util.Locale.ROOT);
    assertThat(upper).doesNotMatch("(?s).*\\b(?:DO|EXECUTE)\\s+(?:\\$|['\"]).*");
    assertThat(upper).doesNotMatch("(?s).*\\bLANGUAGE\\s+PLPGSQL\\b.*");
    assertThat(upper).doesNotMatch("(?s).*\\bCREATE(?:\\s+OR\\s+REPLACE)?\\s+FUNCTION\\b.*");
    assertThat(upper)
        .doesNotMatch("(?s).*\\b(?:VACUUM|CLUSTER|REINDEX|REFRESH|GRANT|REVOKE|COMMENT)\\b.*");
    assertThat(upper)
        .doesNotMatch(
            "(?s).*\\b(?:SET\\s+SCHEMA|(?:ATTACH|DETACH)\\s+PARTITION|PARTITION\\s+OF|INHERITS?|OWNED\\s+BY|CASCADE)\\b.*");
    assertThat(upper).doesNotMatch("(?s).*\\bCOPY\\b.*\\bPROGRAM\\b.*");
    assertThat(upper).doesNotMatch("(?s).*\\b(?:SELECT|MERGE)\\b.*");
    assertThat(upper)
        .doesNotMatch("(?s).*\\bUPDATE\\b.*\\bFROM\\b.*|.*\\bDELETE\\s+FROM\\b.*\\bUSING\\b.*");
    assertThat(upper)
        .doesNotMatch(
            "(?s).*\\b(?:NEXTVAL|SETVAL|CURRVAL|TO_REGCLASS|SET_CONFIG)\\s*\\(.*|.*::\\s*REGCLASS\\b.*");
    assertThat(upper)
        .doesNotMatch(
            "(?s).*\\b(?:TRUNCATE(?:\\s+TABLE)?|DROP\\s+(?:TABLE|VIEW|SEQUENCE|TYPE|FUNCTION))\\b[^;]*,.*");
    var qualified =
        Pattern.compile("(?i)\\b([a-z_][\\w$]*)\\s*\\.\\s*[a-z_][\\w$]*\\b").matcher(structural);
    while (qualified.find()) {
      assertThat(qualified.group(1)).isEqualToIgnoringCase(owner);
    }
    Pattern targets =
        Pattern.compile(
            "(?is)\\b(?:(?:CREATE(?:\\s+OR\\s+REPLACE)?|ALTER|DROP)\\s+"
                + "(?:TABLE|VIEW|SEQUENCE|FUNCTION|TYPE)|CREATE\\s+SCHEMA|INSERT\\s+INTO|"
                + "UPDATE|DELETE\\s+FROM|MERGE\\s+INTO|TRUNCATE(?:\\s+TABLE)?|COPY|"
                + "REFERENCES|EXECUTE\\s+(?:FUNCTION|PROCEDURE))\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?"
                + "(?:ONLY\\s+)?([\\w.]+)|\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+"
                + "(?:IF\\s+NOT\\s+EXISTS\\s+)?\\S+\\s+ON\\s+(?:ONLY\\s+)?([\\w.]+)|"
                + "\\bCREATE\\s+(?:CONSTRAINT\\s+)?TRIGGER\\s+\\S+.*?\\bON\\s+([\\w.]+)|"
                + "\\bCREATE\\s+RULE\\s+\\S+\\s+AS\\s+ON\\s+(?:UPDATE|DELETE)\\s+TO\\s+([\\w.]+)");
    for (String statement : structural.split(";")) {
      if (statement.isBlank()) {
        continue;
      }
      statement = statement.strip();
      var matches = targets.matcher(statement);
      assertThat(matches.find()).as("unrecognized migration statement: %s", statement).isTrue();
      assertThat(matches.start()).as("unrecognized statement prefix: %s", statement).isZero();
      do {
        String target =
            matches.group(1) != null
                ? matches.group(1)
                : matches.group(2) != null
                    ? matches.group(2)
                    : matches.group(3) != null ? matches.group(3) : matches.group(4);
        if (statement.matches("(?is)^CREATE\\s+SCHEMA\\b.*")) {
          assertThat(target).as("migration target %s", target).isEqualToIgnoringCase(owner);
        } else {
          assertThat(target.toLowerCase(java.util.Locale.ROOT))
              .as("migration target %s", target)
              .startsWith(owner + ".");
        }
      } while (matches.find());
    }
  }

  private record MigrationOwner(
      int version, String file, List<String> owners, String exception, String sha256) {
    private static MigrationOwner parse(String line) {
      String[] fields = line.split(",", -1);
      assertThat(fields).hasSize(5);
      return new MigrationOwner(
          Integer.parseInt(fields[0]),
          fields[1],
          List.of(fields[2].split(";")),
          fields[3],
          fields[4]);
    }
  }

  private static String moduleName(String packageName) {
    String remainder = packageName.substring(ROOT_PACKAGE.length() + 1);
    int separator = remainder.indexOf('.');
    return separator < 0 ? remainder : remainder.substring(0, separator);
  }
}
