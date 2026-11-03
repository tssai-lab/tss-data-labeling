package com.tss.platform.service;

import com.tss.platform.entity.DatasetUploadSession;
import java.util.Locale;

/** 上传输入的无状态规则。不查询数据库、不鉴权、不上传对象；入口仍负责先校验正数大小等前置条件。 */
final class DatasetUploadPolicy {
    private DatasetUploadPolicy() {}
    private static final int MIN_CHUNK_SIZE = 5 * 1024 * 1024;
    private static final int CHUNK_SIZE_GRANULARITY = 1024 * 1024;
    private static final int MAX_COMPOSE_SOURCES = 10_000;
    private static final String GROUPING_MANIFEST = "MANIFEST";
    private static final String GROUPING_AUTO_DIRECTORY = "AUTO_DIRECTORY";

    static String normalizeSampleGrouping(String value) {
        String normalized = value == null || value.isBlank()
                ? null
                : value.trim().toUpperCase(Locale.ROOT);
        if (normalized != null
                && !GROUPING_MANIFEST.equals(normalized)
                && !GROUPING_AUTO_DIRECTORY.equals(normalized)) {
            throw new IllegalArgumentException(
                    "sampleGrouping 仅支持 MANIFEST 或 AUTO_DIRECTORY"
            );
        }
        return normalized;
    }

    static String normalizeSampleGroupingForTask(String taskType, String value) {
        String normalized = normalizeSampleGrouping(value);
        if ("MULTIMODAL".equals(taskType) && normalized == null) {
            return GROUPING_AUTO_DIRECTORY;
        }
        return normalized;
    }

    static String normalizeManifestPath(String sampleGrouping, String value) {
        String normalized = value == null || value.isBlank()
                ? null
                : value.trim().replace('\\', '/');
        if (GROUPING_AUTO_DIRECTORY.equals(sampleGrouping)) {
            if (normalized != null) {
                throw new IllegalArgumentException(
                        "AUTO_DIRECTORY 不允许传 manifestPath"
                );
            }
            return null;
        }
        if (!GROUPING_MANIFEST.equals(sampleGrouping)) {
            if (normalized != null) {
                throw new IllegalArgumentException(
                        "manifestPath 仅在 sampleGrouping=MANIFEST 时可用"
                );
            }
            return null;
        }
        if (normalized == null) {
            return "manifest.json";
        }
        if (normalized.length() > 255
                || normalized.startsWith("/")
                || normalized.matches("^[A-Za-z]:.*")
                || normalized.contains("\u0000")) {
            throw new IllegalArgumentException("manifestPath 非法");
        }
        for (String part : normalized.split("/")) {
            if ("..".equals(part)) {
                throw new IllegalArgumentException("manifestPath 非法");
            }
        }
        return normalized;
    }

    static boolean normalizeStrictManifestForTask(
            String taskType,
            String sampleGrouping,
            Boolean value
    ) {
        boolean strict = Boolean.TRUE.equals(value);
        if (!strict) {
            return false;
        }
        if (!"MULTIMODAL".equals(taskType)) {
            throw new IllegalArgumentException(
                    "strictManifest 仅 MULTIMODAL + MANIFEST 支持"
            );
        }
        if (!GROUPING_MANIFEST.equals(sampleGrouping)) {
            throw new IllegalArgumentException(
                    "strictManifest 仅在 sampleGrouping=MANIFEST 时可用"
            );
        }
        return true;
    }

    static int calculateChunkSize(long fileSize) {
        long sizeRequiredByPartLimit = ((fileSize - 1) / MAX_COMPOSE_SOURCES) + 1;
        long rawChunkSize = Math.max(MIN_CHUNK_SIZE, sizeRequiredByPartLimit);
        long roundedChunkSize = ((rawChunkSize + CHUNK_SIZE_GRANULARITY - 1) / CHUNK_SIZE_GRANULARITY)
                * CHUNK_SIZE_GRANULARITY;
        if (roundedChunkSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("fileSize 过大，无法生成有效分片");
        }
        return (int) roundedChunkSize;
    }

    static int calculateTotalChunks(long fileSize, int chunkSize) {
        long totalChunks = ((fileSize - 1) / chunkSize) + 1;
        if (totalChunks > MAX_COMPOSE_SOURCES) {
            throw new IllegalArgumentException("分片数量不能超过 " + MAX_COMPOSE_SOURCES);
        }
        return (int) totalChunks;
    }

    static void validateGroupingForTask(String taskType, String sampleGrouping) {
        if ("MULTIMODAL".equals(taskType) && !isMultimodalGrouping(sampleGrouping)) {
            throw new IllegalArgumentException(
                    "MULTIMODAL 数据集必须使用 sampleGrouping=MANIFEST 或 AUTO_DIRECTORY"
            );
        }
        if (!"MULTIMODAL".equals(taskType) && sampleGrouping != null) {
            throw new IllegalArgumentException(
                    "仅 MULTIMODAL 数据集支持 sampleGrouping"
            );
        }
    }

    static boolean isMultimodalGrouping(String sampleGrouping) {
        return GROUPING_MANIFEST.equals(sampleGrouping)
                || GROUPING_AUTO_DIRECTORY.equals(sampleGrouping);
    }

    static String appendPackageDestinationObject(DatasetUploadSession session) {
        if (session.getOwnerUserId() == null
                || session.getAssetId() == null || session.getAssetId().isBlank()
                || session.getVersionNo() == null
                || session.getId() == null || session.getId().isBlank()
                || session.getFileName() == null || session.getFileName().isBlank()) {
            throw new IllegalArgumentException("append upload session is incomplete");
        }
        return "users/" + session.getOwnerUserId()
                + "/datasets/" + session.getAssetId()
                + "/" + sanitizeSegment("v" + session.getVersionNo())
                + "/packages/" + sanitizeSegment(session.getId())
                + "/" + sanitizeSegment(session.getFileName());
    }

    static String manifestDestinationObject(DatasetUploadSession session) {
        return manifestDestinationObject(
                session.getOwnerUserId(),
                session.getAssetId(),
                session.getVersionNo(),
                session.getFileName()
        );
    }

    static String manifestDestinationObject(
            Integer ownerUserId,
            String assetId,
            Integer versionNo,
            String fileName
    ) {
        if (ownerUserId == null
                || assetId == null || assetId.isBlank()
                || versionNo == null
                || fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("manifest upload reservation is incomplete");
        }
        return "users/" + ownerUserId
                + "/datasets/" + assetId + "/" + sanitizeSegment("v" + versionNo)
                + "/" + sanitizeSegment(fileName);
    }

    static String sanitizeSegment(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return "unnamed";
        }
        return normalized
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .toLowerCase(Locale.ROOT);
    }

    static String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
