package com.tss.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.ImportJobStatusDto;
import com.tss.platform.dto.v2.V2UserError;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ImportJob;

import java.util.LinkedHashMap;
import java.util.Map;

final class V2ImportJobDisplayHelper {

    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper();
    private static final String IMPORT_SUCCESS = "SUCCESS";
    private static final String IMPORT_SUPERSEDED = "SUPERSEDED";
    private static final String IMPORT_PARTIAL = "PARTIAL";
    private static final String IMPORT_FAILED = "FAILED";

    private V2ImportJobDisplayHelper() {
    }

    static V2UserError userError(ImportJob job) {
        return userError(job, DEFAULT_OBJECT_MAPPER);
    }

    static V2UserError userError(ImportJob job, ObjectMapper objectMapper) {
        if (job == null
                || (!"FAILED".equals(job.getStatus())
                && !"PARTIAL".equals(job.getStatus()))) {
            return null;
        }
        String code = job.getErrorCode() == null
                ? ("PARTIAL".equals(job.getStatus())
                        ? "PARTIAL_IMPORT_FAILED"
                        : "IMPORT_FAILED")
                : job.getErrorCode();
        String message = job.getErrorMessage() == null
                ? ("PARTIAL".equals(job.getStatus())
                        ? "部分样本导入失败，可增量重试"
                        : "数据导入失败，请检查上传内容后重试")
                : job.getErrorMessage();
        return new V2UserError(code, message, parseDetails(job.getErrorDetailsJson(), objectMapper));
    }

    static V2UserError userError(ImportJobStatusDto source) {
        if (source == null
                || (!IMPORT_FAILED.equals(source.getStatus())
                && !IMPORT_PARTIAL.equals(source.getStatus()))) {
            return null;
        }
        boolean partial = IMPORT_PARTIAL.equals(source.getStatus());
        String code = source.getErrorCode() == null
                ? (partial ? "PARTIAL_IMPORT_FAILED" : "IMPORT_FAILED")
                : source.getErrorCode();
        String message = source.getErrorMessage() == null
                ? (partial
                        ? "部分样本导入失败，可增量重试"
                        : "数据导入失败，请检查上传内容后重试")
                : source.getErrorMessage();
        LinkedHashMap<String, Object> details = new LinkedHashMap<>(
                parseDetails(
                        source.getErrorDetailsJson(),
                        DEFAULT_OBJECT_MAPPER
                )
        );
        if (partial) {
            details.putIfAbsent(
                    "failedSamples",
                    safeInt(source.getFailedSamples())
            );
            details.putIfAbsent(
                    "importedSamples",
                    safeInt(source.getImportedSamples())
            );
            details.putIfAbsent(
                    "totalSamples",
                    safeInt(source.getTotalSamples())
            );
            details.putIfAbsent("retryMode", "INCREMENTAL");
        }
        return new V2UserError(code, message, Map.copyOf(details));
    }

    static Map<String, Object> parseDetails(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    json,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    }
            );
            return Map.copyOf(parsed);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    static boolean isPublishTerminalJobStatus(String status) {
        return IMPORT_SUCCESS.equals(status) || IMPORT_SUPERSEDED.equals(status);
    }

    static String catalogDisplayStatus(
            DatasetVersion ready,
            DatasetVersion draft,
            ImportJob importJob
    ) {
        String importDisplayStatus = importDisplayStatus(importJob);
        if (importDisplayStatus != null) {
            return importDisplayStatus;
        }
        if (draft != null) {
            return "EDITING";
        }
        return ready != null ? "READY" : "EMPTY";
    }

    static String editSessionDisplayStatus(ImportJob importJob) {
        String importDisplayStatus = importDisplayStatus(importJob);
        return importDisplayStatus == null ? "EDITING" : importDisplayStatus;
    }

    static String displayVersion(DatasetVersion version) {
        return version.getVersionLabel() != null && !version.getVersionLabel().isBlank()
                ? version.getVersionLabel()
                : version.getVersion();
    }

    private static String importDisplayStatus(ImportJob importJob) {
        if (importJob == null) {
            return null;
        }
        if (IMPORT_PARTIAL.equals(importJob.getStatus())) {
            return "IMPORT_PARTIAL";
        }
        if (IMPORT_FAILED.equals(importJob.getStatus())) {
            return "IMPORT_FAILED";
        }
        if (V2ImportJobStatusSelector.IMPORTING_STATUSES.contains(importJob.getStatus())) {
            return "IMPORTING";
        }
        return null;
    }
}
