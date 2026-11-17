package com.tss.platform.integration;

import com.tss.platform.TssPlatformApplication;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeValidationRun;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.CodeWorkspace;
import com.tss.platform.entity.CodeWorkspaceFileDelta;
import com.tss.platform.entity.MinioDeleteTask;
import com.tss.platform.model.CodeApprovalStatus;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeValidationRunRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.repository.CodeWorkspaceFileDeltaRepository;
import com.tss.platform.repository.CodeWorkspaceRepository;
import com.tss.platform.repository.MinioDeleteTaskRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.CodeArtifactAssembler;
import com.tss.platform.service.CodeArtifactStorageService;
import com.tss.platform.service.CodeFilePolicy;
import com.tss.platform.service.CodeWorkspaceConflictException;
import com.tss.platform.service.CodeWorkspacePublishException;
import com.tss.platform.service.CodeWorkspacePublishService;
import com.tss.platform.service.CodeZipArchiveService;
import com.tss.platform.service.MinioDeleteTaskScheduler;
import com.tss.platform.service.MinioDeleteTaskService;
import com.tss.platform.service.MinioService;
import com.tss.platform.service.V2AdminCodeAssetService;
import com.tss.platform.service.V2AdminCodeReviewService;
import com.tss.platform.service.V2CodeAssetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = {
                TssPlatformApplication.class,
                CodeAssetPublishIntegrationTest.TestAuthConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.profiles.active=test",
                "spring.main.banner-mode=off",
                "training.kubernetes.enabled=false",
                "training.mlflow.enabled=false",
                "minio.init.max-attempts=10",
                "minio.init.initial-backoff-ms=100"
        }
)
class CodeAssetPublishIntegrationTest {

