package com.tss.platform.module1.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserApiFeatureClassifierTest {

    private final UserApiFeatureClassifier classifier = new UserApiFeatureClassifier();

    @ParameterizedTest
    @CsvSource({
            "/api/model-assets,MODEL_ASSET",
            "/api/v2/model-uploads,MODEL_ASSET",
            "/api/dataset-versions/abc,DATASET_ASSET",
            "/api/v2/dataset-workspaces/abc,DATASET_ASSET",
            "/api/code/upload,TRAINING_DEFINITION",
            "/api/v2/admin/code-review-tasks,TRAINING_DEFINITION",
            "/api/admin/training-plans,TRAINING_DEFINITION",
            "/api/experiments,TRAINING_TASK",
            "/api/task/list,TRAINING_TASK",
            "/api/inference/tasks,INFERENCE_TASK",
            "/api/inference/scripts,INFERENCE_TASK",
            "/api/user/reset-password,SYSTEM_ADMIN_AUDIT",
            "/api/system/config,SYSTEM_ADMIN_AUDIT",
            "/api/v2/admin/demo-assets/model/asset-1,SYSTEM_ADMIN_AUDIT",
            "/api/resource-monitor/servers,SYSTEM_ADMIN_AUDIT",
            "/api/log/query,SYSTEM_ADMIN_AUDIT"
    })
    void mapsStableApiFamilies(String path, UserApiFeatureGroup expected) {
        assertThat(classifier.classify(path)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "/api/user/login",
            "/api/user/current-user",
            "/api/user/logout",
            "/api/internal/training/status",
            "/api/internal/inference/status",
            "/api/files/download",
            "/v3/api-docs"
    })
    void leavesPublicSafetyInternalAndSharedFilePathsOutsideFeatureSwitches(String path) {
        assertThat(classifier.classify(path)).isEmpty();
    }

    @Test
    void trainingPlansAreNotMisclassifiedAsTrainingTasks() {
        assertThat(classifier.classify("/api/training-plans/current"))
                .contains(UserApiFeatureGroup.TRAINING_DEFINITION);
    }
}
