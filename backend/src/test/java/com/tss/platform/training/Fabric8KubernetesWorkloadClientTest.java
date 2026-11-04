package com.tss.platform.training;

import com.tss.platform.config.TrainingKubernetesProperties;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.BatchAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import io.fabric8.kubernetes.client.dsl.V1BatchAPIGroupDSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fabric8KubernetesWorkloadClientTest {

    private static final String NAMESPACE = "tss-training";
    private static final String JOB_NAME = "tss-training-training-42";
    private static final String YAML = "apiVersion: batch/v1\nkind: Job\nmetadata:\n  name: " + JOB_NAME + "\n";

    private final TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
    private final Fabric8KubernetesClientProvider provider = mock(Fabric8KubernetesClientProvider.class);
    private final KubernetesClient kubernetesClient = mock(KubernetesClient.class);
    private final BatchAPIGroupDSL batch = mock(BatchAPIGroupDSL.class);
    private final V1BatchAPIGroupDSL batchV1 = mock(V1BatchAPIGroupDSL.class);
    private final MixedOperation<Job, JobList, ScalableResource<Job>> jobs = mock(MixedOperation.class);
    private final NonNamespaceOperation<Job, JobList, ScalableResource<Job>> namespacedJobs =
            mock(NonNamespaceOperation.class);
    private final ScalableResource<Job> jobResource = mock(ScalableResource.class);
    private final ScalableResource<Job> namedJobResource = mock(ScalableResource.class);
    private final NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable<HasMetadata> loadedResources =
            mock(NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable.class);
    private final MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class);
    private final NonNamespaceOperation<Pod, PodList, PodResource> namespacedPods =
            mock(NonNamespaceOperation.class);
    private final FilterWatchListDeletable<Pod, PodList, PodResource> selectedPods =
            mock(FilterWatchListDeletable.class);

    private Fabric8KubernetesWorkloadClient client;
    private Job job;

    @BeforeEach
    void setUp() {
        properties.setNamespace(NAMESPACE);
        when(provider.getClient()).thenReturn(kubernetesClient);
        when(kubernetesClient.batch()).thenReturn(batch);
        when(batch.v1()).thenReturn(batchV1);
        when(batchV1.jobs()).thenReturn(jobs);
        when(jobs.inNamespace(NAMESPACE)).thenReturn(namespacedJobs);
        when(namespacedJobs.resource(any(Job.class))).thenReturn(jobResource);
        when(namespacedJobs.withName(JOB_NAME)).thenReturn(namedJobResource);
        when(kubernetesClient.pods()).thenReturn(pods);
        when(pods.inNamespace(NAMESPACE)).thenReturn(namespacedPods);
        when(namespacedPods.withLabel("job-name", JOB_NAME)).thenReturn(selectedPods);
        when(kubernetesClient.load(any(InputStream.class))).thenReturn(loadedResources);
        client = new Fabric8KubernetesWorkloadClient(properties, provider);
        job = new JobBuilder()
                .withApiVersion("batch/v1")
                .withKind("Job")
                .withMetadata(new ObjectMetaBuilder().withName(JOB_NAME).build())
                .build();
        when(loadedResources.items())
                .thenReturn(List.<HasMetadata>of(job));
    }

    @Test
    void createsOnlyTheValidatedJobInTheConfiguredNamespace() {
        client.applyTrainingJob(NAMESPACE, JOB_NAME, YAML);

        verify(jobResource).create();
    }

    @Test
    void reconcilesCreateFailureWithTheSameFabric8Client() {
        doThrow(new RuntimeException("request timed out"))
                .when(jobResource).create();
        when(namedJobResource.get()).thenReturn(job);

        assertDoesNotThrow(() -> client.applyTrainingJob(NAMESPACE, JOB_NAME, YAML));
    }

    @Test
    void reportsCreateFailureWhenTheJobDoesNotExist() {
        doThrow(new RuntimeException("forbidden"))
                .when(jobResource).create();
        when(namedJobResource.get()).thenReturn(null);

        assertThrows(
                KubernetesWorkloadException.class,
                () -> client.applyTrainingJob(NAMESPACE, JOB_NAME, YAML)
        );
    }

    @Test
    void preservesCreateAndReconciliationFailuresTogether() {
        RuntimeException createFailure = new RuntimeException("request timed out");
        doThrow(createFailure).when(jobResource).create();
        when(namedJobResource.get()).thenThrow(new RuntimeException("API unavailable"));

        KubernetesWorkloadException thrown = assertThrows(
                KubernetesWorkloadException.class,
                () -> client.applyTrainingJob(NAMESPACE, JOB_NAME, YAML)
        );

        org.junit.jupiter.api.Assertions.assertSame(createFailure, thrown.getCause());
        org.junit.jupiter.api.Assertions.assertEquals(1, createFailure.getSuppressed().length);
    }

    @Test
    void rejectsManifestNameMismatchBeforeCreate() {
        Job other = new JobBuilder(job)
                .withMetadata(new ObjectMetaBuilder().withName("another-job").build())
                .build();
        when(loadedResources.items())
                .thenReturn(List.<HasMetadata>of(other));

        assertThrows(
                IllegalArgumentException.class,
                () -> client.applyTrainingJob(NAMESPACE, JOB_NAME, YAML)
        );
        verify(jobResource, never()).create();
    }

    @Test
    void rejectsMultipleResources() {
        when(loadedResources.items())
                .thenReturn(List.<HasMetadata>of(job, new JobBuilder(job).build()));

        assertThrows(
                IllegalArgumentException.class,
                () -> client.applyTrainingJob(NAMESPACE, JOB_NAME, YAML)
        );
    }

    @Test
    void rejectsJobWithWrongApiVersion() {
        Job wrongApi = new JobBuilder(job).withApiVersion("batch/v1beta1").build();
        when(loadedResources.items()).thenReturn(List.<HasMetadata>of(wrongApi));

        assertThrows(
                IllegalArgumentException.class,
                () -> client.applyTrainingJob(NAMESPACE, JOB_NAME, YAML)
        );
    }

    @Test
    void rejectsManifestForAnotherNamespaceBeforeCreate() {
        Job wrongNamespace = new JobBuilder(job)
                .editOrNewMetadata()
                .withNamespace("another-namespace")
                .endMetadata()
                .build();
        when(loadedResources.items()).thenReturn(List.<HasMetadata>of(wrongNamespace));

        assertThrows(
                IllegalArgumentException.class,
                () -> client.applyTrainingJob(NAMESPACE, JOB_NAME, YAML)
        );
        verify(jobResource, never()).create();
    }

    @Test
    void deletesOnlyTheNamedJobThroughFabric8() {
        client.deleteTrainingJob(NAMESPACE, JOB_NAME);

        verify(namedJobResource).delete();
    }

    @Test
    void reconcilesDeleteTimeoutWhenTheJobIsAlreadyAbsent() {
        doThrow(new RuntimeException("request timed out")).when(namedJobResource).delete();
        when(namedJobResource.get()).thenReturn(null);

        assertDoesNotThrow(() -> client.deleteTrainingJob(NAMESPACE, JOB_NAME));
    }

    @Test
    void reportsDeleteFailureWhenTheJobStillExists() {
        doThrow(new RuntimeException("forbidden")).when(namedJobResource).delete();
        when(namedJobResource.get()).thenReturn(job);

        assertThrows(
                KubernetesWorkloadException.class,
                () -> client.deleteTrainingJob(NAMESPACE, JOB_NAME)
        );
    }

    @Test
    void readsNewestPodStartupFailureTogetherWithJobCounters() {
        String createdAt = "2026-09-02T12:00:00Z";
        Job activeJob = new JobBuilder(job)
                .withNewStatus()
                .withActive(1)
                .endStatus()
                .build();
        Pod waitingPod = new PodBuilder()
                .withNewMetadata()
                .withName("training-pod")
                .withCreationTimestamp(createdAt)
                .endMetadata()
                .withNewStatus()
                .addNewContainerStatus()
                .withName("worker")
                .withNewState()
                .withNewWaiting()
                .withReason("ImagePullBackOff")
                .withMessage("pull access denied")
                .endWaiting()
                .endState()
                .endContainerStatus()
                .endStatus()
                .build();
        when(namedJobResource.get()).thenReturn(activeJob);
        when(selectedPods.list()).thenReturn(new PodListBuilder().withItems(waitingPod).build());

        var status = client.getTrainingJobStatus(NAMESPACE, JOB_NAME);

        assertTrue(status.isPresent());
        assertEquals(1, status.orElseThrow().active());
        assertEquals("ImagePullBackOff", status.orElseThrow().podWaitingReason());
        assertEquals("pull access denied", status.orElseThrow().podWaitingMessage());
        assertEquals(Instant.parse(createdAt), status.orElseThrow().podCreatedAt());
    }

    @Test
    void reportsMissingJobWithoutListingPods() {
        when(namedJobResource.get()).thenReturn(null);

        assertTrue(client.getTrainingJobStatus(NAMESPACE, JOB_NAME).isEmpty());

        verify(selectedPods, never()).list();
    }

    @Test
    void podListFailureDoesNotHideJobCounters() {
        Job succeededJob = new JobBuilder(job)
                .withNewStatus()
                .withSucceeded(1)
                .endStatus()
                .build();
        when(namedJobResource.get()).thenReturn(succeededJob);
        when(selectedPods.list()).thenThrow(new RuntimeException("pods forbidden"));

        var status = client.getTrainingJobStatus(NAMESPACE, JOB_NAME);

        assertTrue(status.isPresent());
        assertEquals(1, status.orElseThrow().succeeded());
        assertEquals(null, status.orElseThrow().podWaitingReason());
    }
}
