package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Fabric8KubernetesClientProviderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void refusesToFallBackToAnImplicitUserKubeconfig() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        TrainingEnvironmentService environmentService = mock(TrainingEnvironmentService.class);
        when(environmentService.resolveKubeconfig())
                .thenReturn(tempDirectory.resolve("missing-kubeconfig"));
        Fabric8KubernetesClientProvider provider =
                new Fabric8KubernetesClientProvider(properties, environmentService);

        assertThrows(KubernetesWorkloadException.class, provider::getClient);
    }
}
