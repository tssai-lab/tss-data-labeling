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

class CodeModelZipValidatorTest {

    @Test
    void rejectsWeightsInCodeZip() throws Exception {
        byte[] zip = buildZip(
                entry("scripts/training/train_fusion_baseline.py", "print('ok')"),
                entry("weights/best.pt", "FAKE")
        );
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip)
        );
        assertEquals("训练代码包包含不支持的文件类型: weights/best.pt", error.getMessage());
    }

    @Test
    void acceptsCodeWithConfigYaml() throws Exception {
        byte[] zip = buildZip(
                entry("scripts/training/train_fusion_baseline.py", "print('ok')"),
                entry("config/model.yaml", "model: logreg\n")
        );
        assertDoesNotThrow(() -> validate(zip));
    }

    @Test
    void acceptsMetadataJsonl() throws Exception {
        byte[] zip = buildZip(
                entry("scripts/train.py", "pass"),
                entry("metadata/index.jsonl", "{\"id\":1}\n")
        );
        assertDoesNotThrow(() -> validate(zip));
    }

    @Test
    void rejectsBlockedShellScript() throws Exception {
        byte[] zip = buildZip(
                entry("scripts/run.sh", "#!/bin/bash"),
                entry("scripts/train.py", "pass")
        );
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip)
        );
        assertEquals("训练代码包不允许可执行/脚本入口文件: scripts/run.sh", error.getMessage());
    }

    @Test
    void rejectsUnsupportedExtension() throws Exception {
        byte[] zip = buildZip(
                entry("scripts/train.py", "pass"),
                entry("weights/model.bin", "binary")
        );
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip)
        );
        assertEquals("训练代码包包含不支持的文件类型: weights/model.bin", error.getMessage());
    }

    @Test
    void rejectsFileWithoutExtension() throws Exception {
        byte[] zip = buildZip(
                entry("scripts/train.py", "pass"),
                entry("weights/best", "noext")
        );
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip)
        );
        assertEquals("训练代码包包含无扩展名文件: weights/best", error.getMessage());
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        byte[] zip = buildZip(entry("../etc/passwd", "x"));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip)
        );
        assertEquals("训练代码包 zip 包含非法路径: ../etc/passwd", error.getMessage());
    }

    @Test
    void rejectsEmptyZip() throws Exception {
        byte[] zip = buildZip();
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip)
        );
        assertEquals("训练代码包 zip 不能为空", error.getMessage());
    }

    private static ZipEntrySpec entry(String name, String content) {
        return new ZipEntrySpec(name, content);
    }

    private static byte[] buildZip(ZipEntrySpec... entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (ZipEntrySpec spec : entries) {
                ZipEntry zipEntry = new ZipEntry(spec.name);
                zos.putNextEntry(zipEntry);
                zos.write(spec.content.getBytes());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static void validate(byte[] zipBytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            CodeModelZipValidator.validate(zip);
        }
    }

    private record ZipEntrySpec(String name, String content) {
    }
}
