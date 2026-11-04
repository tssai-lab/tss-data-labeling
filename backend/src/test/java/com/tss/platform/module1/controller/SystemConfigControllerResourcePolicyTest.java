package com.tss.platform.module1.controller;

import com.tss.platform.dto.KubernetesResourcePolicyDto;
import com.tss.platform.dto.KubernetesResourcePolicyUpdateRequest;
import com.tss.platform.module1.common.Result;
import com.tss.platform.service.CodeApprovalForbiddenException;
import com.tss.platform.service.KubernetesResourcePolicyService;
import com.tss.platform.service.SystemConfigService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemConfigControllerResourcePolicyTest {

    private final KubernetesResourcePolicyService resourcePolicyService =
            mock(KubernetesResourcePolicyService.class);
    private final SystemConfigController controller = new SystemConfigController(
            mock(SystemConfigService.class),
            resourcePolicyService
    );

    @Test
    void returnsActualResourcePolicyFromSuperAdminService() {
        KubernetesResourcePolicyDto dto = new KubernetesResourcePolicyDto(
                10, 20, 180, 1, 2,
                "tss-training", "tss-training",
                Instant.parse("2026-08-13T00:00:00Z")
        );
        when(resourcePolicyService.getForSuperAdministration()).thenReturn(dto);

        Result<KubernetesResourcePolicyDto> result = controller.getResourcePolicy();

        assertEquals(Result.SUCCESS_CODE, result.getCode());
        assertEquals(dto, result.getData());
    }

    @Test
    void mapsPermissionValidationAndInfrastructureFailuresWithoutFakeData() {
        when(resourcePolicyService.getForSuperAdministration())
                .thenThrow(new CodeApprovalForbiddenException());
        assertEquals(Result.NO_AUTH_CODE, controller.getResourcePolicy().getCode());

        KubernetesResourcePolicyUpdateRequest invalid =
                new KubernetesResourcePolicyUpdateRequest(0, 20, 180);
        when(resourcePolicyService.updateForSuperAdministration(invalid))
                .thenThrow(new IllegalArgumentException("invalid quota"));
        Result<KubernetesResourcePolicyDto> invalidResult =
                controller.updateResourcePolicy(invalid);
        assertEquals(Result.FAIL_CODE, invalidResult.getCode());
        assertEquals(null, invalidResult.getData());

        KubernetesResourcePolicyUpdateRequest unavailable =
                new KubernetesResourcePolicyUpdateRequest(10, 20, 180);
        when(resourcePolicyService.updateForSuperAdministration(unavailable))
                .thenThrow(new IllegalStateException("cluster unavailable"));
        Result<KubernetesResourcePolicyDto> unavailableResult =
                controller.updateResourcePolicy(unavailable);
        assertEquals(Result.SERVER_ERROR_CODE, unavailableResult.getCode());
        assertEquals(null, unavailableResult.getData());
    }
}
