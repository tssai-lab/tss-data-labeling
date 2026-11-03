package com.tss.platform.repository;

import com.tss.platform.entity.TrainingExperimentVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.TrainingExperimentVersionDto;
import com.tss.platform.service.TrainingSubmissionGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false", "spring.sql.init.mode=never"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TrainingMaintenancePostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.6-alpine")
            .withDatabaseName("training_maintenance").withUsername("maintenance").withPassword("maintenance-test");
    static { com.tss.platform.testsupport.IsolatedIntegrationContainers.configure(POSTGRES); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TrainingExperimentVersionRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    TrainingSubmissionGuard guard;
    TransactionTemplate transaction;

    @BeforeEach
    void migrateLikeAnExistingDatabase() throws Exception {
        // 仅本类临时库：恢复迁移前结构，并确认新迁移能给历史行补上修订号。
        if (jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'training_submission'", Integer.class) == 0) {
        jdbc.execute("ALTER TABLE training_experiment_version DROP COLUMN lock_revision");
        jdbc.update("INSERT INTO training_experiment_version(id, experiment_id, version_no, code_version_id, dataset_version_id) VALUES ('pre-migration', 'pre-migration', 1, 'code', 'dataset')");
        try (var connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V66__training_submission_and_revision.sql"));
        }
        assertEquals(0L, jdbc.queryForObject("SELECT lock_revision FROM training_experiment_version WHERE id = 'pre-migration'", Long.class));
        }
        guard = new TrainingSubmissionGuard(jdbc, new ObjectMapper());
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    void staleProgressCannotOverwriteAnAlreadyCommittedStop() {
        TrainingExperimentVersion saved = repository.saveAndFlush(task("exp-" + UUID.randomUUID(), 1, 7));
        TrainingExperimentVersion staleProgress = repository.findById(saved.getId()).orElseThrow();
        TrainingExperimentVersion stop = repository.findById(saved.getId()).orElseThrow();
        stop.setStatus("stopped");
        repository.saveAndFlush(stop);
        staleProgress.setStatus("running");
        staleProgress.setProgress(90);

        assertThrows(ObjectOptimisticLockingFailureException.class, () -> repository.saveAndFlush(staleProgress));
        assertEquals("stopped", repository.findById(saved.getId()).orElseThrow().getStatus());
    }

    @Test
    void atomicStopKeepsCompletionAndInvalidatesAnOlderProgressSnapshot() {
        TrainingExperimentVersion saved = repository.saveAndFlush(task("stop-" + UUID.randomUUID(), 1, 8));
        TrainingExperimentVersion stale = repository.findById(saved.getId()).orElseThrow();
        transaction.executeWithoutResult(tx -> assertEquals(1, repository.stopIfActive(saved.getId(), Instant.now())));
        stale.setProgress(90);
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> repository.saveAndFlush(stale));
        TrainingExperimentVersion completed = task("done-" + UUID.randomUUID(), 1, 8);
        completed.setStatus("success");
        repository.saveAndFlush(completed);
        transaction.executeWithoutResult(tx -> assertEquals(0, repository.stopIfActive(completed.getId(), Instant.now())));
        assertEquals("success", repository.findById(completed.getId()).orElseThrow().getStatus());
    }

    @Test
    void duplicateConcurrentSubmissionsAndLostResponseRetryCreateOnlyOneTask() throws Exception {
        String key = UUID.randomUUID().toString();
        AtomicInteger creates = new AtomicInteger();
        Supplier<TrainingExperimentVersionDto> submit = () -> transaction.execute(tx -> guard.createOnce(71, "new", key,
                Map.of("epochs", 2), () -> create(creates), this::read));
        List<TrainingExperimentVersionDto> results = simultaneously(submit, submit);
        assertEquals(results.get(0).getId(), results.get(1).getId());
        // 重建协调器模拟进程重启；回执持久化在数据库，不靠内存缓存。
        TrainingSubmissionGuard restarted = new TrainingSubmissionGuard(jdbc, new ObjectMapper());
        String replayed = transaction.execute(tx -> restarted.createOnce(71, "new", key,
                Map.of("epochs", 2), () -> create(creates), this::read).getId());
        assertEquals(results.get(0).getId(), replayed);
        assertEquals(1, creates.get());
    }

    @Test
    void submissionScopeParameterConflictRollbackAndDeletedResultAreHandled() {
        String key = UUID.randomUUID().toString();
        AtomicInteger creates = new AtomicInteger();
        Supplier<TrainingExperimentVersionDto> operation = () -> create(creates);
        var first = transaction.execute(tx -> guard.createOnce(72, "new", key, Map.of("epochs", 2), operation, this::read));
        assertThrows(IllegalArgumentException.class, () -> transaction.execute(tx ->
                guard.createOnce(72, "new", key, Map.of("epochs", 3), operation, this::read)));
        var otherUser = transaction.execute(tx -> guard.createOnce(73, "new", key, Map.of("epochs", 2), operation, this::read));
        assertNotEquals(first.getId(), otherUser.getId());
        var newIntent = transaction.execute(tx -> guard.createOnce(72, "new", UUID.randomUUID().toString(), Map.of("epochs", 2), operation, this::read));
        assertNotEquals(first.getId(), newIntent.getId());
        repository.deleteById(first.getId());
        assertThrows(IllegalArgumentException.class, () -> transaction.execute(tx ->
                guard.createOnce(72, "new", key, Map.of("epochs", 2), operation, this::read)));
        String rolledBackKey = UUID.randomUUID().toString();
        long before = jdbc.queryForObject("SELECT count(*) FROM training_submission", Long.class);
        assertThrows(IllegalStateException.class, () -> transaction.execute(tx -> {
            guard.createOnce(72, "new", rolledBackKey, Map.of(), operation, this::read);
            throw new IllegalStateException("模拟创建事务回滚");
        }));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM training_submission", Long.class));
        assertNotNull(transaction.execute(tx -> guard.createOnce(72, "new", rolledBackKey, Map.of(), operation, this::read)));
    }

    @Test
    void concurrentDifferentVersionsOfTheSameExperimentReceiveDifferentNumbers() throws Exception {
        String experiment = "versions-" + UUID.randomUUID();
        var original = task(experiment, 1, 74);
        original.setModelVersionId("test-model");
        original.setTrainingPlanId("test-plan");
        original.setHyperParamsJson("{}");
        repository.saveAndFlush(original);
        ServiceFixture fixture = trainingService(74);
        Supplier<Integer> create = () -> transaction.execute(tx -> {
            var request = new com.tss.platform.dto.CreateExperimentVersionRequest();
            request.setSubmissionKey(UUID.randomUUID().toString());
            return fixture.service().createVersion(experiment, request).getVersionNo();
        });
        assertEquals(List.of(2, 3), simultaneously(create, create).stream().sorted().toList());
        org.mockito.Mockito.verify(fixture.scheduler(), org.mockito.Mockito.timeout(5000).times(2)).enqueueTask(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void continuationUsesTheSelectedResultModelWithoutMutatingTheRequest() {
        String experiment = "result-model-" + UUID.randomUUID();
        var original = task(experiment, 1, 78);
        original.setModelVersionId("previous-input-model");
        original.setTrainingPlanId("test-plan");
        original.setHyperParamsJson("{}");
        repository.saveAndFlush(original);
        ServiceFixture fixture = trainingService(78);
        var request = new com.tss.platform.dto.CreateExperimentVersionRequest();
        request.setSubmissionKey(UUID.randomUUID().toString());
        request.setBaseModelVersionId("test-model");

        var created = transaction.execute(tx -> fixture.service().createVersion(experiment, request));

        assertNotNull(created);
        assertEquals(2, created.getVersionNo());
        assertEquals("test-model", created.getModelVersionId());
        assertEquals("test-model", request.getBaseModelVersionId());
        assertNull(request.getModelVersionId());
        assertEquals(
                "test-model",
                repository.findById(created.getId()).orElseThrow().getModelVersionId()
        );
        org.mockito.Mockito.verify(fixture.scheduler(), org.mockito.Mockito.timeout(5000))
                .enqueueTask(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void realCreationServiceReplaysOneTaskAndKeepsTheCallerRequestUnchanged() throws Exception {
        ServiceFixture fixture = trainingService(77);
        var request = new com.tss.platform.dto.CreateTrainingExperimentRequest();
        request.setSubmissionKey(UUID.randomUUID().toString());
        request.setBaseModelVersionId("test-model");
        request.setCodeVersionId("test-code");
        request.setDatasetVersionId("test-dataset");
        request.setPlanId("test-plan");
        Supplier<TrainingExperimentVersionDto> submit = () -> transaction.execute(tx -> fixture.service().createExperiment(request));
        var results = simultaneously(submit, submit);
        assertEquals(results.get(0).getId(), results.get(1).getId());
        assertEquals(results.get(0).getId(), submit.get().getId());
        assertNull(request.getModelVersionId());
        assertEquals(1, repository.findByExperimentIdOrderByVersionNoAsc(results.get(0).getExperimentId()).size());
        org.mockito.Mockito.verify(fixture.scheduler(), org.mockito.Mockito.timeout(5000).times(1)).enqueueTask(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void databaseSelectsLatestBeforePagingAndFilteringWithoutLeakingOtherOwners() {
        String prefix = "page-" + UUID.randomUUID();
        List<Object[]> records = new ArrayList<>();
        for (int experiment = 0; experiment < 250; experiment++) {
            for (int version = 1; version <= 40; version++) {
                records.add(new Object[]{prefix + "-" + experiment + "-" + version, prefix + "-" + experiment,
                        version, 75, version == 40 ? "success" : "running", "batch_100%"});
            }
        }
        long started = System.nanoTime();
        jdbc.batchUpdate("INSERT INTO training_experiment_version(id, experiment_id, version_no, owner_user_id, status, name, code_version_id, dataset_version_id, lock_revision, created_at) VALUES (?, ?, ?, ?, ?, ?, 'code', 'dataset', 0, CURRENT_TIMESTAMP)", records);
        repository.saveAndFlush(task(prefix + "-foreign", 1, 76));
        var first = repository.searchLatestExperiments(75, "success", "%batch!_100!%%", null, PageRequest.of(0, 20));
        var second = repository.searchLatestExperiments(75, "success", null, null, PageRequest.of(1, 20));
        assertEquals(250, first.getTotalElements());
        assertEquals(20, first.getNumberOfElements());
        assertTrue(first.stream().allMatch(v -> v.getVersionNo() == 40 && v.getOwnerUserId() == 75));
        assertTrue(first.stream().noneMatch(a -> second.stream().anyMatch(b -> a.getId().equals(b.getId()))));
        assertEquals(0, repository.searchLatestExperiments(75, "running", null, null, PageRequest.of(0, 20)).getTotalElements());
        assertEquals(1, repository.searchLatestExperiments(76, null, null, "%" + prefix + "-foreign%", PageRequest.of(0, 20)).getTotalElements());
        System.out.println("TRAINING_PAGING_EVIDENCE historicalRows=10000 latestRows=250 pageRows=20 seedAndChecksMs="
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    @Test
    void diagnosticRetentionCoversFailedAndStoppedTasks() {
        Instant old = Instant.now().minusSeconds(2 * 24 * 60 * 60);
        var failed = task("diagnostic-failed-" + UUID.randomUUID(), 1, 79);
        failed.setStatus("failed");
        failed.setFinishedAt(old);
        failed.setLogPath("minio://users/79/training-failure-diagnostics/" + failed.getId() + "/failure.log");
        var stopped = task("diagnostic-stopped-" + UUID.randomUUID(), 1, 79);
        stopped.setStatus("stopped");
        stopped.setFinishedAt(old);
        stopped.setLogPath("minio://users/79/training-failure-diagnostics/" + stopped.getId() + "/failure.log");
        repository.saveAllAndFlush(List.of(failed, stopped));

        var expired = repository.findExpiredFailureDiagnostics(
                Instant.now().minusSeconds(24 * 60 * 60),
                "%/training-failure-diagnostics/%",
                PageRequest.of(0, 10)
        );

        assertTrue(expired.stream().anyMatch(item -> item.getId().equals(failed.getId())));
        assertTrue(expired.stream().anyMatch(item -> item.getId().equals(stopped.getId())));
    }

    private TrainingExperimentVersionDto create(AtomicInteger creates) {
        creates.incrementAndGet();
        return read(repository.saveAndFlush(task("intent-" + UUID.randomUUID(), 1, 71)).getId());
    }

    private TrainingExperimentVersionDto read(String id) {
        TrainingExperimentVersion version = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("已删除"));
        TrainingExperimentVersionDto dto = new TrainingExperimentVersionDto();
        dto.setId(version.getId());
        dto.setVersionNo(version.getVersionNo());
        return dto;
    }

    private static <T> List<T> simultaneously(Supplier<T> first, Supplier<T> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Supplier<T> call : List.of(first, second)) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发起点超时");
                return call.get();
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }

    private ServiceFixture trainingService(int owner) {
        // 真实服务、事务、SQL 和回执；仅替换资产准入/快照及外部调度，测试不启动训练。
        var auth = org.mockito.Mockito.mock(com.tss.platform.security.AuthContext.class);
        org.mockito.Mockito.when(auth.currentUserId()).thenReturn(owner);
        org.mockito.Mockito.when(auth.canAccessOwner(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> Integer.valueOf(owner).equals(invocation.getArgument(0)));
        org.mockito.Mockito.doCallRealMethod().when(auth).requireOwnerAccess(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        var modelVersions = org.mockito.Mockito.mock(ModelVersionRepository.class);
        var modelAssets = org.mockito.Mockito.mock(ModelAssetRepository.class);
        var model = new com.tss.platform.entity.ModelVersion();
        model.setId("test-model"); model.setAssetId("test-model-asset"); model.setOwnerUserId(owner);
        var asset = new com.tss.platform.entity.ModelAsset();
        asset.setId("test-model-asset"); asset.setOwnerUserId(owner);
        org.mockito.Mockito.when(modelVersions.findByIdAndDeletedFalse(model.getId())).thenReturn(java.util.Optional.of(model));
        org.mockito.Mockito.when(modelAssets.findByIdAndDeletedFalse(asset.getId())).thenReturn(java.util.Optional.of(asset));
        var attestation = org.mockito.Mockito.mock(com.tss.platform.service.ModelArtifactAttestationService.class);
        org.mockito.Mockito.when(attestation.attestReady(model.getId())).thenReturn(
                new com.tss.platform.service.ModelArtifactAttestationService.AttestedArtifact(model, asset, 1, "a".repeat(64), null));
        var snapshot = org.mockito.Mockito.mock(com.tss.platform.training.plan.TrainingRunSnapshot.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(snapshot.runSpec().plan().id()).thenReturn("test-plan");
        org.mockito.Mockito.when(snapshot.runSpec().plan().version()).thenReturn("1");
        org.mockito.Mockito.when(snapshot.runSpec().trainingMode()).thenReturn(com.tss.platform.training.plan.TrainingPlanDefinition.TrainingMode.FROM_SCRATCH);
        org.mockito.Mockito.when(snapshot.runSpec().resources().profileId()).thenReturn("test-small");
        org.mockito.Mockito.when(snapshot.runSpecJson()).thenReturn("{}");
        org.mockito.Mockito.when(snapshot.resolvedParameters()).thenReturn(Map.of());
        var factory = org.mockito.Mockito.mock(com.tss.platform.training.plan.TrainingRunSpecFactory.class);
        org.mockito.Mockito.when(factory.create(org.mockito.ArgumentMatchers.any())).thenReturn(snapshot);
        var scheduler = org.mockito.Mockito.mock(com.tss.platform.service.JobScheduler.class);
        org.mockito.Mockito.when(scheduler.resolveNodeSelector(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of());
        var service = new com.tss.platform.service.TrainingExperimentService(repository, modelVersions, modelAssets,
                attestation, org.mockito.Mockito.mock(DatasetVersionRepository.class), org.mockito.Mockito.mock(DatasetAssetRepository.class),
                org.mockito.Mockito.mock(CodeVersionRepository.class), org.mockito.Mockito.mock(CodeAssetRepository.class),
                org.mockito.Mockito.mock(com.tss.platform.service.CodeVersionService.class), factory,
                org.mockito.Mockito.mock(com.tss.platform.training.plan.TrainingOutputValidator.class),
                org.mockito.Mockito.mock(com.tss.platform.training.TrainingExecutorRouter.class), scheduler, transaction,
                new ObjectMapper(), auth, org.mockito.Mockito.mock(com.tss.platform.service.MlflowTrackingService.class),
                org.mockito.Mockito.mock(com.tss.platform.training.TrainingFailureDiagnosticService.class), guard);
        return new ServiceFixture(service, scheduler);
    }

    private record ServiceFixture(com.tss.platform.service.TrainingExperimentService service,
                                  com.tss.platform.service.JobScheduler scheduler) { }

    static TrainingExperimentVersion task(String experiment, int number, int owner) {
        TrainingExperimentVersion task = new TrainingExperimentVersion();
        task.setId("test-" + UUID.randomUUID());
        task.setExperimentId(experiment);
        task.setVersionNo(number);
        task.setOwnerUserId(owner);
        task.setCodeVersionId("test-code");
        task.setDatasetVersionId("test-dataset");
        task.setStatus("running");
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(task.getCreatedAt());
        return task;
    }

    @SpringBootConfiguration
    @EntityScan(basePackageClasses = TrainingExperimentVersion.class)
    @EnableJpaRepositories(basePackageClasses = TrainingExperimentVersionRepository.class)
    static class Config { }
}
