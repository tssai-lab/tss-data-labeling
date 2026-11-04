package com.tss.platform.service;

import com.tss.platform.dto.SystemConfigDto;
import com.tss.platform.dto.SystemConfigUpdateRequest;
import com.tss.platform.entity.PlatformSystemConfig;
import com.tss.platform.model.TrainingCodeReviewMode;
import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.service.AuditRecordService;
import com.tss.platform.repository.PlatformSystemConfigRepository;
import com.tss.platform.security.AuthContext;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Service
public class SystemConfigService {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigService.class);
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final int ROW_OVERHEAD_BYTES = 64;

    private final PlatformSystemConfigRepository repository;
    private final AuthContext authContext;
    private final EntityManager entityManager;
    private final AuditRecordService auditRecordService;

    public SystemConfigService(
            PlatformSystemConfigRepository repository,
            AuthContext authContext,
            EntityManager entityManager,
            AuditRecordService auditRecordService
    ) {
        this.repository = repository;
        this.authContext = authContext;
        this.entityManager = entityManager;
        this.auditRecordService = auditRecordService;
    }

    @Transactional(readOnly = true)
    public TrainingCodeReviewMode currentTrainingCodeReviewMode() {
        return repository.findById(PlatformSystemConfig.GLOBAL_ID)
                .map(PlatformSystemConfig::getTrainingCodeReviewMode)
                .map(SystemConfigService::storedModeOrFailClosed)
                .orElse(TrainingCodeReviewMode.STANDARD_REVIEW);
    }

    /** Serializes automatic decisions with review-mode updates. */
    @Transactional
    public TrainingCodeReviewMode currentTrainingCodeReviewModeForUpdate() {
        return repository.findByIdForUpdate(PlatformSystemConfig.GLOBAL_ID)
                .map(PlatformSystemConfig::getTrainingCodeReviewMode)
                .map(SystemConfigService::storedModeOrFailClosed)
                .orElse(TrainingCodeReviewMode.STANDARD_REVIEW);
    }

    @Transactional(readOnly = true)
    public int currentUserLogLimitMb() {
        return repository.findById(PlatformSystemConfig.GLOBAL_ID)
                .map(PlatformSystemConfig::getOperationLogMaxSize)
                .map(this::normalizeLimitMb)
                .orElse(PlatformSystemConfig.DEFAULT_USER_LOG_LIMIT_MB);
    }

    /** @deprecated use currentUserLogLimitMb */
    @Transactional(readOnly = true)
    public int currentOperationLogMaxSize() {
        return currentUserLogLimitMb();
    }

    @Transactional(readOnly = true)
    public SystemConfigDto getForAdministration() {
        requireSuperAdministratorAuthority();
        PlatformSystemConfig config = repository.findById(PlatformSystemConfig.GLOBAL_ID).orElse(null);
        return toDto(config);
    }

    @Transactional
    public SystemConfigDto updateForAdministration(SystemConfigUpdateRequest request) {
        requireSuperAdministratorAuthority();
        Integer operatorUserId = authContext.currentUserId();
        Instant now = Instant.now();
        PlatformSystemConfig config = repository
                .findByIdForUpdate(PlatformSystemConfig.GLOBAL_ID)
                .orElseGet(() -> newConfig(now));
        String previousReviewMode = null;
        String updatedReviewMode = null;

        if (request != null && request.trainingCodeReviewMode() != null && !request.trainingCodeReviewMode().isBlank()) {
            TrainingCodeReviewMode requested = TrainingCodeReviewMode.fromApiValue(request.trainingCodeReviewMode());
            String previous = storedModeOrFailClosed(config.getTrainingCodeReviewMode()).name();
            config.setTrainingCodeReviewMode(requested.name());
            if (!previous.equals(requested.name())) {
                previousReviewMode = previous;
                updatedReviewMode = requested.name();
            }
            log.info(
                    "Training code review mode updated: previous={}, current={}, operatorUserId={}",
                    previous,
                    requested,
                    operatorUserId
            );
        }

        if (request != null && request.logMaxSize() != null) {
            int requestedSize = request.logMaxSize();
            if (requestedSize < PlatformSystemConfig.MIN_USER_LOG_LIMIT_MB
                    || requestedSize > PlatformSystemConfig.MAX_USER_LOG_LIMIT_MB) {
                throw new IllegalArgumentException(
                        "用户日志存储上限需在 " + PlatformSystemConfig.MIN_USER_LOG_LIMIT_MB
                                + " ~ " + PlatformSystemConfig.MAX_USER_LOG_LIMIT_MB + " MB 之间");
            }
            config.setOperationLogMaxSize(requestedSize);
            log.info("User log storage limit updated: {} MB, operatorUserId={}", requestedSize, operatorUserId);
        }

        config.setUpdatedByUserId(operatorUserId);
        config.setUpdatedAt(now);
        PlatformSystemConfig saved = repository.saveAndFlush(config);
        trimAllUsersToLimitMb(currentUserLogLimitMb());
        if (updatedReviewMode != null) {
            auditRecordService.recordSuccess(
                    AuditActionType.PERMISSION_CHANGE,
                    AuditObjectType.TRAINING_CODE,
                    PlatformSystemConfig.GLOBAL_ID,
                    "trainingCodeReviewMode: " + previousReviewMode + " -> " + updatedReviewMode
            );
        }
        return toDto(saved);
    }

    @Transactional
    public void trimOperationLogs(int limitMb) {
        trimAllUsersToLimitMb(limitMb);
    }

    @Transactional
    public void trimUserExcessLogs(Integer userId) {
        if (userId == null) {
            return;
        }
        trimUserToLimit(userId, (long) currentUserLogLimitMb() * BYTES_PER_MB);
    }

    @Transactional
    public void trimAllUsersToLimitMb(int limitMb) {
        long limitBytes = (long) limitMb * BYTES_PER_MB;
        @SuppressWarnings("unchecked")
        List<Number> userIds = entityManager.createNativeQuery(
                "SELECT DISTINCT user_id FROM operation_logs WHERE user_id IS NOT NULL"
        ).getResultList();
        for (Number userId : userIds) {
            if (userId != null) {
                trimUserToLimit(userId.intValue(), limitBytes);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void trimUserToLimit(int userId, long limitBytes) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT id,
                       COALESCE(LENGTH(CONVERT_TO(COALESCE(user_name, ''), 'UTF8')), 0)
                     + COALESCE(LENGTH(CONVERT_TO(COALESCE(operation_type, ''), 'UTF8')), 0)
                     + COALESCE(LENGTH(CONVERT_TO(COALESCE(operation_obj, ''), 'UTF8')), 0)
                     + COALESCE(LENGTH(CONVERT_TO(COALESCE(ip_address, ''), 'UTF8')), 0)
                     + COALESCE(LENGTH(CONVERT_TO(COALESCE(remarks, ''), 'UTF8')), 0)
                     + COALESCE(LENGTH(CONVERT_TO(COALESCE(status, ''), 'UTF8')), 0)
                     + :overhead AS approx_bytes
                FROM operation_logs
                WHERE user_id = :userId
                ORDER BY operation_time ASC NULLS FIRST, id ASC
                """)
                .setParameter("overhead", ROW_OVERHEAD_BYTES)
                .setParameter("userId", userId)
                .getResultList();

        long used = 0L;
        for (Object[] row : rows) {
            used += ((Number) row[1]).longValue();
        }
        if (used <= limitBytes) {
            return;
        }
        for (Object[] row : rows) {
            if (used <= limitBytes) {
                break;
            }
            Number id = (Number) row[0];
            long bytes = ((Number) row[1]).longValue();
            entityManager.createNativeQuery("DELETE FROM operation_logs WHERE id = :id")
                    .setParameter("id", id.intValue())
                    .executeUpdate();
            used -= bytes;
        }
        log.info("Trimmed user operation logs by storage limit: userId={}, keepLimitBytes={}", userId, limitBytes);
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
        config.setOperationLogMaxSize(PlatformSystemConfig.DEFAULT_USER_LOG_LIMIT_MB);
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        return config;
    }

    private static TrainingCodeReviewMode storedModeOrFailClosed(String value) {
        try {
            return TrainingCodeReviewMode.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            return TrainingCodeReviewMode.MANUAL_ONLY;
        }
    }

    private int normalizeLimitMb(Integer value) {
        if (value == null) {
            return PlatformSystemConfig.DEFAULT_USER_LOG_LIMIT_MB;
        }
        // 兼容旧「条数」配置（常见默认 10000）
        if (value > PlatformSystemConfig.MAX_USER_LOG_LIMIT_MB) {
            return PlatformSystemConfig.DEFAULT_USER_LOG_LIMIT_MB;
        }
        if (value < PlatformSystemConfig.MIN_USER_LOG_LIMIT_MB) {
            return PlatformSystemConfig.DEFAULT_USER_LOG_LIMIT_MB;
        }
        return value;
    }

    private SystemConfigDto toDto(PlatformSystemConfig config) {
        String mode = TrainingCodeReviewMode.STANDARD_REVIEW.name();
        int limitMb = PlatformSystemConfig.DEFAULT_USER_LOG_LIMIT_MB;
        Instant updatedAt = null;
        if (config != null) {
            mode = storedModeOrFailClosed(config.getTrainingCodeReviewMode()).name();
            limitMb = normalizeLimitMb(config.getOperationLogMaxSize());
            updatedAt = config.getUpdatedAt();
        }
        return new SystemConfigDto(mode, limitMb, limitMb, updatedAt);
    }
}
