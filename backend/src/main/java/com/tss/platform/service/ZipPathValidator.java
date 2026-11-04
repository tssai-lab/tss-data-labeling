package com.tss.platform.service;

final class ZipPathValidator {

    private ZipPathValidator() {
    }

    static String normalizeEntryPath(String path) {
        return ZipCentralDirectoryReader.normalizePath(path);
    }

    static boolean isSafeEntryPath(String path) {
        try {
            normalizeEntryPath(path);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
