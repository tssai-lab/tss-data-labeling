package com.tss.platform.module1.util;

import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.dto.LogItemVO;
import com.tss.platform.module1.entity.AuditRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRecordConverterTest {

    @Test
    void exposesAllSixContractActionTypes() {
        assertThat(AuditActionType.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                "UPLOAD", "DELETE", "TRAIN", "INFERENCE", "LOGIN", "PERMISSION_CHANGE"
        );
        assertThat(AuditRecordConverter.resolveActionTypes("上传")).containsExactly("UPLOAD");
        assertThat(AuditRecordConverter.resolveActionTypes("DELETE")).containsExactly("DELETE");
        assertThat(AuditRecordConverter.resolveActionTypes("训练")).containsExactly("TRAIN");
        assertThat(AuditRecordConverter.resolveActionTypes("推理")).containsExactly("INFERENCE");
        assertThat(AuditRecordConverter.resolveActionTypes("退出")).containsExactly("LOGIN");
        assertThat(AuditRecordConverter.resolveActionTypes("权限变更")).containsExactly("PERMISSION_CHANGE");
    }

    @Test
    void convertsFailureIntoExistingLogPageShape() {
        AuditRecord record = new AuditRecord();
        record.setId(3_000_000_000L);
        record.setUsername("alice");
        record.setActionType("UPLOAD");
        record.setObjectType("MODEL");
        record.setObjectId("model-1");
        record.setResult("FAILED");
        record.setFailReason("文件损坏");
        record.setDetail("MODEL_UPLOAD_V2");
        record.setIpAddress("10.0.0.8");
        record.setCreatedAt(LocalDateTime.of(2026, 8, 27, 1, 2, 3));

        LogItemVO result = AuditRecordConverter.toVo(record);

        assertThat(result.getId()).isEqualTo(3_000_000_000L);
        assertThat(result.getOperateType()).isEqualTo("UPLOAD");
        assertThat(result.getOperateTime()).isEqualTo("2026-08-27 01:02:03");
        assertThat(result.getContent()).isEqualTo("模型；对象=model-1；MODEL_UPLOAD_V2；失败原因=文件损坏");
        assertThat(result.getResult()).isEqualTo("failed");
        assertThat(result.getLogType()).isEqualTo("operation");
    }

    @Test
    void keepsUnknownFilterExactInsteadOfBroadeningResults() {
        assertThat(AuditRecordConverter.resolveActionTypes("unexpected"))
                .isEqualTo(List.of("UNEXPECTED"));
    }
}
