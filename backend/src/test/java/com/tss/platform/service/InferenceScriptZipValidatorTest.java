package com.tss.platform.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InferenceScriptZipValidatorTest {

    @Test
    void acceptsZipContainingEntryFile() throws Exception {
        byte[] zip = buildZip(
                entry("infer.py", "print('ok')"),
                entry("config/config.json", "{}")
        );
        assertDoesNotThrow(() -> validate(zip, "infer.py"));
    }

    @Test
    void rejectsMissingEntryFile() throws Exception {
        byte[] zip = buildZip(entry("scripts/infer.py", "print('ok')"));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip, "infer.py")
        );
        assertEquals("推理脚本 zip 未包含 entryFile: infer.py", error.getMessage());
    }

    @Test
    void rejectsEntryFileThatIsNotPython() throws Exception {
        byte[] zip = buildZip(entry("infer.txt", "not python"));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip, "infer.txt")
        );
        assertEquals("entryFile 必须是 Python 文件", error.getMessage());
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        byte[] zip = buildZip(entry("../infer.py", "print('bad')"));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip, "../infer.py")
        );
        assertEquals("entryFile 非法", error.getMessage());
    }

    @Test
    void rejectsBlockedExecutableFile() throws Exception {
        byte[] zip = buildZip(
                entry("infer.py", "print('ok')"),
                entry("run.sh", "#!/bin/sh")
        );
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip, "infer.py")
        );
        assertEquals("推理脚本包不允许可执行文件: run.sh", error.getMessage());
    }

    private static ZipEntrySpec entry(String name, String content) {
        return new ZipEntrySpec(name, content);
    }

    private static byte[] buildZip(ZipEntrySpec... entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (ZipEntrySpec spec : entries) {
                zos.putNextEntry(new ZipEntry(spec.name));
                zos.write(spec.content.getBytes());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static void validate(byte[] zipBytes, String entryFile) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            InferenceScriptZipValidator.validate(zip, entryFile);
        }
    }

    private record ZipEntrySpec(String name, String content) {
    }
}
