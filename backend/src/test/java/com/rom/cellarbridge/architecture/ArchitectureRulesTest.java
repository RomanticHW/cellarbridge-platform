package com.rom.cellarbridge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.architecture.fixture.domain.IllegalWebDependency;
import com.rom.cellarbridge.identityaccess.GlobalRegistryAccess;
import com.rom.cellarbridge.identityaccess.TenantId;
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
              "org.springframework.web..",
              "jakarta.persistence..",
              "org.springframework.kafka..",
              "org.springframework.data.redis..");

  private final JavaClasses productionClasses =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages(ROOT_PACKAGE);

  @Test
  void keepsDomainIndependentFromWebPersistenceAndMessaging() {
    DOMAIN_ISOLATION.allowEmptyShould(true).check(productionClasses);
  }

  @Test
  void rejectsCrossModuleInternalDependencies() {
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

    assertThat(violations).isEmpty();
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
