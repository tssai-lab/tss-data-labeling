package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import com.tss.platform.training.plan.TrainingRunSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrainingRuntimeImageServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsPinnedBaseImageWhenCodeHasNoExtraDependencies() {
        TrainingRuntimeImageService service = new TrainingRuntimeImageService(
                properties(false), mock(ShellCommandRunner.class)
        );

        assertEquals("registry.example/tss/worker@sha256:" + "b".repeat(64),
                service.resolveImage(runSpec(List.of(), null)));
    }

    @Test
    void fallsBackToBaseImageWhenBuildCapabilityIsNotConfigured() {
        TrainingRuntimeImageService service = new TrainingRuntimeImageService(
                properties(false), mock(ShellCommandRunner.class)
        );

        assertEquals("registry.example/tss/worker@sha256:" + "b".repeat(64),
                service.resolveImage(runSpec(List.of("ultralytics==8.3.0"), "a".repeat(64))));
    }

    @Test
    void buildsAndPushesOneDeterministicallyTaggedDerivedImage() {
        ShellCommandRunner runner = mock(ShellCommandRunner.class);
        when(runner.run(anyList(), any(), anyInt())).thenReturn(
                ShellCommandRunner.CommandResult.failed(1, "not found", "pull failed"),
                ShellCommandRunner.CommandResult.failed(1, "not found", "base not local"),
                ShellCommandRunner.CommandResult.success("built"),
                ShellCommandRunner.CommandResult.success("pushed")
        );
        TrainingRuntimeImageService service = new TrainingRuntimeImageService(properties(true), runner);

        String image = service.resolveImage(runSpec(List.of("ultralytics==8.3.0"), "a".repeat(64)));

        assertEquals("registry.example/tss/training-worker:deps-" + "c".repeat(24), image);
        verify(runner, times(4)).run(anyList(), any(), anyInt());
    }

    @Test
    void doesNotForceRemotePullWhenBaseImageExistsLocally() {
        ShellCommandRunner runner = mock(ShellCommandRunner.class);
        when(runner.run(anyList(), any(), anyInt())).thenReturn(
                ShellCommandRunner.CommandResult.failed(1, "not found", "derived image absent"),
                ShellCommandRunner.CommandResult.success("base image exists"),
                ShellCommandRunner.CommandResult.success("built"),
                ShellCommandRunner.CommandResult.success("pushed")
        );
        TrainingRuntimeImageService service = new TrainingRuntimeImageService(properties(true), runner);

        String image = service.resolveImage(runSpec(List.of("torch"), "a".repeat(64)));

        assertEquals("registry.example/tss/training-worker:deps-" + "c".repeat(24), image);
        verify(runner).run(
                eq(List.of(
                        "docker", "build", "--tag",
                        "registry.example/tss/training-worker:deps-" + "c".repeat(24),
                        "."
                )),
                any(),
                anyInt()
        );
    }

    @Test
    void writesConfiguredPipMirrorTimeoutAndRetriesIntoDerivedDockerfile() throws Exception {
        ShellCommandRunner runner = mock(ShellCommandRunner.class);
        when(runner.run(anyList(), any(), anyInt())).thenReturn(
                ShellCommandRunner.CommandResult.failed(1, "not found", "derived image absent"),
                ShellCommandRunner.CommandResult.success("base image exists"),
                ShellCommandRunner.CommandResult.success("built"),
                ShellCommandRunner.CommandResult.success("pushed")
        );
        TrainingKubernetesProperties properties = properties(true);
        properties.setRuntimeImagePipIndexUrl("https://mirror.example/simple");
        properties.setRuntimeImagePipTimeoutSeconds(180);
        properties.setRuntimeImagePipRetries(7);

        new TrainingRuntimeImageService(properties, runner)
                .resolveImage(runSpec(List.of("transformers"), "a".repeat(64)));

        Path dockerfile;
        try (var paths = Files.walk(temporaryDirectory)) {
            dockerfile = paths.filter(path -> "Dockerfile".equals(
                            path.getFileName().toString()))
                    .findFirst()
                    .orElseThrow();
        }
        String content = Files.readString(dockerfile);
        org.junit.jupiter.api.Assertions.assertTrue(
                content.contains(
                        "--timeout 180 --retries 7"
                                + " --index-url https://mirror.example/simple"
                )
        );
    }

    private TrainingKubernetesProperties properties(boolean enabled) {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setRuntimeImageBuildEnabled(enabled);
        properties.setRuntimeImageRepository("registry.example/tss/training-worker");
        properties.setRuntimeImageBuildDirectory(temporaryDirectory.toString());
        properties.setRuntimeImageBuildTimeoutSeconds(30);
        return properties;
    }

    private TrainingRunSpec runSpec(List<String> requirements, String requirementsSha256) {
        return new TrainingRunSpec(
                TrainingRunSpec.SCHEMA_VERSION,
                "training-1",
                null,
                null,
                TrainingPlanDefinition.TrainingMode.FULL_FINETUNE,
                new TrainingRunSpec.Inputs(null, null, new TrainingRunSpec.CodeArtifact(
                        "code-1", "users/1/code.zip", "a".repeat(64), 1L, "ZIP", "code.zip", true,
                        List.of("train.py"), "train.py", "approval-1", requirements, requirementsSha256
                )),
                null,
                null,
                new TrainingRunSpec.Runtime(
                        "gpu", TrainingPlanDefinition.DeviceType.NVIDIA_GPU,
                        "registry.example/tss/worker", "sha256:" + "b".repeat(64),
                        TrainingPlanDefinition.ImagePullPolicy.IfNotPresent, "c".repeat(64)
                ),
                null,
                null,
                null,
                null
        );
    }
}
