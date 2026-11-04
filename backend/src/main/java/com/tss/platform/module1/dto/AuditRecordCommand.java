package com.tss.platform.module1.dto;

import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.common.AuditResult;
import lombok.Builder;
import lombok.Data;

/**
 * 内部审计写入命令。不允许接收前端伪造的操作者与结果作为可信来源；
 * 服务层会用 Sa-Token 会话覆盖操作者字段（登录失败场景除外）。
 */
@Data
@Builder
public class AuditRecordCommand {
    private AuditActionType actionType;
    private AuditObjectType objectType;
    private String objectId;
    private AuditResult result;
    private String failReason;
    private String detail;
    private String ipAddress;
    private String requestId;

    /** 登录失败等未登录场景可显式传入脱敏后的账号 */
    private Integer fallbackUserId;
    private String fallbackUsername;
}
