package com.specshield.ledgerline;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final String ROOT = "com.specshield.ledgerline";

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("LL-A01 domain depends on nothing in web, persistence, Spring or Jakarta Persistence")
    void domainIsFrameworkFree() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        ROOT + ".web..", ROOT + ".persistence..", "org.springframework..", "jakarta.persistence..")
                .because("the domain must stay a plain Java model (AGENTS.md, architecture rules)")
                .check(productionClasses);
    }

    @Test
    @DisplayName("LL-A02 web classes do not depend on persistence")
    void webDoesNotReachIntoPersistence() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".web..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + ".persistence..")
                .because("controllers go through the application layer, never repositories (AGENTS.md)")
                .check(productionClasses);
    }
}
