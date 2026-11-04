package com.tss.platform.module1.controller;

import com.tss.platform.module1.common.Result;
import com.tss.platform.module1.entity.OperationLog;
import com.tss.platform.module1.service.OperationLogService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OperationLogControllerSecurityTest {

    @Test
    void clientCannotForgeAuditRecord() {
        OperationLogService service = mock(OperationLogService.class);
        OperationLogController controller = new OperationLogController();
        ReflectionTestUtils.setField(controller, "logService", service);
        OperationLog forged = new OperationLog();
        forged.setUserId(1);
        forged.setIpAddress("127.0.0.1");
        forged.setStatus("SUCCESS");

        Result<?> result = controller.recordLog(forged);

        assertThat(result.getCode()).isEqualTo(Result.NO_AUTH_CODE);
        verify(service, never()).recordLog(forged);
    }
}
