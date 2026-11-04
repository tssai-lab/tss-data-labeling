package com.tss.platform.service;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.PlatformSystemConfig;
import com.tss.platform.repository.PlatformSystemConfigRepository;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class JobTtlPolicyService {

    private static final Logger LOG = LoggerFactory.getLogger(JobTtlPolicyService.class);

    private final PlatformSystemConfigRepository repository;
    private final TrainingKubernetesProperties properties;

    public JobTtlPolicyService(
            PlatformSystemConfigRepository repository,
            TrainingKubernetesProperties properties
    ) {
        this.repository = repository;
        this.properties = properties;
    }

    public int currentJobTtlSecondsAfterFinished() {
        try {
            return repository.findById(PlatformSystemConfig.GLOBAL_ID)
                    .map(PlatformSystemConfig::getJobTtlSecondsAfterFinished)
                    .filter(JobTtlPolicyService::isAllowed)
                    .orElseGet(this::configuredFallback);
        } catch (RuntimeException exception) {
            int fallback = configuredFallback();
            LOG.warn(
                    "Unable to read dynamic Job TTL; using configured fallback {} seconds: {}",
                    fallback,
                    exception.getMessage()
            );
            return fallback;
        }
    }

    private int configuredFallback() {
        int configured = properties.getJobTtlSecondsAfterFinished();
        return isAllowed(configured)
                ? configured
                : PlatformSystemConfig.DEFAULT_JOB_TTL_SECONDS_AFTER_FINISHED;
    }

    private static boolean isAllowed(Integer value) {
        return value != null
                && value >= PlatformSystemConfig.MIN_JOB_TTL_SECONDS_AFTER_FINISHED
                && value <= PlatformSystemConfig.MAX_JOB_TTL_SECONDS_AFTER_FINISHED;
    }
}
