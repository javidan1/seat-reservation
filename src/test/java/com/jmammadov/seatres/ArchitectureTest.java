package com.jmammadov.seatres;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;

/**
 * The rules from CLAUDE.md, enforced rather than intended — structural (layers) AND semantic (clock
 * discipline, transaction discipline). If one of these fails, the change is architecturally wrong
 * even if every behavior test passes.
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

  @ArchTest
  static final ArchRule theDatabaseIsTheOnlyClock =
      noClasses()
          .should()
          .callMethod(Instant.class, "now")
          .orShould()
          .callMethod(System.class, "currentTimeMillis")
          .because(
              "expiry is decided by Postgres now() inside each statement (CLAUDE.md invariant 2);"
                  + " a JVM clock comparison would reintroduce the races the design eliminates");

  @ArchTest
  static final ArchRule transactionBoundariesAreExplicit =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.transaction.annotation.Transactional")
          .because(
              "transaction boundaries are visible TransactionTemplate blocks, never proxy"
                  + " annotations whose semantics hide at the call site");

  @ArchTest
  static final ArchRule transactionOwnershipStaysInTheApplicationLayer =
      noClasses()
          .that()
          .resideOutsideOfPackage("..application..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.transaction.support.TransactionTemplate");

  @ArchTest static final ArchRule constructorInjectionOnly = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
}
