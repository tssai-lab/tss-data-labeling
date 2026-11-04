package com.tss.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeFilePolicyTest {

    private final CodeFilePolicy policy = new CodeFilePolicy();

    @ParameterizedTest
    @MethodSource("supportedFiles")
    void mapsSupportedExtensions(String path, String extension, String languageId, String contentType) {
        CodeFileDescriptor descriptor = policy.describe(path, "content".getBytes(StandardCharsets.UTF_8));

        assertEquals(path, descriptor.path());
        assertEquals(path.substring(path.lastIndexOf('/') + 1), descriptor.name());
        assertEquals("FILE", descriptor.nodeType());
        assertEquals(extension, descriptor.extension());
        assertEquals(languageId, descriptor.languageId());
        assertEquals(contentType, descriptor.contentType());
        assertTrue(descriptor.previewable());
        assertTrue(descriptor.editable());
        assertTrue(descriptor.downloadable());
        assertNull(descriptor.reasonCode());
    }

    @Test
    void enforcesEditableLimitAtRawByteBoundary() {
        CodeFileDescriptor below = policy.describe("below.txt", asciiBytes(1_048_575));
        CodeFileDescriptor exact = policy.describe("exact.txt", asciiBytes(1_048_576));
        CodeFileDescriptor above = policy.describe("above.txt", asciiBytes(1_048_577));

        assertTrue(below.previewable());
        assertTrue(below.editable());
        assertTrue(exact.previewable());
        assertTrue(exact.editable());
        assertFalse(above.previewable());
        assertFalse(above.editable());
        assertTrue(above.downloadable());
        assertEquals("FILE_TOO_LARGE", above.reasonCode());
    }

    @Test
    void preservesBomAndLineEndingsAndHashesOriginalBytes() throws Exception {
        byte[] withBomAndCrLf = "\uFEFFfirst\r\nsecond\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] withoutBomAndLf = "first\nsecond\n".getBytes(StandardCharsets.UTF_8);

        String decoded = policy.decodeEditable(withBomAndCrLf);
        CodeFileDescriptor descriptor = policy.describe("script.py", withBomAndCrLf);

        assertEquals("\uFEFFfirst\r\nsecond\r\n", decoded);
        assertArrayEquals(withBomAndCrLf, decoded.getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedSha256(withBomAndCrLf), descriptor.contentHash());
        assertNotEquals(policy.sha256(withBomAndCrLf), policy.sha256(withoutBomAndLf));
    }

    @Test
    void marksMalformedUtf8AsDownloadOnly() {
        byte[] malformed = {(byte) 0xC3, 0x28};

        CodeFileDescriptor descriptor = policy.describe("broken.py", malformed);

        assertFalse(descriptor.previewable());
        assertFalse(descriptor.editable());
        assertTrue(descriptor.downloadable());
        assertEquals("INVALID_UTF8", descriptor.reasonCode());
        CodeValidationException error = assertThrows(
                CodeValidationException.class,
                () -> policy.decodeEditable(malformed)
        );
        assertEquals("INVALID_UTF8", error.getReasonCode());
    }

    @Test
    void rejectsUnsupportedExtensionWithStableReasonCode() {
        CodeValidationException error = assertThrows(
                CodeValidationException.class,
                () -> policy.validateSupportedPath("weights/model.bin")
        );

        assertEquals("UNSUPPORTED_EXTENSION", error.getReasonCode());
    }

    private static Stream<Arguments> supportedFiles() {
        return Stream.of(
                Arguments.of("src/train.py", ".py", "python", "text/x-python"),
                Arguments.of("config/model.json", ".json", "json", "application/json"),
                Arguments.of("metadata/items.jsonl", ".jsonl", "json", "application/x-ndjson"),
                Arguments.of("config/model.yaml", ".yaml", "yaml", "application/yaml"),
                Arguments.of("config/model.yml", ".yml", "yaml", "application/yaml"),
                Arguments.of("README.md", ".md", "markdown", "text/markdown"),
                Arguments.of("notes.txt", ".txt", "plaintext", "text/plain")
        );
    }

    private static byte[] asciiBytes(int size) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) 'a');
        return bytes;
    }

    private static String expectedSha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
