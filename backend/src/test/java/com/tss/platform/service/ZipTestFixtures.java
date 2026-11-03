package com.tss.platform.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ZipTestFixtures {

    private ZipTestFixtures() {
    }

    static byte[] zip(EntrySpec... entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (EntrySpec spec : entries) {
                ZipEntry entry = new ZipEntry(spec.path());
                entry.setMethod(spec.method());
                if (spec.method() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(spec.content());
                    entry.setSize(spec.content().length);
                    entry.setCompressedSize(spec.content().length);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(spec.content());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    static EntrySpec stored(String path, String content) {
        return new EntrySpec(path, content.getBytes(StandardCharsets.UTF_8), ZipEntry.STORED);
    }

    static EntrySpec deflated(String path, String content) {
        return new EntrySpec(path, content.getBytes(StandardCharsets.UTF_8), ZipEntry.DEFLATED);
    }

    static byte[] patchCompressionMethod(byte[] zip, int method) {
        byte[] patched = zip.clone();
        int local = findSignature(patched, 0x04034b50, 0);
        int central = findSignature(patched, 0x02014b50, 0);
        writeU16(patched, local + 8, method);
        writeU16(patched, central + 10, method);
        return patched;
    }

    static int findSignature(byte[] bytes, int signature, int from) {
        for (int i = from; i <= bytes.length - 4; i++) {
            if ((bytes[i] & 0xff) == (signature & 0xff)
                    && (bytes[i + 1] & 0xff) == ((signature >>> 8) & 0xff)
                    && (bytes[i + 2] & 0xff) == ((signature >>> 16) & 0xff)
                    && (bytes[i + 3] & 0xff) == ((signature >>> 24) & 0xff)) {
                return i;
            }
        }
        throw new IllegalArgumentException("ZIP signature not found");
    }

    static void writeU16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    record EntrySpec(String path, byte[] content, int method) {
    }
}
