package com.tss.platform.inference;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.InferenceScriptVersionRepository;
import com.tss.platform.repository.InferenceTaskRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.training.ShellCommandRunner;
import com.tss.platform.training.TrainingEnvironmentService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class KubernetesInferenceExecutorProcessTest {

    @Test
    void stdinCommandTimeoutIsEnforcedWhileProcessKeepsOutputOpen() throws Exception {
        KubernetesInferenceExecutor executor = new KubernetesInferenceExecutor(
                new TrainingKubernetesProperties(),
                mock(TrainingEnvironmentService.class),
                mock(InferenceTaskRepository.class),
                mock(ModelVersionRepository.class),
                mock(DatasetVersionRepository.class),
                mock(InferenceScriptVersionRepository.class),
                mock(KubernetesInferenceJobManifestBuilder.class),
                mock(PlatformTransactionManager.class)
        );
        String javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        ).toString();
        String testClasses = Path.of(
                KubernetesInferenceExecutorProcessTest.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI()
        ).toString();
        Instant started = Instant.now();

        ShellCommandRunner.CommandResult result = executor.runWithStdin(
                List.of(javaExecutable, "-cp", testClasses, HangingProcess.class.getName()),
                null,
                "input",
                1
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("timeout") || result.errorMessage().contains("超时"));
        assertTrue(Duration.between(started, Instant.now()).compareTo(Duration.ofSeconds(7)) < 0);
    }

    @Test
    void stateUpdatesUseIndependentTransactionsAfterOuterCommit() throws Exception {
        KubernetesInferenceExecutor executor = new KubernetesInferenceExecutor(
                new TrainingKubernetesProperties(),
                mock(TrainingEnvironmentService.class),
                mock(InferenceTaskRepository.class),
                mock(ModelVersionRepository.class),
                mock(DatasetVersionRepository.class),
                mock(InferenceScriptVersionRepository.class),
                mock(KubernetesInferenceJobManifestBuilder.class),
                mock(PlatformTransactionManager.class)
        );

        Field field = KubernetesInferenceExecutor.class.getDeclaredField("transactionTemplate");
        field.setAccessible(true);
        TransactionTemplate transactionTemplate = (TransactionTemplate) field.get(executor);

        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                transactionTemplate.getPropagationBehavior());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static final class HangingProcess {
        public static void main(String[] args) throws Exception {
            System.in.readAllBytes();
            Thread.sleep(30_000);
        }
    }
}
