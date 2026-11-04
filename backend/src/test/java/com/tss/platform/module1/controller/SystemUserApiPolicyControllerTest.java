package com.tss.platform.module1.controller;

import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.dto.UserApiPolicyUpdateRequest;
import com.tss.platform.module1.security.UserAdministrationForbiddenException;
import com.tss.platform.module1.security.UserAdministrationPolicy;
import com.tss.platform.module1.security.UserApiFeatureGroup;
import com.tss.platform.module1.security.UserApiPolicyDecision;
import com.tss.platform.module1.security.UserApiPolicyService;
import com.tss.platform.module1.service.AuditRecordService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemUserApiPolicyControllerTest {

    @Test
    void normalAdministratorCannotReadOrModifyUserApiPolicies() {
        Fixture fixture = fixture();
        doThrow(new UserAdministrationForbiddenException("仅超级管理员可操作"))
                .when(fixture.administrationPolicy).requireSuperAdministrator();

        var response = fixture.controller.list(7);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(fixture.policyService, never()).listForTarget(7);
    }

    @Test
    void updatePersistsExplicitPolicyAndWritesPermissionAudit() {
        Fixture fixture = fixture();
        when(fixture.administrationPolicy.currentUserId()).thenReturn(1);
        UserApiPolicyDecision before = UserApiPolicyDecision.inherited(
                7, UserApiFeatureGroup.TRAINING_TASK);
        UserApiPolicyDecision saved = UserApiPolicyDecision.explicit(
                7, UserApiFeatureGroup.TRAINING_TASK, false, 2, 0L, null);
        when(fixture.policyService.resolve(7, UserApiFeatureGroup.TRAINING_TASK))
                .thenReturn(before);
        when(fixture.policyService.save(
                7, UserApiFeatureGroup.TRAINING_TASK, false, 2, null, 1))
                .thenReturn(saved);
        UserApiPolicyUpdateRequest request = new UserApiPolicyUpdateRequest();
        request.setEnabled(false);
        request.setMaxConcurrentRequests(2);

        var response = fixture.controller.update(7, "training_task", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().getData()).isEqualTo(saved);
        verify(fixture.auditRecordService).recordSuccess(
                AuditActionType.PERMISSION_CHANGE,
                AuditObjectType.API_POLICY,
                "user=7,group=TRAINING_TASK",
                "API_POLICY_UPDATE:from=INHERIT(enabled=true,maxConcurrentRequests=null)"
                        + ",to=EXPLICIT(enabled=false,maxConcurrentRequests=2)"
        );
    }

    @Test
    void listReturnsAllSixStableGroupsIncludingInheritedDefaults() {
        Fixture fixture = fixture();
        List<UserApiPolicyDecision> policies = java.util.Arrays.stream(UserApiFeatureGroup.values())
                .map(group -> UserApiPolicyDecision.inherited(7, group))
                .toList();
        when(fixture.policyService.listForTarget(7)).thenReturn(policies);

        var response = fixture.controller.list(7);

        assertThat(response.getBody().getData()).hasSize(6);
        assertThat(response.getBody().getData()).allMatch(UserApiPolicyDecision::inherited);
    }

    @Test
    void resetWritesTheExplicitToInheritedTransitionToAudit() {
        Fixture fixture = fixture();
        UserApiPolicyDecision before = UserApiPolicyDecision.explicit(
                7, UserApiFeatureGroup.MODEL_ASSET, false, 1, 2L, null);
        UserApiPolicyDecision after = UserApiPolicyDecision.inherited(
                7, UserApiFeatureGroup.MODEL_ASSET);
        when(fixture.policyService.resolve(7, UserApiFeatureGroup.MODEL_ASSET))
                .thenReturn(before);
        when(fixture.policyService.reset(7, UserApiFeatureGroup.MODEL_ASSET, 2L))
                .thenReturn(after);

        var response = fixture.controller.reset(7, "MODEL_ASSET", 2L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(fixture.auditRecordService).recordSuccess(
                AuditActionType.PERMISSION_CHANGE,
                AuditObjectType.API_POLICY,
                "user=7,group=MODEL_ASSET",
                "API_POLICY_RESET:from=EXPLICIT(enabled=false,maxConcurrentRequests=1)"
                        + ",to=INHERIT(enabled=true,maxConcurrentRequests=null)"
        );
    }

    private static Fixture fixture() {
        UserAdministrationPolicy administrationPolicy = mock(UserAdministrationPolicy.class);
        UserApiPolicyService policyService = mock(UserApiPolicyService.class);
        AuditRecordService auditRecordService = mock(AuditRecordService.class);
        return new Fixture(
                new SystemUserApiPolicyController(administrationPolicy, policyService, auditRecordService),
                administrationPolicy,
                policyService,
                auditRecordService
        );
    }

    private record Fixture(
            SystemUserApiPolicyController controller,
            UserAdministrationPolicy administrationPolicy,
            UserApiPolicyService policyService,
            AuditRecordService auditRecordService
    ) {
    }
}
