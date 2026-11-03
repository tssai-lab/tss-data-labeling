package com.tss.platform.service;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.dto.KubernetesResourcePolicyDto;
import com.tss.platform.dto.KubernetesResourcePolicyUpdateRequest;
import com.tss.platform.entity.PlatformSystemConfig;
import com.tss.platform.module1.entity.OperationLog;
import com.tss.platform.module1.service.OperationLogService;
import com.tss.platform.repository.PlatformSystemConfigRepository;
import com.tss.platform.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

@Service
public class KubernetesResourcePolicyService {

    private static final Logger LOG = LoggerFactory.getLogger(KubernetesResourcePolicyService.class);
    private static final int MAX_AUDIT_ERROR_LENGTH = 180;

    private final PlatformSystemConfigRepository repository;
    private final AuthContext authContext;
    private final KubernetesResourceQuotaClient quotaClient;
    private final TrainingKubernetesProperties properties;
    private final OperationLogService operationLogService;
    private final TransactionTemplate transactionTemplate;

    public KubernetesResourcePolicyService(
            PlatformSystemConfigRepository repository,
            AuthContext authContext,
            KubernetesResourceQuotaClient quotaClient,
            TrainingKubernetesProperties properties,
            OperationLogService operationLogService,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.authContext = authContext;
        this.quotaClient = quotaClient;
        this.properties = properties;
        this.operationLogService = operationLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public KubernetesResourcePolicyDto getForSuperAdministration() {
        requireSuperAdministratorAuthority();
        PlatformSystemConfig config = repository.findById(PlatformSystemConfig.GLOBAL_ID).orElse(null);
        KubernetesResourceQuotaClient.QuotaSnapshot actual = quotaClient.read();
        return toDto(actual, ttlFrom(config), config == null ? null : config.getUpdatedAt());
    }

    public KubernetesResourcePolicyDto updateForSuperAdministration(
            KubernetesResourcePolicyUpdateRequest request
    ) {
        requireSuperAdministratorAuthority();
        ValidatedPolicy requested = validate(request);
        Integer operatorUserId = authContext.currentUserId();
        String operatorUsername = authContext.currentUsername();
        ChangeState state = new ChangeState(requested);

        try {
            KubernetesResourcePolicyDto result = transactionTemplate.execute(status -> {
                Instant now = Instant.now();
                PlatformSystemConfig config = repository
                        .findByIdForUpdate(PlatformSystemConfig.GLOBAL_ID)
                        .orElseGet(() -> newConfig(now));
                state.beforeTtl = ttlFrom(config);
                KubernetesResourceQuotaClient.QuotaSnapshot before = quotaClient.read();
                state.before = before;
                rejectBelowCurrentUsage(requested, before);

                boolean quotaChanged = before.podQuota() != requested.podQuota()
                        || before.jobQuota() != requested.jobQuota();
                KubernetesResourceQuotaClient.QuotaSnapshot after = before;
                if (quotaChanged) {
                    state.quotaWriteAttempted = true;
                    after = quotaClient.writeAndReadBack(requested.podQuota(), requested.jobQuota());
                }

                boolean databaseChanged = !Integer.valueOf(requested.podQuota()).equals(config.getPodQuota())
                        || !Integer.valueOf(requested.jobQuota()).equals(config.getJobQuota())
                        || !Integer.valueOf(requested.jobTtlSecondsAfterFinished())
                        .equals(config.getJobTtlSecondsAfterFinished());
                if (databaseChanged) {
                    config.setPodQuota(requested.podQuota());
                    config.setJobQuota(requested.jobQuota());
                    config.setJobTtlSecondsAfterFinished(requested.jobTtlSecondsAfterFinished());
                    config.setUpdatedByUserId(operatorUserId);
                    config.setUpdatedAt(now);
                    repository.saveAndFlush(config);
                }
                return toDto(after, requested.jobTtlSecondsAfterFinished(), config.getUpdatedAt());
            });
            if (result == null) {
                throw new IllegalStateException("资源策略数据库事务未返回结果");
            }
            LOG.info(
                    "Kubernetes resource policy updated: cluster={}, namespace={}, before={}, requested={}, operatorUserId={}",
                    properties.getClusterName(), properties.getNamespace(), state.before, requested, operatorUserId
            );
            recordAudit(operatorUserId, operatorUsername, "SUCCESS", auditDetails(state, null));
            return result;
        } catch (RuntimeException exception) {
            RuntimeException reported = exception;
            boolean restored = false;
            if (state.quotaWriteAttempted && state.before != null) {
                try {
                    quotaClient.writeAndReadBack(state.before.podQuota(), state.before.jobQuota());
                    restored = true;
                } catch (RuntimeException rollbackException) {
                    LOG.error(
                            "Kubernetes ResourceQuota rollback failed: cluster={}, namespace={}, before={}, error={}",
                            properties.getClusterName(), properties.getNamespace(), state.before,
                            rollbackException.getMessage(), rollbackException
                    );
                    reported = new IllegalStateException(
                            "资源策略更新失败，且 Kubernetes 配额自动恢复失败；请立即检查集群实际值",
                            exception
                    );
                }
            }
            state.restored = restored;
            LOG.warn(
                    "Kubernetes resource policy update failed: cluster={}, namespace={}, before={}, requested={}, restored={}, operatorUserId={}, error={}",
                    properties.getClusterName(), properties.getNamespace(), state.before, requested,
                    restored, operatorUserId, exception.getMessage()
            );
            recordAudit(operatorUserId, operatorUsername, "FAIL", auditDetails(state, exception));
            throw reported;
        }
    }

    private KubernetesResourcePolicyDto toDto(
            KubernetesResourceQuotaClient.QuotaSnapshot quota,
            int ttl,
            Instant updatedAt
    ) {
        return new KubernetesResourcePolicyDto(
                quota.podQuota(),
                quota.jobQuota(),
                ttl,
                quota.usedPods(),
                quota.usedJobs(),
                properties.getClusterName(),
                properties.getNamespace(),
                updatedAt
        );
    }

    private static ValidatedPolicy validate(KubernetesResourcePolicyUpdateRequest request) {
        if (request == null
                || request.podQuota() == null
                || request.jobQuota() == null
                || request.jobTtlSecondsAfterFinished() == null) {
            throw new IllegalArgumentException("Pod 配额、Job 配额和 Job TTL 均不能为空");
        }
        requireRange(
                "Pod 配额", request.podQuota(),
                PlatformSystemConfig.MIN_POD_QUOTA, PlatformSystemConfig.MAX_POD_QUOTA
        );
        requireRange(
                "Job 配额", request.jobQuota(),
                PlatformSystemConfig.MIN_JOB_QUOTA, PlatformSystemConfig.MAX_JOB_QUOTA
        );
        requireRange(
                "Job TTL", request.jobTtlSecondsAfterFinished(),
                PlatformSystemConfig.MIN_JOB_TTL_SECONDS_AFTER_FINISHED,
                PlatformSystemConfig.MAX_JOB_TTL_SECONDS_AFTER_FINISHED
        );
        return new ValidatedPolicy(
                request.podQuota(), request.jobQuota(), request.jobTtlSecondsAfterFinished()
        );
    }

    private static void requireRange(String label, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    label + "必须在 " + minimum + " 到 " + maximum + " 之间"
            );
        }
    }

    private static void rejectBelowCurrentUsage(
            ValidatedPolicy requested,
            KubernetesResourceQuotaClient.QuotaSnapshot current
    ) {
        if (requested.podQuota() < current.usedPods()) {
            throw new IllegalArgumentException(
                    "Pod 配额不能低于当前占用 " + current.usedPods()
            );
        }
        if (requested.jobQuota() < current.usedJobs()) {
            throw new IllegalArgumentException(
                    "Job 配额不能低于当前占用 " + current.usedJobs()
            );
        }
    }

    private int ttlFrom(PlatformSystemConfig config) {
        Integer stored = config == null ? null : config.getJobTtlSecondsAfterFinished();
        if (stored != null
                && stored >= PlatformSystemConfig.MIN_JOB_TTL_SECONDS_AFTER_FINISHED
                && stored <= PlatformSystemConfig.MAX_JOB_TTL_SECONDS_AFTER_FINISHED) {
            return stored;
        }
        int configured = properties.getJobTtlSecondsAfterFinished();
        return configured >= PlatformSystemConfig.MIN_JOB_TTL_SECONDS_AFTER_FINISHED
                && configured <= PlatformSystemConfig.MAX_JOB_TTL_SECONDS_AFTER_FINISHED
                ? configured
                : PlatformSystemConfig.DEFAULT_JOB_TTL_SECONDS_AFTER_FINISHED;
    }

    private void requireSuperAdministratorAuthority() {
        try {
            if (authContext.isSuperAdmin()) {
                return;
            }
        } catch (RuntimeException ignored) {
        }
        throw new CodeApprovalForbiddenException();
    }

    private static PlatformSystemConfig newConfig(Instant now) {
        PlatformSystemConfig config = new PlatformSystemConfig();
        config.setId(PlatformSystemConfig.GLOBAL_ID);
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        return config;
    }

    private void recordAudit(
            Integer operatorUserId,
            String operatorUsername,
            String status,
            String remarks
    ) {
        try {
            OperationLog operationLog = new OperationLog();
            operationLog.setUserId(operatorUserId);
            operationLog.setUserName(operatorUsername);
            operationLog.setOperationType("3");
            operationLog.setOperationObj("kubernetes_resource_policy");
            operationLog.setRemarks(remarks);
            operationLog.setStatus(status);
            if (!operationLogService.recordLog(operationLog)) {
                LOG.warn("Resource policy audit log was not persisted: operatorUserId={}", operatorUserId);
            }
        } catch (RuntimeException exception) {
            LOG.warn("Failed to persist resource policy audit log: {}", exception.getMessage());
        }
    }

    private String auditDetails(ChangeState state, RuntimeException exception) {
        String details = "cluster=" + properties.getClusterName()
                + ", namespace=" + properties.getNamespace()
                + ", before=" + state.before
                + ", beforeTtl=" + state.beforeTtl
                + ", requested=" + state.requested
                + ", restored=" + state.restored;
        if (exception == null) {
            return details;
        }
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        if (message.length() > MAX_AUDIT_ERROR_LENGTH) {
            message = message.substring(0, MAX_AUDIT_ERROR_LENGTH) + "...";
        }
        return details + ", error=" + message;
    }

    private record ValidatedPolicy(
            int podQuota,
            int jobQuota,
            int jobTtlSecondsAfterFinished
    ) {
    }

    private static final class ChangeState {
        private final ValidatedPolicy requested;
        private KubernetesResourceQuotaClient.QuotaSnapshot before;
        private int beforeTtl;
        private boolean quotaWriteAttempted;
        private boolean restored;

        private ChangeState(ValidatedPolicy requested) {
            this.requested = requested;
        }
    }
}
