package com.tss.platform.module1.service;

import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.common.AuditResult;
import com.tss.platform.module1.dto.AuditRecordCommand;

/**
 * 统一操作记录写入入口。业务模块不得直接拼接 SQL 写审计表。
 */
public interface AuditRecordService {

    /**
     * 写入一条操作记录。写入失败只记系统错误日志，不抛出业务异常。
     */
    void record(AuditRecordCommand command);

    default void recordSuccess(AuditActionType action, AuditObjectType objectType, String objectId, String detail) {
        record(AuditRecordCommand.builder()
                .actionType(action)
                .objectType(objectType)
                .objectId(objectId)
                .result(AuditResult.SUCCESS)
                .detail(detail)
                .build());
    }

    default void recordFailed(AuditActionType action, AuditObjectType objectType, String objectId,
                              String failReason, String detail) {
        record(AuditRecordCommand.builder()
                .actionType(action)
                .objectType(objectType)
                .objectId(objectId)
                .result(AuditResult.FAILED)
                .failReason(failReason)
                .detail(detail)
                .build());
    }
}
