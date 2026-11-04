package com.tss.platform.module1.service;

import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.common.AuditResult;
import com.tss.platform.module1.dto.AuditRecordCommand;
import com.tss.platform.module1.entity.AuditRecord;
import com.tss.platform.module1.mapper.AuditRecordMapper;
import com.tss.platform.module1.service.impl.AuditRecordServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditRecordServiceImplTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void fillsRequestIpAndRequestIdWithoutTrustingCallerToSupplyThem() {
        AuditRecordMapper mapper = mock(AuditRecordMapper.class);
        when(mapper.insert(any())).thenReturn(1);
        AuditRecordServiceImpl service = service(mapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        request.addHeader("X-Request-ID", "request-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.record(AuditRecordCommand.builder()
                .actionType(AuditActionType.UPLOAD)
                .objectType(AuditObjectType.MODEL)
                .objectId("model-1")
                .result(AuditResult.SUCCESS)
                .detail("MODEL_UPLOAD")
                .fallbackUsername("alice")
                .build());

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(mapper).insert(captor.capture());
        AuditRecord saved = captor.getValue();
        assertThat(saved.getIpAddress()).isEqualTo("203.0.113.9");
        assertThat(saved.getRequestId()).isEqualTo("request-123");
        assertThat(saved.getUsername()).isEqualTo("alice");
    }

    @Test
    void auditStorageFailureDoesNotBreakTheBusinessRequest() {
        AuditRecordMapper mapper = mock(AuditRecordMapper.class);
        when(mapper.insert(any())).thenThrow(new IllegalStateException("database unavailable"));
        AuditRecordServiceImpl service = service(mapper);

        assertThatCode(() -> service.record(AuditRecordCommand.builder()
                .actionType(AuditActionType.INFERENCE)
                .objectType(AuditObjectType.INFERENCE_TASK)
                .result(AuditResult.FAILED)
                .failReason("runtime error")
                .fallbackUsername("alice")
                .build()))
                .doesNotThrowAnyException();
    }

    private static AuditRecordServiceImpl service(AuditRecordMapper mapper) {
        AuditRecordServiceImpl service = new AuditRecordServiceImpl();
        ReflectionTestUtils.setField(service, "auditRecordMapper", mapper);
        return service;
    }
}
