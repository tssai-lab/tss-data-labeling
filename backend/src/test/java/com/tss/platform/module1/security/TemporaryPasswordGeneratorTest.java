package com.tss.platform.module1.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TemporaryPasswordGeneratorTest {

    @Test
    void generatesNonPredictablePasswordsWithAllRequiredCharacterGroups() {
        TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();
        Set<String> generated = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            String password = generator.generate();
            generated.add(password);
            assertThat(password).hasSize(16);
            assertThat(password).containsPattern("[A-Z]");
            assertThat(password).containsPattern("[a-z]");
            assertThat(password).containsPattern("[0-9]");
            assertThat(password).containsPattern("[!@#$%*+_\\-]");
        }

        assertThat(generated).hasSize(100);
    }
}
