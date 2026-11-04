package com.tss.platform.module1.util;

import com.tss.platform.module1.dto.LogItemVO;
import com.tss.platform.module1.entity.OperationLog;

import java.time.format.DateTimeFormatter;
import java.util.List;

public final class OperationLogConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private OperationLogConverter() {
    }

    public static LogItemVO toVo(OperationLog log) {
        LogItemVO vo = new LogItemVO();
        vo.setId(log.getId() != null ? log.getId().longValue() : null);
        vo.setUsername(log.getUserName() != null ? log.getUserName() : "");
        vo.setOperateType(mapOperateType(log.getOperationType()));
        vo.setOperateTime(log.getOperationTime() != null ? log.getOperationTime().format(FORMATTER) : "");
        vo.setIp(log.getIpAddress() != null ? log.getIpAddress() : "");
        vo.setContent(log.getRemarks() != null ? log.getRemarks() : "");
        vo.setResult(mapResult(log.getStatus()));
        vo.setLogType(mapLogType(log.getOperationType()));
        return vo;
    }

    public static String mapOperateType(String code) {
        if (code == null) {
            return "其他";
        }
        return switch (code) {
            case "1", "2", "3", "4" -> "用户管理";
            case "5", "6" -> "登录";
            default -> code;
        };
    }

    public static String mapResult(String status) {
        return status != null && "SUCCESS".equalsIgnoreCase(status) ? "success" : "failed";
    }

    public static String mapLogType(String operationType) {
        return "5".equals(operationType) || "6".equals(operationType) ? "system" : "operation";
    }

    public static List<String> resolveOperationTypeCodes(String operateType) {
        if (operateType == null || operateType.isBlank()) {
            return null;
        }
        String key = operateType.trim();
        return switch (key) {
            case "登录", "LOGIN" -> List.of("5", "6");
            case "登出", "退出", "LOGOUT" -> List.of("6");
            case "用户管理" -> List.of("1", "2", "3", "4");
            default -> List.of(key);
        };
    }
}
