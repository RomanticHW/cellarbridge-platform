package com.rom.cellarbridge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchitectureRulesTest {

  private static final String ROOT_PACKAGE = "com.rom.cellarbridge";
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

  private static String moduleName(String packageName) {
    String remainder = packageName.substring(ROOT_PACKAGE.length() + 1);
    int separator = remainder.indexOf('.');
    return separator < 0 ? remainder : remainder.substring(0, separator);
  }
}
