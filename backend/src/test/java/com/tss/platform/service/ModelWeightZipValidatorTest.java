package com.tss.platform.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelWeightZipValidatorTest {

    @Test
    void acceptsAllowedWeightFiles() throws Exception {
        byte[] zip = buildZip(
                entry("weights/best.pt", "FAKE"),
                entry("weights/model.safetensors", "FAKE"),
                entry("weights/pytorch_model.bin", "FAKE"),
                entry("weights/adapter.ckpt", "FAKE"),
                entry("config/model.yaml", "model: logreg\n"),
                entry("meta.json", "{}")
        );
        assertDoesNotThrow(() -> validate(zip));
    }

    @Test
    void rejectsPythonScript() throws Exception {
        byte[] zip = buildZip(entry("scripts/load.py", "import os"));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip)
        );
        assertEquals("模型权重包不允许脚本或可执行文件: scripts/load.py", error.getMessage());
    }

    @Test
    void rejectsUnsupportedExtension() throws Exception {
        byte[] zip = buildZip(entry("weights/model.weights", "binary"));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip)
        );
        assertEquals("模型权重包包含不支持的文件类型: weights/model.weights", error.getMessage());
    }

    @Test
    void rejectsFileWithoutExtension() throws Exception {
        byte[] zip = buildZip(entry("weights/best", "noext"));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip)
        );
        assertEquals("模型权重包包含无扩展名文件: weights/best", error.getMessage());
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        byte[] zip = buildZip(entry("../etc/passwd", "x"));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip)
        );
        assertEquals("模型权重 zip 包含非法路径: ../etc/passwd", error.getMessage());
    }

    @Test
    void rejectsEmptyZip() throws Exception {
        byte[] zip = buildZip();
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip)
        );
        assertEquals("模型权重 zip 不能为空", error.getMessage());
    }

    @Test
    void rejectsRawZipWithDuplicateInternalPath() throws Exception {
        byte[] zip = buildRawStoredZip(
                rawEntry("weights/model.pt", "one"),
                rawEntry("weights/model.pt", "two")
        );
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validate(zip)
        );
        assertEquals("模型权重 zip 包含重复路径: weights/model.pt", error.getMessage());
    }

    @Test
    void rejectsSlashAliasDuplicateAndFileDirectoryConflict() throws Exception {
        byte[] alias = buildRawStoredZip(
                rawEntry("weights/model.pt", "one"),
                rawEntry("weights\\model.pt", "two")
        );
        assertThrows(IllegalArgumentException.class, () -> validate(alias));

        byte[] normalizedAlias = buildRawStoredZip(
                rawEntry("weights/./model.pt", "one"),
                rawEntry("weights//model.pt", "two")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> validate(normalizedAlias)
        );

        byte[] treeConflict = buildRawStoredZip(
                rawEntry("weights.pt", "file"),
                rawEntry("weights.pt/model.pth", "nested")
        );
        IllegalArgumentException conflict = assertThrows(
                IllegalArgumentException.class,
                () -> validate(treeConflict)
        );
        assertEquals(
                "模型权重 zip 包含文件/目录冲突: weights.pt/model.pth",
                conflict.getMessage()
        );
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
            ModelWeightZipValidator.validate(zip);
        }
    }

    private static RawEntry rawEntry(String name, String content) {
        return new RawEntry(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] buildRawStoredZip(RawEntry... entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(output);
        List<CentralEntry> centralEntries = new ArrayList<>();
        for (RawEntry entry : entries) {
            byte[] name = entry.name().getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(entry.content());
            int localOffset = output.size();
            writeIntLe(out, 0x04034b50);
            writeShortLe(out, 20);
            writeShortLe(out, 0x0800);
            writeShortLe(out, 0);
            writeShortLe(out, 0);
            writeShortLe(out, 0);
            writeIntLe(out, crc.getValue());
            writeIntLe(out, entry.content().length);
            writeIntLe(out, entry.content().length);
            writeShortLe(out, name.length);
            writeShortLe(out, 0);
            out.write(name);
            out.write(entry.content());
            centralEntries.add(new CentralEntry(entry, name, crc.getValue(), localOffset));
        }
        int centralOffset = output.size();
        for (CentralEntry central : centralEntries) {
            writeIntLe(out, 0x02014b50);
            writeShortLe(out, 20);
            writeShortLe(out, 20);
            writeShortLe(out, 0x0800);
            writeShortLe(out, 0);
            writeShortLe(out, 0);
            writeShortLe(out, 0);
            writeIntLe(out, central.crc());
            writeIntLe(out, central.entry().content().length);
            writeIntLe(out, central.entry().content().length);
            writeShortLe(out, central.name().length);
            writeShortLe(out, 0);
            writeShortLe(out, 0);
            writeShortLe(out, 0);
            writeShortLe(out, 0);
            writeIntLe(out, 0);
            writeIntLe(out, central.localOffset());
            out.write(central.name());
        }
        int centralSize = output.size() - centralOffset;
        writeIntLe(out, 0x06054b50);
        writeShortLe(out, 0);
        writeShortLe(out, 0);
        writeShortLe(out, entries.length);
        writeShortLe(out, entries.length);
        writeIntLe(out, centralSize);
        writeIntLe(out, centralOffset);
        writeShortLe(out, 0);
        out.flush();
        return output.toByteArray();
    }

    private static void writeShortLe(DataOutputStream out, int value) throws Exception {
        out.writeByte(value & 0xff);
        out.writeByte((value >>> 8) & 0xff);
    }

    private static void writeIntLe(DataOutputStream out, long value) throws Exception {
        out.writeByte((int) (value & 0xff));
        out.writeByte((int) ((value >>> 8) & 0xff));
        out.writeByte((int) ((value >>> 16) & 0xff));
        out.writeByte((int) ((value >>> 24) & 0xff));
    }

    private record ZipEntrySpec(String name, String content) {
    }

    private record RawEntry(String name, byte[] content) {
    }

    private record CentralEntry(RawEntry entry, byte[] name, long crc, int localOffset) {
    }
}
