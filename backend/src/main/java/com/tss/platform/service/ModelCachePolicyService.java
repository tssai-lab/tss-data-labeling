package com.tss.platform.service;

import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.dto.modelcache.ModelCacheDtos;
import com.tss.platform.entity.PlatformSystemConfig;
import com.tss.platform.modelcache.ModelCachePolicy;
import com.tss.platform.module1.entity.OperationLog;
import com.tss.platform.module1.service.OperationLogService;
import com.tss.platform.repository.PlatformSystemConfigRepository;
import com.tss.platform.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Service
public class ModelCachePolicyService {

    private static final Logger LOG = LoggerFactory.getLogger(ModelCachePolicyService.class);
    private static final int MAX_AUDIT_ERROR_LENGTH = 180;

    private final PlatformSystemConfigRepository repository;
    private final InferenceModelCacheProperties properties;
    private final AuthContext authContext;
    private final ModelCacheAdministrationService administrationService;
    private final ModelCachePolicyKubernetesClient kubernetesClient;
    private final OperationLogService operationLogService;
    private final TransactionTemplate transactionTemplate;

    public ModelCachePolicyService(
            PlatformSystemConfigRepository repository,
            InferenceModelCacheProperties properties,
            AuthContext authContext,
            ModelCacheAdministrationService administrationService,
            ModelCachePolicyKubernetesClient kubernetesClient,
            OperationLogService operationLogService,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.properties = properties;
        this.authContext = authContext;
        this.administrationService = administrationService;
        this.kubernetesClient = kubernetesClient;
        this.operationLogService = operationLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public ModelCachePolicy currentPolicy() {
        return repository.findById(PlatformSystemConfig.GLOBAL_ID)
                .map(this::fromConfig)
                .orElseGet(this::fallbackPolicy);
    }

    public ModelCachePolicy getForSuperAdministration() {
        requireSuperAdministratorAuthority();
        return currentPolicy();
    }

    public ModelCachePolicy updateForSuperAdministration(
            ModelCacheDtos.PolicyUpdateRequest request
    ) {
        requireSuperAdministratorAuthority();
        ModelCachePolicy requested = validate(request);
        Integer operatorUserId = authContext.currentUserId();
        String operatorUsername = authContext.currentUsername();
        ChangeState state = new ChangeState(requested);

        try {
            ModelCachePolicy result = transactionTemplate.execute(status -> {
                Instant now = Instant.now();
                PlatformSystemConfig config = repository
                        .findByIdForUpdate(PlatformSystemConfig.GLOBAL_ID)
                        .orElseGet(() -> newConfig(now));
                state.beforePolicy = fromConfig(config);

                List<ModelCacheDtos.Node> nodes = administrationService.inspectPolicyNodes();
                validateNodes(requested, nodes);
                state.validatedNodeCount = nodes.size();

                state.beforeKubernetes = kubernetesClient.read();
                state.kubernetesWriteAttempted = true;
                kubernetesClient.writeAndReadBack(requested);

                boolean databaseChanged = config.getModelCacheMaxBytes() != requested.maxBytes()
                        || config.getModelCacheMinFreeBytes() != requested.minFreeBytes()
                        || config.getModelCacheRuntimeReserveBytes() != requested.runtimeReserveBytes();
                if (databaseChanged) {
                    config.setModelCacheMaxBytes(requested.maxBytes());
                    config.setModelCacheMinFreeBytes(requested.minFreeBytes());
                    config.setModelCacheRuntimeReserveBytes(requested.runtimeReserveBytes());
                    config.setUpdatedByUserId(operatorUserId);
                    config.setUpdatedAt(now);
                    repository.saveAndFlush(config);
                }
                return fromConfig(config);
            });
            if (result == null) {
                throw new IllegalStateException("模型缓存策略数据库事务未返回结果");
            }
            LOG.info(
                    "Model cache policy updated: before={}, requested={}, validatedNodes={}, operatorUserId={}",
                    state.beforePolicy, requested, state.validatedNodeCount, operatorUserId
            );
            recordAudit(operatorUserId, operatorUsername, "SUCCESS", auditDetails(state, null));
            return result;
        } catch (RuntimeException exception) {
            RuntimeException reported = exception;
            boolean restored = false;
            if (state.kubernetesWriteAttempted) {
                try {
                    kubernetesClient.restore(state.beforeKubernetes);
                    restored = true;
                } catch (RuntimeException rollbackException) {
                    LOG.error(
                            "Model cache policy ConfigMap rollback failed: before={}, error={}",
                            state.beforeKubernetes, rollbackException.getMessage(), rollbackException
                    );
                    reported = new IllegalStateException(
                            "模型缓存策略更新失败，且 Kubernetes 配置自动恢复失败；请立即检查集群实际值",
                            exception
                    );
                }
            }
            state.restored = restored;
            LOG.warn(
                    "Model cache policy update failed: before={}, requested={}, restored={}, operatorUserId={}, error={}",
                    state.beforePolicy, requested, restored, operatorUserId, exception.getMessage()
            );
            recordAudit(operatorUserId, operatorUsername, "FAIL", auditDetails(state, exception));
            throw reported;
        }
    }

    private ModelCachePolicy validate(ModelCacheDtos.PolicyUpdateRequest request) {
        if (request == null
                || request.maxBytes() == null
                || request.minFreeBytes() == null
                || request.runtimeReserveBytes() == null) {
            throw new IllegalArgumentException("缓存上限、最低空闲空间和运行镜像预留均不能为空");
        }
        requireGiBRange("缓存上限", request.maxBytes());
        requireGiBRange("最低空闲空间", request.minFreeBytes());
        requireGiBRange("运行镜像预留", request.runtimeReserveBytes());
        try {
            return new ModelCachePolicy(
                    request.maxBytes(),
                    request.minFreeBytes(),
                    request.runtimeReserveBytes(),
                    null
            );
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("模型缓存保护门数值总和过大", exception);
        }
    }

    private static void requireGiBRange(String label, long value) {
        if (value < PlatformSystemConfig.MIN_MODEL_CACHE_POLICY_BYTES
                || value > PlatformSystemConfig.MAX_MODEL_CACHE_POLICY_BYTES
                || value % PlatformSystemConfig.GIB != 0) {
            throw new IllegalArgumentException(label + "必须是 1 到 1024 GiB 之间的整数 GiB");
        }
    }

    private static void validateNodes(ModelCachePolicy requested, List<ModelCacheDtos.Node> nodes) {
        for (ModelCacheDtos.Node node : nodes) {
            String label = node.k8sNodeName() == null || node.k8sNodeName().isBlank()
                    ? node.serverIp() : node.k8sNodeName();
            if (node.error() != null && !node.error().isBlank()) {
                throw new IllegalStateException("无法验证缓存节点 " + label + "：" + node.error());
            }
            if (node.usedBytes() > requested.maxBytes()) {
                throw new IllegalArgumentException(
                        "节点 " + label + " 当前缓存占用 " + node.usedBytes()
                                + " 字节，高于新缓存上限；请先清理缓存"
                );
            }
            long required = requested.requiredAvailableBytes(node.usedBytes());
            if (node.diskFreeBytes() < required) {
                throw new IllegalArgumentException(
                        "节点 " + label + " 磁盘不足：当前可用 " + node.diskFreeBytes()
                                + " 字节，新保护门需要 " + required + " 字节"
                );
            }
        }
    }

    private ModelCachePolicy fromConfig(PlatformSystemConfig config) {
        return new ModelCachePolicy(
                normalized(
                        config.getModelCacheMaxBytes(),
                        PlatformSystemConfig.DEFAULT_MODEL_CACHE_MAX_BYTES
                ),
                normalized(
                        config.getModelCacheMinFreeBytes(),
                        PlatformSystemConfig.DEFAULT_MODEL_CACHE_MIN_FREE_BYTES
                ),
                normalized(
                        config.getModelCacheRuntimeReserveBytes(),
                        PlatformSystemConfig.DEFAULT_MODEL_CACHE_RUNTIME_RESERVE_BYTES
                ),
                config.getUpdatedAt()
        );
    }

    private ModelCachePolicy fallbackPolicy() {
        return new ModelCachePolicy(
                normalized(properties.getMaxBytes(), PlatformSystemConfig.DEFAULT_MODEL_CACHE_MAX_BYTES),
                normalized(properties.getMinFreeBytes(), PlatformSystemConfig.DEFAULT_MODEL_CACHE_MIN_FREE_BYTES),
                normalized(
                        properties.getRuntimeReserveBytes(),
                        PlatformSystemConfig.DEFAULT_MODEL_CACHE_RUNTIME_RESERVE_BYTES
                ),
                null
        );
    }

    private static long normalized(long value, long fallback) {
        if (value < PlatformSystemConfig.MIN_MODEL_CACHE_POLICY_BYTES
                || value > PlatformSystemConfig.MAX_MODEL_CACHE_POLICY_BYTES
                || value % PlatformSystemConfig.GIB != 0) {
            return fallback;
        }
        return value;
    }

    private static PlatformSystemConfig newConfig(Instant now) {
        PlatformSystemConfig config = new PlatformSystemConfig();
        config.setId(PlatformSystemConfig.GLOBAL_ID);
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        return config;
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
            operationLog.setOperationObj("model_cache_disk_policy");
            operationLog.setRemarks(remarks);
            operationLog.setStatus(status);
            if (!operationLogService.recordLog(operationLog)) {
                LOG.warn("Model cache policy audit log was not persisted: operatorUserId={}", operatorUserId);
            }
        } catch (RuntimeException exception) {
            LOG.warn("Failed to persist model cache policy audit log: {}", exception.getMessage());
        }
    }

    private static String auditDetails(ChangeState state, RuntimeException exception) {
        String details = "before=" + state.beforePolicy
                + ", requested=" + state.requested
                + ", validatedNodes=" + state.validatedNodeCount
                + ", restored=" + state.restored;
        if (exception == null) {
            return details;
        }
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
        if (message.length() > MAX_AUDIT_ERROR_LENGTH) {
            message = message.substring(0, MAX_AUDIT_ERROR_LENGTH) + "...";
        }
        return details + ", error=" + message;
    }

    private static final class ChangeState {
        private final ModelCachePolicy requested;
        private ModelCachePolicy beforePolicy;
        private ModelCachePolicyKubernetesClient.PolicySnapshot beforeKubernetes;
        private int validatedNodeCount;
        private boolean kubernetesWriteAttempted;
        private boolean restored;

        private ChangeState(ModelCachePolicy requested) {
            this.requested = requested;
        }
    }
}
