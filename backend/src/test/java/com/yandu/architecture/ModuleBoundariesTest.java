package com.yandu.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.yandu.YanduApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundariesTest {

    @Test
    void applicationModulesHaveNoIllegalDependencies() {
        ApplicationModules.of(YanduApplication.class).verify();
    }

    @Test
    void domainLayerDoesNotDependOnSpringOrPersistenceFrameworks() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.yandu");

        com.tngtech.archunit.lang.ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "com.baomidou..", "org.apache.ibatis..");

        rule.check(classes);
    }
}
