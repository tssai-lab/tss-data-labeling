package com.tss.platform.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
public class TrainingEnvironmentInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(TrainingEnvironmentInitializer.class);

    private final TrainingEnvironmentService environmentService;

    public TrainingEnvironmentInitializer(TrainingEnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOG.info("开始初始化训练 Kubernetes 环境...");
        TrainingEnvironmentStatus status = environmentService.initializeOnStartup();
        LOG.info("训练环境状态: state={}, ready={}, message={}",
                status.getState(), status.isKubernetesReady(), status.getMessage());
    }
}
