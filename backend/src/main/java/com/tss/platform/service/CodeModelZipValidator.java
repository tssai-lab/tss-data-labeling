package com.tss.platform.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 训练代码包 ZIP 内容校验：路径安全、扩展名白名单/黑名单、体积与条目限制。
 */
final class CodeModelZipValidator {

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

    private static final CodePathPolicy PATH_POLICY = new CodePathPolicy();
    private static final CodeFilePolicy FILE_POLICY = new CodeFilePolicy();

    private CodeModelZipValidator() {
    }

    static void validate(ZipInputStream zip) throws Exception {
        int entries = 0;
        boolean foundFile = false;
        long totalUncompressedBytes = 0;
        List<String> filePaths = new ArrayList<>();
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            entries += 1;
            if (entries > MAX_ENTRIES) {
                throw new IllegalArgumentException("训练代码包 zip 文件条目过多");
            }
            String entryName = normalizeZipEntryName(entry.getName());
            if (!isSafeZipEntryPath(entryName)) {
                throw new IllegalArgumentException("训练代码包 zip 包含非法路径: " + entry.getName());
            }
            if (!entry.isDirectory()) {
                foundFile = true;
                validateFileExtension(entryName);
                filePaths.add(PATH_POLICY.normalizeFilePath(entryName));
                totalUncompressedBytes = drainZipEntry(zip, totalUncompressedBytes);
            }
            zip.closeEntry();
        }
        if (!foundFile) {
            throw new IllegalArgumentException("训练代码包 zip 不能为空");
        }
        validateTree(filePaths);
    }

    /** 仅基于已读取的文件条目名做路径与扩展名校验（不重读解压体积）。 */
    static void validateEntryNames(List<String> entryNames) {
        if (entryNames == null || entryNames.isEmpty()) {
            throw new IllegalArgumentException("训练代码包 zip 不能为空");
        }
        if (entryNames.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("训练代码包 zip 文件条目过多");
        }
        boolean foundFile = false;
        List<String> filePaths = new ArrayList<>();
        for (String raw : entryNames) {
            String name = normalizeZipEntryName(raw);
            if (!isSafeZipEntryPath(name)) {
                throw new IllegalArgumentException("训练代码包 zip 包含非法路径: " + raw);
            }
            if (name.endsWith("/") ) {
                continue;
            }
            foundFile = true;
            validateFileExtension(name);
            filePaths.add(PATH_POLICY.normalizeFilePath(name));
        }
        if (!foundFile) {
            throw new IllegalArgumentException("训练代码包 zip 不能为空");
        }
        validateTree(filePaths);
    }

    private static void validateFileExtension(String entryName) {
        String ext = extensionOf(entryName);
        if (ext == null || ext.isEmpty()) {
            throw new IllegalArgumentException("训练代码包包含无扩展名文件: " + entryName);
        }
        if (BLOCKED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("训练代码包不允许可执行/脚本入口文件: " + entryName);
        }
        try {
            FILE_POLICY.validateSupportedPath(entryName);
        } catch (CodeValidationException e) {
            throw new IllegalArgumentException("训练代码包包含不支持的文件类型: " + entryName);
        }
    }

    private static long drainZipEntry(ZipInputStream zip, long currentTotal) throws Exception {
        byte[] buffer = new byte[8192];
        long total = currentTotal;
        int len;
        while ((len = zip.read(buffer)) != -1) {
            total += len;
            if (total > MAX_UNCOMPRESSED_BYTES) {
                throw new IllegalArgumentException("训练代码包 zip 解压后体积过大");
            }
        }
        return total;
    }

    static String normalizeZipEntryName(String name) {
        return name == null ? "" : name.replace('\\', '/');
    }

    static boolean isSafeZipEntryPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String candidate = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        try {
            PATH_POLICY.normalizeFilePath(candidate);
            return true;
        } catch (CodeValidationException e) {
            return false;
        }
    }

    private static void validateTree(List<String> filePaths) {
        try {
            PATH_POLICY.validateNoTreeConflicts(filePaths);
        } catch (CodeValidationException e) {
            throw new IllegalArgumentException("训练代码包 zip 包含冲突路径");
        }
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
