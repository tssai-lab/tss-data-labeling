package com.tss.platform.module1.service;

import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.common.AuditResult;
import com.tss.platform.module1.dto.AuditRecordCommand;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 供模型/数据集/训练/推理等模块显式调用的审计钩子。
 * 当前 demo 工程尚未包含这些业务入口；接入时直接调用对应方法即可。
 *
 * <pre>
 * // 上传模型成功
 * auditHooks.upload(AuditObjectType.MODEL, modelId, "MODEL_UPLOAD", true, null);
 * // 提交训练
 * auditHooks.train(taskId, "TRAIN_CREATE", true, null);
 * // 提交推理
 * auditHooks.inference(taskId, "INFERENCE_CREATE", true, null);
 * </pre>
 */
@Component
public class AuditHooks {

    @Resource
    private AuditRecordService auditRecordService;

    public void upload(AuditObjectType objectType, String objectId, String detail,
                       boolean success, String failReason) {
        write(AuditActionType.UPLOAD, objectType, objectId, detail, success, failReason);
    }

    public void delete(AuditObjectType objectType, String objectId, String detail,
                       boolean success, String failReason) {
        write(AuditActionType.DELETE, objectType, objectId, detail, success, failReason);
    }

    public void train(String objectId, String detail, boolean success, String failReason) {
        write(AuditActionType.TRAIN, AuditObjectType.TRAIN_TASK, objectId, detail, success, failReason);
    }

    public void inference(String objectId, String detail, boolean success, String failReason) {
        write(AuditActionType.INFERENCE, AuditObjectType.INFERENCE_TASK, objectId, detail, success, failReason);
    }

    private void write(AuditActionType action, AuditObjectType objectType, String objectId,
                       String detail, boolean success, String failReason) {
        auditRecordService.record(AuditRecordCommand.builder()
                .actionType(action)
                .objectType(objectType)
                .objectId(objectId)
                .result(success ? AuditResult.SUCCESS : AuditResult.FAILED)
                .failReason(failReason)
                .detail(detail)
                .build());
    }
}
