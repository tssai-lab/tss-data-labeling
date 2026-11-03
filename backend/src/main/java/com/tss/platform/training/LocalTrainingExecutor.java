package com.tss.platform.training;

import com.tss.platform.service.LocalTrainingRunnerService;
import org.springframework.stereotype.Component;

@Component
public class LocalTrainingExecutor implements TrainingExecutor {

    private final LocalTrainingRunnerService localTrainingRunnerService;

    public LocalTrainingExecutor(LocalTrainingRunnerService localTrainingRunnerService) {
        this.localTrainingRunnerService = localTrainingRunnerService;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getType() {
        return "local";
    }

    @Override
    public void start(String trainingId) {
        localTrainingRunnerService.start(trainingId);
    }

    @Override
    public void stop(String trainingId) {
        // 本地训练线程无法强制中断，状态由上层服务更新
    }
}
