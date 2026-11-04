package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.resource.KubernetesDiagnosticsDto;
import com.tss.platform.training.ShellCommandRunner;
import com.tss.platform.training.TrainingEnvironmentService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubernetesResourceDiagnosticsServiceTest {

    private static final String INFERENCE_IMAGE = "registry.example/tss-inference:abc123";

    @Test
    void reportsNodeConditionsPodFailuresAndActualImageIds() {
        Fixture fixture = fixture();
        when(fixture.shellRunner.runWithInput(any(), any(), any(), anyInt()))
                .thenReturn(ShellCommandRunner.CommandResult.success(nodesJson()))
                .thenReturn(ShellCommandRunner.CommandResult.success(podsJson()));

        KubernetesDiagnosticsDto result = fixture.service.collectDiagnostics();

        assertThat(result.getCollectionStatus()).isEqualTo("healthy");
        assertThat(result.getNodes()).hasSize(2);
        assertThat(result.getNodes().get(0).getHealthStatus()).isEqualTo("healthy");
        assertThat(result.getNodes().get(1).getHealthStatus()).isEqualTo("warning");
        assertThat(result.getNodes().get(1).getDiskPressure()).isTrue();
        assertThat(result.getNodes().get(1).getUnschedulable()).isTrue();

        assertThat(result.getPodIssues())
                .extracting(KubernetesDiagnosticsDto.KubernetesPodIssue::getReason)
                .contains("FailedScheduling", "ImagePullBackOff", "OOMKilled");
        assertThat(result.getPodIssues())
                .anySatisfy(issue -> {
                    assertThat(issue.getContainerType()).isEqualTo("init");
                    assertThat(issue.getContainerName()).isEqualTo("prepare-model");
                })
                .anySatisfy(issue -> {
                    assertThat(issue.getReason()).isEqualTo("OOMKilled");
                    assertThat(issue.getExitCode()).isEqualTo(137);
                });

        assertThat(result.getWorkloadImages())
                .filteredOn(image -> "inference-worker".equals(image.getContainerName()))
                .singleElement()
                .satisfies(image -> {
                    assertThat(image.getDeclaredImage()).isEqualTo(INFERENCE_IMAGE);
                    assertThat(image.getImageId()).isEqualTo("containerd://sha256:abcdef");
                    assertThat(image.getConfiguredInferenceImageMatch()).isTrue();
                });
    }

    @Test
    void keepsAvailablePodDiagnosticsWhenNodeCollectionFails() {
        Fixture fixture = fixture();
        when(fixture.shellRunner.runWithInput(any(), any(), any(), anyInt()))
                .thenReturn(ShellCommandRunner.CommandResult.failed(1, "", "nodes failed"))
                .thenReturn(ShellCommandRunner.CommandResult.success("{\"items\":[]}"));

        KubernetesDiagnosticsDto result = fixture.service.collectDiagnostics();

        assertThat(result.getCollectionStatus()).isEqualTo("degraded");
        assertThat(result.getNodes()).isEmpty();
        assertThat(result.getMessage()).contains("节点");
    }

    @Test
    void missingReadyConditionIsUnavailableInsteadOfHealthy() throws Exception {
        Fixture fixture = fixture();

        var nodes = fixture.service.parseNodes("""
                {"items":[{"metadata":{"name":"new-node"},"spec":{},"status":{"conditions":[]}}]}
                """);

        assertThat(nodes).singleElement().satisfies(node -> {
            assertThat(node.getReady()).isNull();
            assertThat(node.getHealthStatus()).isEqualTo("unavailable");
        });
    }

    @Test
    void reusesShortNodeCacheForConcurrentPageRequests() {
        Fixture fixture = fixture();
        when(fixture.shellRunner.runWithInput(any(), any(), any(), anyInt()))
                .thenReturn(ShellCommandRunner.CommandResult.success(nodesJson()));

        fixture.service.collectNodeHealth();
        fixture.service.collectNodeHealth();

        verify(fixture.shellRunner, times(1)).runWithInput(any(), any(), any(), anyInt());
    }

    private static Fixture fixture() {
        TrainingEnvironmentService environmentService = mock(TrainingEnvironmentService.class);
        ShellCommandRunner shellRunner = mock(ShellCommandRunner.class);
        Path root = Path.of(".").toAbsolutePath().normalize();
        when(environmentService.resolveProjectRoot()).thenReturn(root);
        when(environmentService.resolveKubeconfig()).thenReturn(root.resolve("kubeconfig"));
        when(environmentService.kubectlCommand(any(), any(String[].class)))
                .thenReturn(List.of("kubectl"));
        KubernetesResourceDiagnosticsService service = new KubernetesResourceDiagnosticsService(
                environmentService,
                shellRunner,
                new ObjectMapper(),
                INFERENCE_IMAGE
        );
        return new Fixture(service, shellRunner);
    }

    private static String nodesJson() {
        return """
                {"items":[
                  {
                    "metadata":{"name":"k8s-master"},
                    "spec":{},
                    "status":{"conditions":[
                      {"type":"Ready","status":"True"},
                      {"type":"MemoryPressure","status":"False"},
                      {"type":"DiskPressure","status":"False"},
                      {"type":"PIDPressure","status":"False"}
                    ]}
                  },
                  {
                    "metadata":{"name":"k8s-node1"},
                    "spec":{"unschedulable":true},
                    "status":{"conditions":[
                      {"type":"Ready","status":"False"},
                      {"type":"MemoryPressure","status":"False"},
                      {"type":"DiskPressure","status":"True"},
                      {"type":"PIDPressure","status":"False"}
                    ]}
                  }
                ]}
                """;
    }

    private static String podsJson() {
        return """
                {"items":[
                  {
                    "metadata":{"namespace":"tss-training","name":"pending-pod","labels":{}},
                    "spec":{"containers":[{"name":"worker","image":"example/worker:1"}]},
                    "status":{"phase":"Pending","conditions":[{
                      "type":"PodScheduled","status":"False","reason":"FailedScheduling",
                      "message":"0/2 nodes are available"
                    }],"containerStatuses":[]}
                  },
                  {
                    "metadata":{"namespace":"tss-training","name":"inference-pod","labels":{
                      "app.kubernetes.io/name":"tss-inference-job"
                    }},
                    "spec":{"nodeName":"k8s-master",
                      "initContainers":[{"name":"prepare-model","image":"busybox:1"}],
                      "containers":[{"name":"inference-worker","image":"registry.example/tss-inference:abc123"}]
                    },
                    "status":{"phase":"Pending",
                      "initContainerStatuses":[{"name":"prepare-model","imageID":"","state":{
                        "waiting":{"reason":"ImagePullBackOff","message":"pull failed"}
                      }}],
                      "containerStatuses":[{"name":"inference-worker",
                        "imageID":"containerd://sha256:abcdef","state":{"waiting":{"reason":"PodInitializing"}}
                      }]
                    }
                  },
                  {
                    "metadata":{"namespace":"tss-training","name":"oom-pod","labels":{}},
                    "spec":{"nodeName":"k8s-node1","containers":[{"name":"trainer","image":"train:1"}]},
                    "status":{"phase":"Failed","containerStatuses":[{"name":"trainer","state":{
                      "terminated":{"reason":"OOMKilled","exitCode":137,"message":"memory limit exceeded"}
                    }}]}
                  }
                ]}
                """;
    }

    private record Fixture(
            KubernetesResourceDiagnosticsService service,
            ShellCommandRunner shellRunner
    ) {
    }
}
