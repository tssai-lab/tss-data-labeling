package com.tss.platform.module1.service;

import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.common.AuditResult;
import com.tss.platform.module1.dto.AuditRecordCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AuditHooksTest {

    @Test
    void mapsBusinessHooksToStableActionObjectAndResultValues() {
        AuditRecordService recordService = mock(AuditRecordService.class);
        AuditHooks hooks = new AuditHooks();
        ReflectionTestUtils.setField(hooks, "auditRecordService", recordService);

        hooks.upload(AuditObjectType.MODEL, "model-1", "MODEL_UPLOAD", true, null);
        hooks.upload(AuditObjectType.DATASET, "dataset-1", "DATASET_UPLOAD", false, "bad archive");
        hooks.delete(AuditObjectType.INFERENCE_SCRIPT, "script-1", "SCRIPT_DELETE", true, null);
        hooks.train("train-1", "TRAIN_CREATE", false, "quota exceeded");
        hooks.inference("infer-1", "INFERENCE_RETRY", true, null);

        ArgumentCaptor<AuditRecordCommand> captor = ArgumentCaptor.forClass(AuditRecordCommand.class);
        verify(recordService, times(5)).record(captor.capture());
        List<AuditRecordCommand> commands = captor.getAllValues();

        assertThat(commands).extracting(AuditRecordCommand::getActionType)
                .containsExactly(
                        AuditActionType.UPLOAD,
                        AuditActionType.UPLOAD,
                        AuditActionType.DELETE,
                        AuditActionType.TRAIN,
                        AuditActionType.INFERENCE
                );
        assertThat(commands).extracting(AuditRecordCommand::getObjectType)
                .containsExactly(
                        AuditObjectType.MODEL,
                        AuditObjectType.DATASET,
                        AuditObjectType.INFERENCE_SCRIPT,
                        AuditObjectType.TRAIN_TASK,
                        AuditObjectType.INFERENCE_TASK
                );
        assertThat(commands).extracting(AuditRecordCommand::getResult)
                .containsExactly(
                        AuditResult.SUCCESS,
                        AuditResult.FAILED,
                        AuditResult.SUCCESS,
                        AuditResult.FAILED,
                        AuditResult.SUCCESS
                );
        assertThat(commands.get(1).getFailReason()).isEqualTo("bad archive");
    }
}
