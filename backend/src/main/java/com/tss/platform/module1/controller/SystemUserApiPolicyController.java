package com.tss.platform.module1.controller;

import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.common.Result;
import com.tss.platform.module1.dto.UserApiPolicyUpdateRequest;
import com.tss.platform.module1.security.UserAdministrationForbiddenException;
import com.tss.platform.module1.security.UserAdministrationPolicy;
import com.tss.platform.module1.security.UserApiFeatureGroup;
import com.tss.platform.module1.security.UserApiPolicyConflictException;
import com.tss.platform.module1.security.UserApiPolicyDecision;
import com.tss.platform.module1.security.UserApiPolicyService;
import com.tss.platform.module1.service.AuditRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/system/user/{userId}/api-policies")
public class SystemUserApiPolicyController {

    private final UserAdministrationPolicy administrationPolicy;
    private final UserApiPolicyService policyService;
    private final AuditRecordService auditRecordService;

    public SystemUserApiPolicyController(
            UserAdministrationPolicy administrationPolicy,
            UserApiPolicyService policyService,
            AuditRecordService auditRecordService
    ) {
        this.administrationPolicy = administrationPolicy;
        this.policyService = policyService;
        this.auditRecordService = auditRecordService;
    }

    @GetMapping
    public ResponseEntity<Result<List<UserApiPolicyDecision>>> list(@PathVariable Integer userId) {
        try {
            administrationPolicy.requireSuperAdministrator();
            return ResponseEntity.ok(Result.success(policyService.listForTarget(userId), "查询成功"));
        } catch (UserAdministrationForbiddenException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.noAuth(exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Result.fail(exception.getMessage()));
        }
    }

    @PutMapping("/{featureGroup}")
    public ResponseEntity<Result<UserApiPolicyDecision>> update(
            @PathVariable Integer userId,
            @PathVariable String featureGroup,
            @RequestBody UserApiPolicyUpdateRequest request
    ) {
        UserApiFeatureGroup group = null;
        try {
            administrationPolicy.requireSuperAdministrator();
            group = UserApiFeatureGroup.parse(featureGroup);
            if (request == null || request.getEnabled() == null) {
                throw new IllegalArgumentException("enabled 不能为空");
            }
            UserApiPolicyDecision before = policyService.resolve(userId, group);
            UserApiPolicyDecision decision = policyService.save(
                    userId,
                    group,
                    request.getEnabled(),
                    request.getMaxConcurrentRequests(),
                    request.getVersion(),
                    administrationPolicy.currentUserId()
            );
            auditRecordService.recordSuccess(
                    AuditActionType.PERMISSION_CHANGE,
                    AuditObjectType.API_POLICY,
                    objectId(userId, group),
                    "API_POLICY_UPDATE:from=" + auditValue(before)
                            + ",to=" + auditValue(decision)
            );
            return ResponseEntity.ok(Result.success(decision, "策略更新成功"));
        } catch (UserAdministrationForbiddenException exception) {
            recordFailed(userId, group, exception.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.noAuth(exception.getMessage()));
        } catch (UserApiPolicyConflictException exception) {
            recordFailed(userId, group, exception.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.fail(exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            recordFailed(userId, group, exception.getMessage());
            return ResponseEntity.badRequest().body(Result.fail(exception.getMessage()));
        }
    }

    @DeleteMapping("/{featureGroup}")
    public ResponseEntity<Result<UserApiPolicyDecision>> reset(
            @PathVariable Integer userId,
            @PathVariable String featureGroup,
            @RequestParam(required = false) Long version
    ) {
        UserApiFeatureGroup group = null;
        try {
            administrationPolicy.requireSuperAdministrator();
            group = UserApiFeatureGroup.parse(featureGroup);
            UserApiPolicyDecision before = policyService.resolve(userId, group);
            UserApiPolicyDecision decision = policyService.reset(userId, group, version);
            auditRecordService.recordSuccess(
                    AuditActionType.PERMISSION_CHANGE,
                    AuditObjectType.API_POLICY,
                    objectId(userId, group),
                    "API_POLICY_RESET:from=" + auditValue(before)
                            + ",to=" + auditValue(decision)
            );
            return ResponseEntity.ok(Result.success(decision, "已恢复默认策略"));
        } catch (UserAdministrationForbiddenException exception) {
            recordFailed(userId, group, exception.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.noAuth(exception.getMessage()));
        } catch (UserApiPolicyConflictException exception) {
            recordFailed(userId, group, exception.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.fail(exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            recordFailed(userId, group, exception.getMessage());
            return ResponseEntity.badRequest().body(Result.fail(exception.getMessage()));
        }
    }

    private void recordFailed(Integer userId, UserApiFeatureGroup group, String reason) {
        auditRecordService.recordFailed(
                AuditActionType.PERMISSION_CHANGE,
                AuditObjectType.API_POLICY,
                objectId(userId, group),
                reason,
                "API_POLICY_CHANGE_FAILED"
        );
    }

    private static String objectId(Integer userId, UserApiFeatureGroup group) {
        return "user=" + userId + ",group=" + (group == null ? "UNKNOWN" : group.name());
    }

    private static String auditValue(UserApiPolicyDecision decision) {
        String source = decision.inherited() ? "INHERIT" : "EXPLICIT";
        return source + "(enabled=" + decision.enabled()
                + ",maxConcurrentRequests=" + decision.maxConcurrentRequests() + ")";
    }
}
