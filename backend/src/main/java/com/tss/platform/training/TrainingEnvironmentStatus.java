package com.tss.platform.training;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class TrainingEnvironmentStatus {
    public enum State {
        DISABLED,
        INITIALIZING,
        READY,
        DEGRADED,
        FAILED
    }

    private State state = State.DISABLED;
    private boolean kubernetesEnabled;
    private boolean kubernetesReady;
    private boolean fallbackToLocal;
    private String clusterName;
    private String namespace;
    private String workerImage;
    private String kubeconfig;
    private String message;
    private String lastError;
    private Instant checkedAt;

    public static TrainingEnvironmentStatus disabled(String message) {
        TrainingEnvironmentStatus status = new TrainingEnvironmentStatus();
        status.setState(State.DISABLED);
        status.setKubernetesEnabled(false);
        status.setKubernetesReady(false);
        status.setMessage(message);
        status.setCheckedAt(Instant.now());
        return status;
    }
}
