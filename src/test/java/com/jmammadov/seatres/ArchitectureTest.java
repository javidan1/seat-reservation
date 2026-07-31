package com.jmammadov.seatres;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The layer rules from CLAUDE.md, enforced rather than intended. If one of these fails, the change
 * is architecturally wrong even if every behavior test passes.
 */
@AnalyzeClasses(
    packages = "com.jmammadov.seatres",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domainIsFrameworkFree =
      classes()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("..domain..", "java..");

  @ArchTest
  static final ArchRule applicationNeverDependsOnInfra =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..infra..");

  @ArchTest
  static final ArchRule sqlAccessOnlyInPersistence =
      noClasses()
          .that()
          .resideOutsideOfPackage("..infra.persistence..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient");

  @ArchTest
  static final ArchRule webNeverTouchesPersistenceDirectly =
      noClasses()
          .that()
          .resideInAPackage("..infra.web..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..infra.persistence..");
}
