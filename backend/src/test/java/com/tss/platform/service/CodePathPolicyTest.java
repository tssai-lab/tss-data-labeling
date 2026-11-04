package com.tss.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodePathPolicyTest {

    private final CodePathPolicy policy = new CodePathPolicy();

    @Test
    void normalizesWindowsSeparatorsWithoutCollapsingComponents() {
        assertEquals("src/training/train.py", policy.normalizeFilePath("src\\training\\train.py"));
        assertEquals("src/training/train.py",
                policy.normalizeRawArchiveFilePath("src\\training\\train.py"));
        assertEquals("src/training",
                policy.normalizeRawArchiveDirectoryPath("src\\training\\"));
    }

    @Test
    void rawArchiveNormalizationStillRejectsTraversalAbsoluteAndControlPaths() {
        for (String rawPath : List.of(
                "..\\train.py",
                "C:\\training\\train.py",
                "\\\\server\\share\\train.py",
                "src\\\u0001train.py"
        )) {
            CodeValidationException error = assertThrows(
                    CodeValidationException.class,
                    () -> policy.normalizeRawArchiveFilePath(rawPath)
            );
            assertEquals("INVALID_PATH", error.getReasonCode());
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "/absolute.py",
            "\\absolute.py",
            "C:\\training\\train.py",
            "a/../train.py",
            "a/./train.py",
            "a//train.py",
            "trailing/",
            "nul\u0000name.py"
    })
    void rejectsUnsafeOrAmbiguousPaths(String rawPath) {
        CodeValidationException error = assertThrows(
                CodeValidationException.class,
                () -> policy.normalizeFilePath(rawPath)
        );

        assertEquals("INVALID_PATH", error.getReasonCode());
    }

    @Test
    void rejectsExactDuplicateAfterNormalization() {
        CodeValidationException error = assertThrows(
                CodeValidationException.class,
                () -> policy.validateNoTreeConflicts(List.of("src/train.py", "src/train.py"))
        );

        assertEquals("DUPLICATE_PATH", error.getReasonCode());
    }

    @Test
    void rejectsFileThatIsAncestorOfAnotherFile() {
        CodeValidationException error = assertThrows(
                CodeValidationException.class,
                () -> policy.validateNoTreeConflicts(List.of("train.py/config.json", "train.py"))
        );

        assertEquals("TREE_CONFLICT", error.getReasonCode());
    }

    @Test
    void treatsPathsAsCaseSensitive() {
        assertDoesNotThrow(() -> policy.validateNoTreeConflicts(List.of("Train.py", "train.py")));
    }

    @Test
    void normalizesRootAndVirtualDirectoryPrefixes() {
        assertEquals("", policy.normalizeDirectoryPrefix(null));
        assertEquals("", policy.normalizeDirectoryPrefix(" "));
        assertEquals("", policy.normalizeDirectoryPrefix("/"));
        assertEquals("src/training", policy.normalizeDirectoryPrefix("src\\training/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "//",
            "/absolute",
            "C:\\training",
            "a/../training",
            "a/./training",
            "a//training",
            "nul\u0000name"
    })
    void rejectsUnsafeVirtualDirectoryPrefixes(String rawPrefix) {
        CodeValidationException error = assertThrows(
                CodeValidationException.class,
                () -> policy.normalizeDirectoryPrefix(rawPrefix)
        );
        assertEquals("INVALID_PATH", error.getReasonCode());
    }

    @Test
    void enforcesPersistedPathCharacterLimit() {
        String exact = "a".repeat(1020) + ".txt";
        assertEquals(1024, exact.length());
        assertEquals(exact, policy.normalizeFilePath(exact));

        CodeValidationException file = assertThrows(
                CodeValidationException.class,
                () -> policy.normalizeFilePath("a".repeat(1021) + ".txt")
        );
        assertEquals("INVALID_PATH", file.getReasonCode());

        CodeValidationException directory = assertThrows(
                CodeValidationException.class,
                () -> policy.normalizeDirectoryPrefix("a".repeat(1025))
        );
        assertEquals("INVALID_PATH", directory.getReasonCode());
    }
}