    private static final int OWNER_USER_ID = 47_001;
    private static final int MINIO_API_PORT = 9000;
    private static final String MINIO_ACCESS_KEY = "codeasset-publish-it-access";
    private static final String MINIO_SECRET_KEY = "codeasset-publish-it-secret-2026";
    private static final String MINIO_BUCKET = "code-asset-publish-it";
    private static final String FORCED_AUDIT_FAILURE_LABEL = "forced-audit-failure";
    private static final String AUDIT_FAILURE_TRIGGER = "trg_it_fail_publish_audit_insert";
    private static final String AUDIT_FAILURE_FUNCTION = "fn_it_fail_publish_audit_insert";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16.6-alpine")
    )
            .withDatabaseName("code_asset_publish_it")
            .withUsername("code_asset_it")
            .withPassword("code_asset_it_password");

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z")
    )
            .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
            .withCommand("server", "/data", "--console-address", ":9001")
            .withExposedPorts(MINIO_API_PORT)
            .waitingFor(Wait.forHttp("/minio/health/ready")
                    .forPort(MINIO_API_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    static {
        com.tss.platform.testsupport.IsolatedIntegrationContainers.configure(POSTGRES, MINIO);
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("minio.endpoint", CodeAssetPublishIntegrationTest::mappedMinioEndpoint);
        registry.add("minio.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("minio.secret-key", () -> MINIO_SECRET_KEY);
        registry.add("minio.bucket", () -> MINIO_BUCKET);
    }

    @Autowired
    private CodeWorkspacePublishService publishService;

    @Autowired
    private V2AdminCodeReviewService codeReviewService;

    @Autowired
    private V2AdminCodeAssetService adminCodeAssetService;

    @Autowired
    private V2CodeAssetService codeAssetService;

    @MockitoSpyBean
    private CodeArtifactStorageService storageService;

    @Autowired
    private CodeZipArchiveService zipArchiveService;

    @Autowired
    private CodeFilePolicy filePolicy;

    @Autowired
    private MinioService minioService;

    @Autowired
    private CodeAssetRepository assetRepository;

    @Autowired
    private CodeVersionRepository versionRepository;

    @Autowired
    private CodeWorkspaceRepository workspaceRepository;

    @Autowired
    private CodeWorkspaceFileDeltaRepository deltaRepository;

    @Autowired
    private CodeValidationRunRepository validationRunRepository;

    @Autowired
    private MinioDeleteTaskRepository deleteTaskRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private MinioDeleteTaskScheduler minioDeleteTaskScheduler;

    @AfterEach
    void removeForcedPersistenceFailure() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + AUDIT_FAILURE_TRIGGER
                + " ON code_asset_audit_log");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + AUDIT_FAILURE_FUNCTION + "()");
    }

    @Test
    void publishesMergedArtifactWithActualStoredByteHashAndClosesWorkspace() throws Exception {
        String suffix = compactUuid();
        String assetId = "publish-success-" + suffix;
        String workspaceId = "workspace-success-" + suffix;
        String baseVersionId = "base-success-" + suffix;
        CodeAsset asset = persistAsset(assetId);

        Map<String, byte[]> baseFiles = new LinkedHashMap<>();
        baseFiles.put("config/keep.json", bytes("{\"keep\":true}\n"));
        baseFiles.put("notes/remove.md", bytes("remove this file\n"));
        baseFiles.put("scripts/train.py", bytes("print('base')\n"));
        byte[] baseArchive = zipArchiveService.writeDeterministic(baseFiles);
        String baseObjectName = versionObjectName(assetId, baseVersionId, "base.zip");
        storageService.upload(baseObjectName, baseArchive);
        CodeVersion baseVersion = persistBaseVersion(
                asset,
                baseVersionId,
                baseObjectName,
                baseArchive
        );
        persistWorkspace(workspaceId, assetId, baseVersionId);
        deltaRepository.saveAllAndFlush(List.of(
                upsert(workspaceId, "scripts/train.py", "print('published')\n"),
                upsert(workspaceId, "notes/new.md", "new file\n"),
                delete(workspaceId, "notes/remove.md")
        ));

        CodeVersion published = publishService.publish(workspaceId, 0L, " v2 ");

        CodeVersion persistedVersion = versionRepository.findById(published.getId()).orElseThrow();
        CodeWorkspace persistedWorkspace = workspaceRepository.findById(workspaceId).orElseThrow();
        CodeVersion persistedBase = versionRepository.findById(baseVersionId).orElseThrow();
        CodeValidationRun validationRun = validationRunRepository
                .findTopByVersionIdOrderByCreatedAtDescIdDesc(published.getId())
                .orElseThrow();
        byte[] actualStoredBytes;
        try (InputStream input = minioService.downloadStream(persistedVersion.getStoragePath())) {
            actualStoredBytes = input.readAllBytes();
        }
        String actualStoredSha256 = sha256(actualStoredBytes);
        LinkedHashMap<String, byte[]> publishedFiles = zipArchiveService.readEntries(
                new ByteArrayInputStream(actualStoredBytes)
        );
        String expectedObjectPrefix = "users/" + OWNER_USER_ID
                + "/codes/" + assetId
                + "/versions/" + published.getId() + "/";

        assertAll(
                () -> assertEquals("v2", persistedVersion.getVersion()),
                () -> assertEquals("READY", persistedVersion.getStatus()),
                () -> assertEquals("PASSED", persistedVersion.getValidationStatus()),
                () -> assertEquals(CodeApprovalStatus.PENDING,
                        persistedVersion.getApprovalStatus()),
                () -> assertEquals(CodeArtifactAssembler.POLICY_VERSION,
                        persistedVersion.getValidationPolicyVersion()),
                () -> assertEquals(actualStoredSha256, published.getArtifactSha256()),
                () -> assertEquals(actualStoredSha256, persistedVersion.getArtifactSha256()),
                () -> assertEquals((long) actualStoredBytes.length,
                        persistedVersion.getSizeBytes()),
                () -> assertTrue(persistedVersion.getStoragePath().matches(
                        Pattern.quote(expectedObjectPrefix) + "[0-9a-f]{32}\\.zip"
                )),
                () -> assertEquals(OWNER_USER_ID, persistedVersion.getOwnerUserId()),
                () -> assertEquals("scripts/train.py", persistedVersion.getEntryScript())
        );
        assertAll(
                () -> assertEquals(
                        List.of("config/keep.json", "notes/new.md", "scripts/train.py"),
                        new ArrayList<>(publishedFiles.keySet())
                ),
                () -> assertArrayEquals(baseFiles.get("config/keep.json"),
                        publishedFiles.get("config/keep.json")),
                () -> assertArrayEquals(bytes("new file\n"),
                        publishedFiles.get("notes/new.md")),
                () -> assertArrayEquals(bytes("print('published')\n"),
                        publishedFiles.get("scripts/train.py")),
                () -> assertFalse(publishedFiles.containsKey("notes/remove.md"))
        );
        assertAll(
                () -> assertEquals(CodeWorkspace.STATUS_PUBLISHED,
                        persistedWorkspace.getStatus()),
                () -> assertEquals(1L, persistedWorkspace.getRevision()),
                () -> assertEquals(published.getId(), persistedWorkspace.getClosedVersionId()),
                () -> assertNotNull(persistedWorkspace.getClosedAt()),
                () -> assertEquals(baseVersionId, persistedWorkspace.getBaseVersionId()),
                () -> assertEquals(baseVersion.getStoragePath(), persistedBase.getStoragePath()),
                () -> assertEquals(baseVersion.getArtifactSha256(),
                        persistedBase.getArtifactSha256()),
                () -> assertEquals("READY", persistedBase.getStatus()),
                () -> assertArrayEquals(baseArchive,
                        storageService.read(baseObjectName).bytes())
        );
        assertAll(
                () -> assertEquals(published.getId(), validationRun.getVersionId()),
                () -> assertEquals("PASSED", validationRun.getStatus()),
                () -> assertEquals(actualStoredSha256, validationRun.getArtifactSha256()),
                () -> assertEquals(CodeArtifactAssembler.POLICY_VERSION,
                        validationRun.getPolicyVersion()),
                () -> assertEquals(OWNER_USER_ID, validationRun.getRequestedByUserId()),
                () -> assertEquals(1L, auditCount(assetId, published.getId(), workspaceId)),
                () -> assertTrue(deleteTaskRepository.findAll().stream().noneMatch(task ->
                        Objects.equals(task.getObjectName(), persistedVersion.getStoragePath())))
        );
    }

    @Test
    void codeReviewQueueQueryAcceptsAbsentOptionalFiltersOnPostgres() {
        String suffix = compactUuid();
        CodeAsset asset = persistAsset("review-query-" + suffix);
        CodeVersion version = new CodeVersion();
        Instant now = Instant.now();
        version.setId("review-version-" + suffix);
        version.setAssetId(asset.getId());
        version.setVersion("review-v1");
        version.setStatus("READY");
        version.setApprovalStatus(CodeApprovalStatus.PENDING);
        version.setValidationStatus("NOT_RUN");
        version.setOwnerUserId(OWNER_USER_ID);
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        version.setDeleted(false);
        versionRepository.saveAndFlush(version);

        var page = codeReviewService.list(
                CodeApprovalStatus.PENDING,
                null,
                null,
                null,
                null,
                null,
                "SUBMITTED_AT",
                "DESC",
                0,
                20
        );

        assertTrue(page.items().stream()
                .anyMatch(candidate -> version.getId().equals(candidate.versionId())));
    }

    @Test
    void administratorAssetListIsCrossOwnerWhileOrdinaryListRemainsOwnerScopedOnPostgres() {
        String suffix = compactUuid();
        String keyword = "cross-owner-" + suffix;
        String ownAssetId = "admin-list-own-" + suffix;
        String foreignAssetId = "admin-list-foreign-" + suffix;
        persistAsset(ownAssetId, OWNER_USER_ID, keyword + "-own");
        persistAsset(foreignAssetId, OWNER_USER_ID + 1, keyword + "-foreign");

        var administratorPage = adminCodeAssetService.list(
                null,
                keyword,
                null,
                0,
                20,
                "UPDATED_AT",
                "DESC"
        );
        var ordinaryIds = codeAssetService.list().stream()
                .map(item -> item.id())
                .toList();

        assertAll(
                () -> assertEquals(
                        Set.of(OWNER_USER_ID, OWNER_USER_ID + 1),
                        administratorPage.items().stream()
                                .map(item -> item.ownerUserId())
                                .collect(java.util.stream.Collectors.toSet())
                ),
                () -> assertTrue(ordinaryIds.contains(ownAssetId)),
                () -> assertFalse(ordinaryIds.contains(foreignAssetId))
        );
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void concurrentPublishHasOneWinnerAndNoUnaccountedMinioObject() throws Exception {
        String suffix = compactUuid();
        String assetId = "publish-race-" + suffix;
        String workspaceId = "workspace-race-" + suffix;
        persistAsset(assetId);
        persistWorkspace(workspaceId, assetId, null);
        deltaRepository.saveAndFlush(upsert(
                workspaceId,
                "scripts/train.py",
                "print('single race winner')\n"
        ));

        String objectPrefix = "users/" + OWNER_USER_ID
                + "/codes/" + assetId + "/versions/";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch uploadsReady = new CountDownLatch(2);
        CountDownLatch releaseUploads = new CountDownLatch(1);
        doAnswer(invocation -> {
            String objectName = invocation.getArgument(0);
            if (objectName.startsWith(objectPrefix)) {
                uploadsReady.countDown();
                if (!releaseUploads.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent upload barrier timed out");
                }
            }
            return invocation.callRealMethod();
        }).when(storageService).upload(anyString(), any(byte[].class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<PublishAttempt> concurrentPublish = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent publish start gate timed out");
            }
            try {
                return new PublishAttempt(
                        publishService.publish(workspaceId, 0L, "race-v1"),
                        null
                );
            } catch (RuntimeException failure) {
                return new PublishAttempt(null, failure);
            }
        };

        List<PublishAttempt> attempts;
        Future<PublishAttempt> first = executor.submit(concurrentPublish);
        Future<PublishAttempt> second = executor.submit(concurrentPublish);
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS),
                    "both publish workers must reach the start gate");
            start.countDown();
            assertTrue(uploadsReady.await(20, TimeUnit.SECONDS),
                    "both publish workers must reach artifact upload");
            releaseUploads.countDown();
            attempts = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
        } finally {
            start.countDown();
            releaseUploads.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "publish executor must terminate");
        }

        List<CodeVersion> winners = attempts.stream()
                .map(PublishAttempt::published)
                .filter(Objects::nonNull)
                .toList();
        List<RuntimeException> failures = attempts.stream()
                .map(PublishAttempt::failure)
                .filter(Objects::nonNull)
                .toList();
        assertEquals(1, winners.size());
        assertEquals(1, failures.size());
        CodeVersion winner = winners.get(0);
        CodeWorkspaceConflictException conflict = assertInstanceOf(
                CodeWorkspaceConflictException.class,
                failures.get(0)
        );
        assertEquals("WORKSPACE_READ_ONLY", conflict.getReasonCode());

        List<CodeVersion> persistedVersions = versionRepository
                .findByAssetIdAndDeletedFalseOrderByCreatedAtDesc(assetId);
        CodeWorkspace persistedWorkspace = workspaceRepository.findById(workspaceId)
                .orElseThrow();
        assertAll(
                () -> assertEquals(1, persistedVersions.size()),
                () -> assertEquals(winner.getId(), persistedVersions.get(0).getId()),
                () -> assertEquals("READY", persistedVersions.get(0).getStatus()),
                () -> assertEquals("PASSED", persistedVersions.get(0).getValidationStatus()),
                () -> assertEquals(1L, validationRunCountForAsset(assetId)),
                () -> assertEquals(1L, publishAuditCount(assetId, workspaceId)),
                () -> assertEquals(1L, auditCount(assetId, winner.getId(), workspaceId)),
                () -> assertEquals(CodeWorkspace.STATUS_PUBLISHED,
                        persistedWorkspace.getStatus()),
                () -> assertEquals(1L, persistedWorkspace.getRevision()),
                () -> assertEquals(winner.getId(), persistedWorkspace.getClosedVersionId())
        );

        Set<String> actualObjects = new HashSet<>(minioService.listObjectNames(objectPrefix));
        List<MinioDeleteTask> compensationTasks = compensationTasks(objectPrefix);
        Set<String> registeredObjects = Set.of(persistedVersions.get(0).getStoragePath());
        Set<String> compensatedObjects = compensationTasks.stream()
                .map(MinioDeleteTask::getObjectName)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> accountedObjects = new HashSet<>(registeredObjects);
        accountedObjects.addAll(compensatedObjects);

        assertAll(
                () -> assertEquals(1, compensationTasks.size()),
                () -> assertTrue(compensatedObjects.stream()
                        .noneMatch(registeredObjects::contains)),
                () -> assertEquals(accountedObjects, actualObjects)
        );
        for (MinioDeleteTask task : compensationTasks) {
            assertExactPendingCompensation(task, task.getObjectName(), assetId);
        }
    }

    @Test
    void queuesExactDeleteTaskWhenPublishAuditPersistenceFailsAfterPriorFlushes()
            throws Exception {
        String suffix = compactUuid();
        String assetId = "publish-failure-" + suffix;
        String workspaceId = "workspace-failure-" + suffix;
        persistAsset(assetId);
        persistWorkspace(workspaceId, assetId, null);
        deltaRepository.saveAndFlush(upsert(
                workspaceId,
                "scripts/train.py",
                "print('uploaded-before-db-failure')\n"
        ));
        installForcedPublishAuditFailure(workspaceId);

        CodeWorkspacePublishException failure = assertThrows(
                CodeWorkspacePublishException.class,
                () -> publishService.publish(
                        workspaceId,
                        0L,
                        FORCED_AUDIT_FAILURE_LABEL
                )
        );

        String objectPrefix = "users/" + OWNER_USER_ID
                + "/codes/" + assetId + "/versions/";
        List<String> actualObjects = minioService.listObjectNames(objectPrefix);
        List<MinioDeleteTask> matchingTasks = compensationTasks(objectPrefix);

        assertEquals("Code workspace publication failed", failure.getMessage());
        assertEquals(1, actualObjects.size());
        assertEquals(1, matchingTasks.size());
        String uploadedObject = actualObjects.get(0);
        MinioDeleteTask cleanupTask = matchingTasks.get(0);
        assertExactPendingCompensation(cleanupTask, uploadedObject, assetId);
        byte[] uploadedBytes;
        try (InputStream input = minioService.downloadStream(uploadedObject)) {
            uploadedBytes = input.readAllBytes();
        }
        LinkedHashMap<String, byte[]> uploadedFiles = zipArchiveService.readEntries(
                new ByteArrayInputStream(uploadedBytes)
        );
        assertArrayEquals(
                bytes("print('uploaded-before-db-failure')\n"),
                uploadedFiles.get("scripts/train.py")
        );

        CodeWorkspace workspaceAfterFailure = workspaceRepository.findById(workspaceId)
                .orElseThrow();
        assertAll(
                () -> assertTrue(versionRepository
                        .findByAssetIdAndDeletedFalseOrderByCreatedAtDesc(assetId)
                        .isEmpty()),
                () -> assertEquals(CodeWorkspace.STATUS_OPEN, workspaceAfterFailure.getStatus()),
                () -> assertEquals(0L, workspaceAfterFailure.getRevision()),
                () -> assertNull(workspaceAfterFailure.getClosedVersionId()),
                () -> assertEquals(0L, publishAuditCount(assetId, workspaceId)),
                () -> assertEquals(0L, validationRunCountForAsset(assetId))
        );
    }

    private CodeAsset persistAsset(String assetId) {
        return persistAsset(
                assetId,
                OWNER_USER_ID,
                "Integration code asset " + assetId
        );
    }

    private CodeAsset persistAsset(
            String assetId,
            int ownerUserId,
            String name
    ) {
        Instant now = Instant.now();
        CodeAsset asset = new CodeAsset();
        asset.setId(assetId);
        asset.setName(name);
        asset.setPurpose("TRAINING");
        asset.setRuntime("python:3.11");
        asset.setEntryScript("scripts/train.py");
        asset.setTrainingType("NLP");
        asset.setOwnerUserId(ownerUserId);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset.setDeleted(false);
        return assetRepository.saveAndFlush(asset);
    }

    private CodeVersion persistBaseVersion(
            CodeAsset asset,
            String versionId,
            String objectName,
            byte[] archiveBytes
    ) {
        Instant now = Instant.now();
        CodeVersion version = new CodeVersion();
        version.setId(versionId);
        version.setAssetId(asset.getId());
        version.setVersion("base");
        version.setFileName("base.zip");
        version.setStoragePath(objectName);
        version.setSizeBytes((long) archiveBytes.length);
        version.setPurpose(asset.getPurpose());
        version.setRuntime(asset.getRuntime());
        version.setEntryScript(asset.getEntryScript());
        version.setTrainingType(asset.getTrainingType());
        version.setTrainingProfile(asset.getTrainingProfile());
        version.setStatus("READY");
        version.setApprovalStatus(CodeApprovalStatus.PENDING);
        version.setArtifactSha256(filePolicy.sha256(archiveBytes));
        version.setValidationStatus("PASSED");
        version.setValidationPolicyVersion(CodeArtifactAssembler.POLICY_VERSION);
        version.setOwnerUserId(OWNER_USER_ID);
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        version.setDeleted(false);
        return versionRepository.saveAndFlush(version);
    }

    private CodeWorkspace persistWorkspace(
            String workspaceId,
            String assetId,
            String baseVersionId
    ) {
        Instant now = Instant.now();
        CodeWorkspace workspace = new CodeWorkspace();
        workspace.setId(workspaceId);
        workspace.setAssetId(assetId);
        workspace.setBaseVersionId(baseVersionId);
        workspace.setStatus(CodeWorkspace.STATUS_OPEN);
        workspace.setRevision(0L);
        workspace.setOwnerUserId(OWNER_USER_ID);
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        workspace.setDeleted(false);
        return workspaceRepository.saveAndFlush(workspace);
    }

    private CodeWorkspaceFileDelta upsert(String workspaceId, String path, String text) {
        byte[] content = bytes(text);
        Instant now = Instant.now();
        CodeWorkspaceFileDelta delta = new CodeWorkspaceFileDelta();
        delta.setId("delta-" + compactUuid());
        delta.setWorkspaceId(workspaceId);
        delta.setPath(path);
        delta.setOperation(CodeWorkspaceFileDelta.OPERATION_UPSERT);
        delta.setContentBytes(content);
        delta.setContentHash(filePolicy.sha256(content));
        delta.setSizeBytes((long) content.length);
        delta.setCreatedAt(now);
        delta.setUpdatedAt(now);
        return delta;
    }

    private static CodeWorkspaceFileDelta delete(String workspaceId, String path) {
        Instant now = Instant.now();
        CodeWorkspaceFileDelta delta = new CodeWorkspaceFileDelta();
        delta.setId("delta-" + compactUuid());
        delta.setWorkspaceId(workspaceId);
        delta.setPath(path);
        delta.setOperation(CodeWorkspaceFileDelta.OPERATION_DELETE);
        delta.setCreatedAt(now);
        delta.setUpdatedAt(now);
        return delta;
    }

    private void installForcedPublishAuditFailure(String workspaceId) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION %s()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    RAISE EXCEPTION 'forced integration-test publish audit failure'
                        USING ERRCODE = 'P0001';
                    RETURN NEW;
                END;
                $function$
                """.formatted(AUDIT_FAILURE_FUNCTION));
        jdbcTemplate.execute("""
                CREATE TRIGGER %s
                BEFORE INSERT ON code_asset_audit_log
                FOR EACH ROW
                WHEN (NEW.action = 'PUBLISH' AND NEW.workspace_id = '%s')
                EXECUTE FUNCTION %s()
                """.formatted(
                AUDIT_FAILURE_TRIGGER,
                workspaceId,
                AUDIT_FAILURE_FUNCTION
        ));
    }

    private List<MinioDeleteTask> compensationTasks(String objectPrefix) {
        return deleteTaskRepository.findAll().stream()
                .filter(task -> MinioDeleteTaskService.SOURCE_CODE_ARTIFACT_ROLLBACK.equals(
                        task.getSourceType()))
                .filter(task -> task.getObjectName().startsWith(objectPrefix))
                .toList();
    }

    private void assertExactPendingCompensation(
            MinioDeleteTask cleanupTask,
            String expectedObject,
            String assetId
    ) throws Exception {
        String[] objectSegments = expectedObject.split("/");
        assertAll(
                () -> assertEquals(MINIO_BUCKET, cleanupTask.getBucket()),
                () -> assertEquals(expectedObject, cleanupTask.getObjectName()),
                () -> assertEquals(MinioDeleteTaskService.SOURCE_CODE_ARTIFACT_ROLLBACK,
                        cleanupTask.getSourceType()),
                () -> assertEquals(MinioDeleteTaskService.STATUS_PENDING,
                        cleanupTask.getStatus()),
                () -> assertEquals(OWNER_USER_ID, cleanupTask.getOwnerUserId()),
                () -> assertEquals(0, cleanupTask.getRetryCount()),
                () -> assertEquals(5, cleanupTask.getMaxRetryCount()),
                () -> assertEquals(7, objectSegments.length),
                () -> assertEquals("users", objectSegments[0]),
                () -> assertEquals(Integer.toString(OWNER_USER_ID), objectSegments[1]),
                () -> assertEquals("codes", objectSegments[2]),
                () -> assertEquals(assetId, objectSegments[3]),
                () -> assertEquals("versions", objectSegments[4]),
                () -> assertEquals(cleanupTask.getSourceId(), objectSegments[5]),
                () -> assertTrue(objectSegments[5].matches("code-version-[0-9a-f]{32}")),
                () -> assertTrue(objectSegments[6].matches("[0-9a-f]{32}\\.zip")),
                () -> assertTrue(minioService.stat(expectedObject).size() > 0)
        );
    }

    private long auditCount(String assetId, String versionId, String workspaceId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM code_asset_audit_log
                WHERE asset_id = ?
                  AND version_id = ?
                  AND workspace_id = ?
                  AND action = 'PUBLISH'
                """, Long.class, assetId, versionId, workspaceId);
        return count == null ? 0L : count;
    }

    private long publishAuditCount(String assetId, String workspaceId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM code_asset_audit_log
                WHERE asset_id = ? AND workspace_id = ? AND action = 'PUBLISH'
                """, Long.class, assetId, workspaceId);
        return count == null ? 0L : count;
    }

    private long validationRunCountForAsset(String assetId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM code_validation_run validation_run
                JOIN code_version version_row ON version_row.id = validation_run.version_id
                WHERE version_row.asset_id = ?
                """, Long.class, assetId);
        return count == null ? 0L : count;
    }

    private static String mappedMinioEndpoint() {
        String host = MINIO.getHost();
        String uriHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "http://" + uriHost + ":" + MINIO.getMappedPort(MINIO_API_PORT);
    }

    private static String versionObjectName(
            String assetId,
            String versionId,
            String fileName
    ) {
        return "users/" + OWNER_USER_ID
                + "/codes/" + assetId
                + "/versions/" + versionId
                + "/" + fileName;
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 must be available", exception);
        }
    }

    private record PublishAttempt(CodeVersion published, RuntimeException failure) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAuthConfiguration {

        @Bean
        @Primary
        AuthContext publicationIntegrationAuthContext() {
            return new AuthContext() {
                @Override
                public Integer currentUserId() {
                    return OWNER_USER_ID;
                }

                @Override
                public boolean isAdmin() {
                    return true;
                }

                @Override
                public boolean canAccessOwner(Integer ownerUserId) {
                    return Objects.equals(OWNER_USER_ID, ownerUserId);
                }

                @Override
                public boolean canAccessObjectName(String objectName, Integer ownerUserId) {
                    return canAccessOwner(ownerUserId)
                            && objectName != null
                            && objectName.startsWith("users/" + OWNER_USER_ID + "/");
                }
            };
        }
    }
}
