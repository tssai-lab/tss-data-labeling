package com.tss.platform.service;

import com.tss.platform.asset.spec.ArtifactSpecRegistry;
import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.TrainingPlanAdminDtos;
import com.tss.platform.entity.TrainingPlanDefinitionEntity;
import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.service.AuditRecordService;
import com.tss.platform.repository.TrainingPlanDefinitionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.training.plan.TrainingPlanContent;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import com.tss.platform.training.plan.TrainingPlanRegistry;
import com.tss.platform.training.plan.TrainingPlanValidator;
import com.tss.platform.training.plan.TrainingPlanYamlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrainingPlanAdministrationServiceTest {

    private TrainingPlanDefinitionRepository repository;
    private AuthContext authContext;
    private AuditRecordService auditRecordService;
    private PlatformTransactionManager transactionManager;
    private TransactionStatus transactionStatus;
    private TrainingPlanYamlParser parser;
    private TrainingPlanValidator validator;
    private TrainingPlanRegistry registry;
    private TrainingPlanAdministrationService service;
    private byte[] validYaml;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(TrainingPlanDefinitionRepository.class);
        authContext = mock(AuthContext.class);
        auditRecordService = mock(AuditRecordService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        transactionStatus = mock(TransactionStatus.class);
        parser = new TrainingPlanYamlParser();
        validator = new TrainingPlanValidator(new ArtifactSpecRegistry());
        registry = new TrainingPlanRegistry(validator, parser);
        registry.initialize();
        service = new TrainingPlanAdministrationService(
                repository, registry, parser, validator, authContext,
                auditRecordService, transactionManager
        );
        validYaml = new ClassPathResource("fixtures/training-plans/valid-online-v2.yaml")
                .getInputStream().readAllBytes();

        when(authContext.isSuperAdmin()).thenReturn(true);
        when(authContext.currentUserId()).thenReturn(1);
        when(authContext.currentUsername()).thenReturn("admin");
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(repository.findByPlanIdAndPlanVersion(any(), any())).thenReturn(Optional.empty());
        when(repository.findVersionForUpdate(any(), any())).thenReturn(Optional.empty());
        when(repository.findByStatusOrderByPlanIdAscPlanVersionAsc(any())).thenReturn(List.of());
        when(repository.findActiveByPlanIdForUpdate(any())).thenReturn(List.of());
        when(repository.findByPlanIdOrderByPlanVersionAsc(any())).thenReturn(List.of());
        when(repository.saveAndFlush(any(TrainingPlanDefinitionEntity.class))).thenAnswer(invocation -> {
            TrainingPlanDefinitionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(100L);
            }
            return entity;
        });
    }

    @Test
    void previewsValidPlanWithoutPersistingAndReportsDeferredAssetCount() {
        TrainingPlanAdminDtos.Preview result = service.preview(file(validYaml));

        assertTrue(result.publishable());
        assertEquals("custom_image_plan", result.definition().id());
        assertEquals(TrainingPlanContent.sha256(validYaml), result.sha256());
        assertTrue(result.warnings().stream().anyMatch(item ->
                "ASSET_COMPATIBILITY_COUNT_PENDING".equals(item.code())));
        assertTrue(result.changes().stream().anyMatch(item -> "security".equals(item.section())));
        verify(repository, never()).saveAndFlush(any());
        verify(auditRecordService).recordSuccess(
                AuditActionType.UPLOAD,
                AuditObjectType.TRAINING_PLAN,
                "custom_image_plan@v1",
                "TRAINING_PLAN_PREVIEW sha256=" + result.sha256()
        );
    }

    @Test
    void downloadsCvCpuTemplateThatPassesTheSameParserAndValidator() {
        TrainingPlanAdminDtos.TemplateFile result = service.template("cv-cpu-v2");
        byte[] content = result.yamlContent().getBytes(StandardCharsets.UTF_8);
        TrainingPlanDefinition definition = parser.parse(content, result.fileName());

        validator.validate(definition, result.fileName());

        assertEquals("cv-cpu-v2", result.templateId());
        assertEquals("training-plan-cv-cpu-v2.yaml", result.fileName());
        assertEquals("custom_cv_image_classification", definition.id());
        assertEquals(TrainingPlanDefinition.PlanCategory.CV, definition.category());
        assertEquals(
                "crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/"
                        + "tss-platform/tss-cv-worker@sha256:"
                        + "c319dc2ffc58119d0fa130b46eb90b8ab9b45e0e8e06683329dbbf75e69cb310",
                definition.runtimes().get(0).image()
        );
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void unknownTemplateReturnsStableNotFound() {
        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> service.template("missing-template")
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        assertEquals("TRAINING_PLAN_TEMPLATE_NOT_FOUND", error.getErrorCode());
    }

    @Test
    void templateChecksAuthorityBeforeTemplateExistence() {
        when(authContext.isSuperAdmin()).thenReturn(false);

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> service.template("missing-template")
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertEquals("TRAINING_PLAN_ADMIN_FORBIDDEN", error.getErrorCode());
    }

    @Test
    void invalidPreviewReturnsStableIssueAndDoesNotThrowOrPersist() {
        TrainingPlanAdminDtos.Preview result = service.preview(file(new byte[0]));

        assertFalse(result.publishable());
        assertEquals("YAML_EMPTY", result.issues().get(0).code());
        verify(repository, never()).saveAndFlush(any());
        verify(auditRecordService).recordFailed(
                eq(AuditActionType.UPLOAD), eq(AuditObjectType.TRAINING_PLAN),
                eq("unparsed"), eq("YAML_EMPTY"), any()
        );
    }

    @Test
    void publishRejectsContentChangedAfterPreview() {
        V2BusinessException error = assertThrows(V2BusinessException.class, () ->
                service.publish(file(validYaml), "0".repeat(64))
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("TRAINING_PLAN_SHA_MISMATCH", error.getErrorCode());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void publishesAtomicallyAndMakesOnlinePlanVisibleInRegistry() {
        TrainingPlanAdminDtos.Detail result = service.publish(
                file(validYaml), TrainingPlanContent.sha256(validYaml)
        );

        assertEquals(100L, result.summary().recordId());
        assertEquals("ACTIVE", result.summary().status());
        assertEquals("custom_image_plan", registry.requireEnabled("custom_image_plan", "v1").id());
        verify(transactionManager).commit(transactionStatus);
        verify(auditRecordService).recordSuccess(
                AuditActionType.UPLOAD,
                AuditObjectType.TRAINING_PLAN,
                "custom_image_plan@v1",
                "TRAINING_PLAN_PUBLISH sha256=" + TrainingPlanContent.sha256(validYaml)
        );
    }

    @Test
    void sameVersionWithDifferentContentIsAConflict() {
        TrainingPlanDefinitionEntity existing = entity(validYaml, true);
        existing.setContentSha256("f".repeat(64));
        when(repository.findByPlanIdAndPlanVersion("custom_image_plan", "v1"))
                .thenReturn(Optional.of(existing));

        V2BusinessException error = assertThrows(V2BusinessException.class, () ->
                service.publish(file(validYaml), TrainingPlanContent.sha256(validYaml))
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("TRAINING_PLAN_VERSION_CONTENT_CONFLICT", error.getErrorCode());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void publishRechecksVersionInsideLockAfterPreviewStateChanges() {
        TrainingPlanDefinitionEntity newer = entity(validYaml, false);
        newer.setId(56L);
        newer.setPlanVersion("v2");
        when(repository.findByPlanIdOrderByPlanVersionAsc("custom_image_plan"))
                .thenReturn(List.of(), List.of(newer));

        V2BusinessException error = assertThrows(V2BusinessException.class, () ->
                service.publish(file(validYaml), TrainingPlanContent.sha256(validYaml))
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("TRAINING_PLAN_VERSION_NOT_NEWER", error.getErrorCode());
        verify(transactionManager, never()).getTransaction(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void sameActiveVersionAndShaIsIdempotent() {
        TrainingPlanDefinitionEntity existing = entity(validYaml, true);
        when(repository.findByPlanIdAndPlanVersion("custom_image_plan", "v1"))
                .thenReturn(Optional.of(existing));
        when(repository.findVersionForUpdate("custom_image_plan", "v1"))
                .thenReturn(Optional.of(existing));
        when(repository.findByStatusOrderByPlanIdAscPlanVersionAsc(
                TrainingPlanDefinitionEntity.STATUS_ACTIVE
        )).thenReturn(List.of(existing));

        TrainingPlanAdminDtos.Detail result = service.publish(
                file(validYaml), TrainingPlanContent.sha256(validYaml)
        );

        assertEquals(existing.getId(), result.summary().recordId());
        verify(repository, never()).saveAndFlush(any());
        assertTrue(registry.find("custom_image_plan", "v1").isPresent());
    }

    @Test
    void disabledExactVersionCanBeRepublishedWithoutChangingYaml() {
        TrainingPlanDefinitionEntity existing = entity(validYaml, false);
        Instant importedAt = existing.getImportedAt();
        when(repository.findByPlanIdAndPlanVersion("custom_image_plan", "v1"))
                .thenReturn(Optional.of(existing));
        when(repository.findVersionForUpdate("custom_image_plan", "v1"))
                .thenReturn(Optional.of(existing));

        TrainingPlanAdminDtos.Detail result = service.publish(
                file(validYaml), TrainingPlanContent.sha256(validYaml)
        );

        assertEquals("ACTIVE", result.summary().status());
        assertEquals(importedAt, result.summary().importedAt());
        assertEquals(new String(validYaml, StandardCharsets.UTF_8), result.yamlContent());
        assertNotNull(result.summary().publishedAt());
    }

    @Test
    void publishingNewVersionFlushesOldActiveVersionBeforeInsert() {
        TrainingPlanDefinitionEntity oldActive = entity(validYaml, true);
        byte[] versionTwo = new String(validYaml, StandardCharsets.UTF_8)
                .replace("version: v1", "version: v2")
                .getBytes(StandardCharsets.UTF_8);
        when(repository.findActiveByPlanIdForUpdate("custom_image_plan"))
                .thenReturn(List.of(oldActive));

        TrainingPlanAdminDtos.Detail result = service.publish(
                file(versionTwo), TrainingPlanContent.sha256(versionTwo)
        );

        assertEquals("v2", result.summary().planVersion());
        assertEquals(TrainingPlanDefinitionEntity.STATUS_DISABLED, oldActive.getStatus());
        InOrder persistenceOrder = inOrder(repository);
        persistenceOrder.verify(repository).save(oldActive);
        persistenceOrder.verify(repository).flush();
        persistenceOrder.verify(repository).saveAndFlush(any(TrainingPlanDefinitionEntity.class));
    }

    @Test
    void disableRemovesOnlinePlanFromRegistryAndIsIdempotent() {
        TrainingPlanDefinitionEntity existing = entity(validYaml, true);
        TrainingPlanDefinition definition = parser.parse(validYaml, "fixture");
        validator.validate(definition, "fixture");
        registry.replaceOnlinePlans(List.of(definition));
        when(repository.findByPlanIdAndPlanVersion("custom_image_plan", "v1"))
                .thenReturn(Optional.of(existing));
        when(repository.findVersionForUpdate("custom_image_plan", "v1"))
                .thenReturn(Optional.of(existing));
        when(repository.findByStatusOrderByPlanIdAscPlanVersionAsc(
                TrainingPlanDefinitionEntity.STATUS_ACTIVE
        )).thenReturn(List.of(existing));

        TrainingPlanAdminDtos.Detail result = service.disable("custom_image_plan", "v1");

        assertEquals("DISABLED", result.summary().status());
        assertTrue(registry.find("custom_image_plan", "v1").isEmpty());
        assertNotNull(existing.getDisabledAt());
        verify(auditRecordService).recordSuccess(
                AuditActionType.DELETE, AuditObjectType.TRAINING_PLAN,
                "custom_image_plan@v1", "TRAINING_PLAN_DISABLE"
        );
    }

    @Test
    void nonSuperAdministratorIsForbiddenAndAudited() {
        when(authContext.isSuperAdmin()).thenReturn(false);

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> service.preview(file(validYaml))
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertEquals("TRAINING_PLAN_ADMIN_FORBIDDEN", error.getErrorCode());
        verify(repository, never()).saveAndFlush(any());
        verify(auditRecordService).recordFailed(
                eq(AuditActionType.UPLOAD), eq(AuditObjectType.TRAINING_PLAN),
                eq("unparsed"), eq("TRAINING_PLAN_ADMIN_FORBIDDEN"), any()
        );
    }

    @Test
    void disableChecksAuthorityBeforeMalformedIdentifiers() {
        when(authContext.isSuperAdmin()).thenReturn(false);

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> service.disable(" ", " ")
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertEquals("TRAINING_PLAN_ADMIN_FORBIDDEN", error.getErrorCode());
        verify(repository, never()).findByPlanIdAndPlanVersion(any(), any());
    }

    @Test
    void auditFailureDoesNotRollbackCommittedPublish() {
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditRecordService)
                .recordSuccess(eq(AuditActionType.UPLOAD), eq(AuditObjectType.TRAINING_PLAN), any(), any());

        TrainingPlanAdminDtos.Detail result = service.publish(
                file(validYaml), TrainingPlanContent.sha256(validYaml)
        );

        assertEquals("ACTIVE", result.summary().status());
        verify(transactionManager).commit(transactionStatus);
        assertTrue(registry.find("custom_image_plan", "v1").isPresent());
    }

    @Test
    void startupFailsClosedWhenStoredActiveYamlDigestDoesNotMatch() {
        TrainingPlanDefinitionEntity corrupted = entity(validYaml, true);
        corrupted.setContentSha256("0".repeat(64));
        when(repository.findByStatusOrderByPlanIdAscPlanVersionAsc(
                TrainingPlanDefinitionEntity.STATUS_ACTIVE
        )).thenReturn(List.of(corrupted));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.run(null)
        );

        assertTrue(error.getMessage().contains("摘要不一致"));
        assertTrue(registry.find("custom_image_plan", "v1").isEmpty());
    }

    private MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "plan.yaml", "application/yaml", content);
    }

    private TrainingPlanDefinitionEntity entity(byte[] content, boolean active) {
        Instant now = Instant.parse("2026-08-14T00:00:00Z");
        TrainingPlanDefinitionEntity entity = new TrainingPlanDefinitionEntity();
        entity.setId(55L);
        entity.setPlanId("custom_image_plan");
        entity.setPlanVersion("v1");
        entity.setSchemaVersion(TrainingPlanValidator.SCHEMA_VERSION_V2);
        entity.setYamlContent(new String(content, StandardCharsets.UTF_8));
        entity.setContentSha256(TrainingPlanContent.sha256(content));
        entity.setStatus(active
                ? TrainingPlanDefinitionEntity.STATUS_ACTIVE
                : TrainingPlanDefinitionEntity.STATUS_DISABLED);
        entity.setImportedByUserId(1);
        entity.setImportedAt(now);
        entity.setPublishedByUserId(1);
        entity.setPublishedAt(now);
        if (!active) {
            entity.setDisabledByUserId(1);
            entity.setDisabledAt(now.plusSeconds(60));
        }
        return entity;
    }
}
