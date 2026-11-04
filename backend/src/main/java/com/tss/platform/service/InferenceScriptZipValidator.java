package com.tss.platform.service;

import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class InferenceScriptZipValidator {

    static final int MAX_ENTRIES = 10_000;
    static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024;

    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            ".sh",
            ".bash",
            ".exe",
            ".bat",
            ".cmd",
            ".dll",
            ".so",
            ".jar"
    );

    private InferenceScriptZipValidator() {
    }

    static void validate(ZipInputStream zip, String entryFile) throws Exception {
        String normalizedEntry = normalizeRequiredEntryFile(entryFile);
        int entries = 0;
        boolean foundFile = false;
        boolean foundEntryFile = false;
        long totalUncompressedBytes = 0;
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            entries += 1;
            if (entries > MAX_ENTRIES) {
                throw new IllegalArgumentException("推理脚本 zip 文件条目过多");
            }
            String entryName = normalizeZipEntryName(entry.getName());
            if (!isSafeZipEntryPath(entryName)) {
                throw new IllegalArgumentException("推理脚本 zip 包含非法路径: " + entry.getName());
            }
            if (!entry.isDirectory()) {
                foundFile = true;
                validateFileExtension(entryName);
                if (entryName.equals(normalizedEntry)) {
                    foundEntryFile = true;
                    if (!entryName.toLowerCase(Locale.ROOT).endsWith(".py")) {
                        throw new IllegalArgumentException("entryFile 必须是 Python 文件");
                    }
                }
                totalUncompressedBytes = drainZipEntry(zip, totalUncompressedBytes);
            }
            zip.closeEntry();
        }
        if (!foundFile) {
            throw new IllegalArgumentException("推理脚本 zip 不能为空");
        }
        if (!foundEntryFile) {
            throw new IllegalArgumentException("推理脚本 zip 未包含 entryFile: " + normalizedEntry);
        }
    }

    static String normalizeRequiredEntryFile(String entryFile) {
        String normalized = normalizeZipEntryName(entryFile);
        if (normalized == null || normalized.isBlank() || normalized.endsWith("/")) {
            throw new IllegalArgumentException("entryFile 不能为空");
        }
        if (!isSafeZipEntryPath(normalized)) {
            throw new IllegalArgumentException("entryFile 非法");
        }
        return normalized;
    }

    private static void validateFileExtension(String entryName) {
        String ext = extensionOf(entryName);
        if (ext != null && BLOCKED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("推理脚本包不允许可执行文件: " + entryName);
        }
    }

    private static long drainZipEntry(ZipInputStream zip, long currentTotal) throws Exception {
        byte[] buffer = new byte[8192];
        long total = currentTotal;
        int len;
        while ((len = zip.read(buffer)) != -1) {
            total += len;
            if (total > MAX_UNCOMPRESSED_BYTES) {
                throw new IllegalArgumentException("推理脚本 zip 解压后体积过大");
            }
        }
        return total;
    }

    private static String normalizeZipEntryName(String name) {
        return name == null ? "" : name.replace('\\', '/').replaceAll("^/+", "");
    }

    private static boolean isSafeZipEntryPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            return false;
        }
        for (String part : path.split("/")) {
            if (".".equals(part) || "..".equals(part) || part.contains("\u0000")) {
                return false;
            }
        }
        return true;
    }

    private static String extensionOf(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return null;
        }
        int slash = entryName.lastIndexOf('/');
        String fileName = slash >= 0 ? entryName.substring(slash + 1) : entryName;
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot).toLowerCase(Locale.ROOT);
    }
}
