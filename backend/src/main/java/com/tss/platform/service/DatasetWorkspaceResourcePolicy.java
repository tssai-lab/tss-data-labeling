package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.repository.DatasetSampleDataRepository;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 共用字段规则。调用方提供历史错误码；不承接权限、事务或资源写入。 */
final class DatasetWorkspaceResourcePolicy {
    private static final Set<String> DATA_TYPES = Set.of(
            "IMAGE", "TEXT", "POINT_CLOUD", "AUDIO", "VIDEO", "OTHER");
    private final String errorCode;

    DatasetWorkspaceResourcePolicy(String errorCode) {
        this.errorCode = errorCode;
    }

    String requiredText(String value, String message, int maxLength) {
        if (value == null || value.isBlank()) throw invalid(message);
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw invalid("字段长度超过限制");
        return normalized;
    }

    String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        return requiredText(value, "字段不能为空", maxLength);
    }

    int nonNegative(Integer value, String field) {
        int normalized = value == null ? 0 : value;
        if (normalized < 0) throw invalid(field + " 必须是非负整数");
        return normalized;
    }

    String dataType(String value) {
        String normalized = requiredText(value, "dataType 不能为空", 32).toUpperCase(Locale.ROOT);
        if (!DATA_TYPES.contains(normalized)) throw invalid("dataType 不受支持");
        return normalized;
    }

    static String fallback(String value, String current) {
        return value == null || value.isBlank() ? current : value;
    }

    static Map<String, Object> copyMap(Map<String, Object> value) {
        return value == null ? null : new LinkedHashMap<>(value);
    }

    /** 必须在原工作区鉴权/事务中调用，防止跨工作区、跨样本引用已删除数据。 */
    static void validateAnnotationTarget(DatasetSampleDataRepository dataRepo,
            String workspaceId, String sampleId, String sampleDataId) {
        if (sampleDataId == null) return;
        DatasetSampleData data = dataRepo.findByIdAndDatasetVersionId(sampleDataId, workspaceId)
                .orElseThrow(() -> new V2BusinessException(HttpStatus.CONFLICT,
                        "ANNOTATION_TARGET_INVALID", "sampleDataId 不存在或不属于当前工作区"));
        if (Boolean.TRUE.equals(data.getDeleted()) || !sampleId.equals(data.getSampleId())) {
            throw new V2BusinessException(HttpStatus.CONFLICT,
                    "ANNOTATION_TARGET_INVALID", "sampleDataId 已删除或不属于同一样本");
        }
    }

    private V2BusinessException invalid(String message) {
        return new V2BusinessException(HttpStatus.BAD_REQUEST, errorCode, message);
    }
}
