package com.tss.platform.training;

/** Raised when the selected Kubernetes control path cannot complete a Job operation. */
public class KubernetesWorkloadException extends RuntimeException {

    public KubernetesWorkloadException(String message) {
        super(message);
    }

    public KubernetesWorkloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
