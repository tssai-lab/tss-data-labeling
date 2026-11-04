package com.tss.platform.module1.util;

import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.dto.LogItemVO;
import com.tss.platform.module1.entity.AuditRecord;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 将统一审计记录适配为既有日志页面响应，保持公开 API 字段不变。
 */
public final class AuditRecordConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AuditRecordConverter() {
    }

    public static LogItemVO toVo(AuditRecord record) {
        LogItemVO vo = new LogItemVO();
        vo.setId(record.getId());
        vo.setUsername(nonNull(record.getUsername()));
        vo.setOperateType(nonNull(record.getActionType()));
        vo.setOperateTime(record.getCreatedAt() != null ? record.getCreatedAt().format(FORMATTER) : "");
        vo.setIp(nonNull(record.getIpAddress()));
        vo.setContent(content(record));
        vo.setResult("SUCCESS".equalsIgnoreCase(record.getResult()) ? "success" : "failed");
        vo.setLogType(AuditActionType.LOGIN.name().equalsIgnoreCase(record.getActionType())
                ? "system"
                : "operation");
        return vo;
    }

    public static List<String> resolveActionTypes(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "登录", "退出", "LOGIN", "LOGOUT", "5", "6" -> List.of(AuditActionType.LOGIN.name());
            case "上传", "UPLOAD" -> List.of(AuditActionType.UPLOAD.name());
            case "删除", "DELETE", "2" -> List.of(AuditActionType.DELETE.name());
            case "训练", "TRAIN" -> List.of(AuditActionType.TRAIN.name());
            case "推理", "INFERENCE" -> List.of(AuditActionType.INFERENCE.name());
            case "权限变更", "PERMISSION_CHANGE" -> List.of(AuditActionType.PERMISSION_CHANGE.name());
            default -> List.of(normalized);
        };
    }

    private static String content(AuditRecord record) {
        StringBuilder value = new StringBuilder();
        append(value, objectLabel(record.getObjectType()));
        if (record.getObjectId() != null && !record.getObjectId().isBlank()) {
            append(value, "对象=" + record.getObjectId());
        }
        append(value, record.getDetail());
        if (record.getFailReason() != null && !record.getFailReason().isBlank()) {
            append(value, "失败原因=" + record.getFailReason());
        }
        return value.toString();
    }

    private static String objectLabel(String objectType) {
        if (objectType == null || objectType.isBlank() || "UNKNOWN".equalsIgnoreCase(objectType)) {
            return null;
        }
        return switch (objectType.toUpperCase(Locale.ROOT)) {
            case "USER" -> "用户";
            case "MODEL" -> "模型";
            case "DATASET" -> "数据集";
            case "TRAIN_TASK" -> "训练任务";
            case "INFERENCE_TASK" -> "推理任务";
            case "TRAINING_CODE" -> "训练代码";
            case "TRAINING_PLAN" -> "训练方案";
            case "INFERENCE_SCRIPT" -> "推理脚本";
            default -> objectType;
        };
    }

    private static void append(StringBuilder value, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (!value.isEmpty()) {
            value.append("；");
        }
        value.append(part.trim());
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }
}
