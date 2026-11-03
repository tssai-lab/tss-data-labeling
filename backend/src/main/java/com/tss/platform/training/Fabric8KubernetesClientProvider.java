package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** Builds one lazy Fabric8 client from the explicitly configured project kubeconfig. */
@Component
@ConditionalOnProperty(prefix = "training.kubernetes", name = "client-mode", havingValue = "fabric8")
public class Fabric8KubernetesClientProvider {

    private final TrainingKubernetesProperties properties;
    private final TrainingEnvironmentService environmentService;
    private volatile KubernetesClient client;

    public Fabric8KubernetesClientProvider(
            TrainingKubernetesProperties properties,
            TrainingEnvironmentService environmentService
    ) {
        this.properties = properties;
        this.environmentService = environmentService;
    }

    public KubernetesClient getClient() {
        KubernetesClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                client = openConfiguredClient();
            }
            return client;
        }
    }

    @PreDestroy
    void close() {
        KubernetesClient current = client;
        client = null;
        if (current != null) {
            current.close();
        }
    }

    private KubernetesClient openConfiguredClient() {
        Path kubeconfig = environmentService.resolveKubeconfig();
        if (!Files.isRegularFile(kubeconfig)) {
            throw new KubernetesWorkloadException(
                    "configured training kubeconfig does not exist: " + kubeconfig
            );
        }
        try {
            Config config = Config.fromKubeconfig(Files.readString(kubeconfig));
            int timeoutMillis = Math.multiplyExact(properties.getClientRequestTimeoutSeconds(), 1_000);
            config.setRequestTimeout(timeoutMillis);
            config.setUploadRequestTimeout(timeoutMillis);
            return new KubernetesClientBuilder().withConfig(config).build();
        } catch (Exception exception) {
            throw new KubernetesWorkloadException(
                    "cannot create Fabric8 client from configured training kubeconfig",
                    exception
            );
        }
    }
}
