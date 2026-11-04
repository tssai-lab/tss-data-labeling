package com.tss.platform.inference;

import com.tss.platform.config.InferenceModelCacheProperties;
import com.tss.platform.config.TrainingKubernetesProperties;
import com.tss.platform.entity.ComputeServer;
import com.tss.platform.entity.InferenceScriptVersion;
import com.tss.platform.entity.InferenceTask;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.repository.ComputeServerRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.InferenceScriptVersionRepository;
import com.tss.platform.repository.InferenceTaskRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.service.InferenceTaskService;
import com.tss.platform.service.JobScheduler;
import com.tss.platform.training.ShellCommandRunner;
import com.tss.platform.training.TrainingEnvironmentService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubernetesInferenceExecutorSchedulingTest {

    @Test
    void cacheDisabledStillBindsAnEnabledPlatformNodeBeforeApplyingJob() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        TrainingEnvironmentService environment = mock(TrainingEnvironmentService.class);
        InferenceTaskRepository tasks = mock(InferenceTaskRepository.class);
        ModelVersionRepository models = mock(ModelVersionRepository.class);
        DatasetVersionRepository datasets = mock(DatasetVersionRepository.class);
        InferenceScriptVersionRepository scripts = mock(InferenceScriptVersionRepository.class);
        KubernetesInferenceJobManifestBuilder manifests = mock(KubernetesInferenceJobManifestBuilder.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        InferenceTask task = new InferenceTask();
        task.setId("infer-task-test");
        task.setModelVersionId("model-version-test");
        task.setScriptVersionId("script-version-test");
        task.setInputMode(InferenceTaskService.INPUT_MODE_SINGLE_OBJECT);
        task.setCurrentAttempt(1);
        task.setStatus("pending");
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));

        ModelVersion model = new ModelVersion();
        model.setId(task.getModelVersionId());
        model.setArtifactAttestedSha256("a".repeat(64));
        when(models.findByIdAndDeletedFalse(model.getId())).thenReturn(Optional.of(model));

        InferenceScriptVersion script = new InferenceScriptVersion();
        script.setId(task.getScriptVersionId());
        when(scripts.findByIdAndDeletedFalse(script.getId())).thenReturn(Optional.of(script));

        JobScheduler scheduler = mock(JobScheduler.class);
        when(scheduler.assignNodeForInference(task, model.getArtifactAttestedSha256()))
                .thenReturn("10.0.0.2");
        ComputeServerRepository computeServers = mock(ComputeServerRepository.class);
        ComputeServer node = new ComputeServer();
        node.setServerIp("10.0.0.2");
        node.setK8sNodeName("tss-ai-control-01");
        when(computeServers.findByServerIpAndDeletedFalse("10.0.0.2"))
                .thenReturn(Optional.of(node));
        when(manifests.buildJobYaml(
                eq(task), eq(model), eq(script), isNull(),
                nullable(String.class), nullable(String.class), nullable(String.class),
                eq("tss-ai-control-01")
        )).thenReturn("apiVersion: batch/v1\nkind: Job\n");
        when(environment.resolveKubeconfig()).thenReturn(Path.of("kubeconfig"));
        when(environment.resolveProjectRoot()).thenReturn(Path.of("."));
        when(environment.kubectlCommand(any(), any(String[].class)))
                .thenReturn(List.of("kubectl", "apply", "-f", "-"));

        KubernetesInferenceExecutor executor = new KubernetesInferenceExecutor(
                properties, environment, tasks, models, datasets, scripts, manifests, transactions
        ) {
            @Override
            ShellCommandRunner.CommandResult runWithStdin(
                    List<String> command,
                    Path workingDirectory,
                    String stdinContent,
                    int timeoutSeconds
            ) {
                return ShellCommandRunner.CommandResult.success("job created");
            }
        };
        InferenceModelCacheProperties cache = new InferenceModelCacheProperties();
        cache.setEnabled(false);
        executor.setNodeScheduling(scheduler, computeServers, cache);

        executor.submitJob(task.getId(), 1);

        assertEquals("scheduled", task.getStatus());
        assertEquals("10.0.0.2", task.getServerIp());
        verify(scheduler).assignNodeForInference(task, model.getArtifactAttestedSha256());
        verify(manifests).buildJobYaml(
                eq(task), eq(model), eq(script), isNull(),
                nullable(String.class), nullable(String.class), nullable(String.class),
                eq("tss-ai-control-01")
        );
    }
}
