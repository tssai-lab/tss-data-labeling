package com.tss.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.controller.v2.V2CodeAssetController;
import com.tss.platform.controller.v2.V2ExceptionHandler;
import com.tss.platform.dto.v2.V2CodeAssetCreateRequest;
import com.tss.platform.dto.v2.V2CodeAssetDto;
import com.tss.platform.dto.v2.V2CodeAssetPatchRequest;
import com.tss.platform.dto.v2.V2CodeWorkspaceDto;
import com.tss.platform.service.CodeAssetAccessException;
import com.tss.platform.service.AssetNameConflictException;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.repository.CodeWorkspaceRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.CodeAccessPolicy;
import com.tss.platform.service.CodeAssetAuditService;
import com.tss.platform.service.CodeAssetReferenceChecker;
import com.tss.platform.service.CodePathPolicy;
import com.tss.platform.service.CodeWorkspaceService;
import com.tss.platform.service.CodeWorkspaceConflictException;
import com.tss.platform.service.CodeValidationException;
import com.tss.platform.service.V2CodeAssetService;
import com.tss.platform.training.plan.TrainingPlanRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class V2CodeAssetControllerTest {

    @Test
    void createRejectsUnsupportedTrainingProfileBeforePersisting() throws Exception {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        TrainingPlanRegistry plans = mock(TrainingPlanRegistry.class);
        when(plans.requireEnabled("unsupported-profile", null))
                .thenThrow(new IllegalArgumentException("not found"));
        V2CodeAssetService service = new V2CodeAssetService(
                assets,
                mock(CodeVersionRepository.class),
                mock(CodeWorkspaceRepository.class),
                mock(CodeWorkspaceService.class),
                mock(CodeAssetReferenceChecker.class),
                mock(CodeAssetAuditService.class),
                new CodePathPolicy(),
                plans,
                new CodeAccessPolicy(mock(AuthContext.class))
        );

        CodeValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                CodeValidationException.class,
                () -> service.create(new V2CodeAssetCreateRequest(
                        "Trainer",
                        "unsupported-profile",
                        "training",
                        "python3.11",
                        "src/train.py",
                        "CUSTOM",
                        "draft"
                ))
        );

        org.assertj.core.api.Assertions.assertThat(error.getReasonCode())
                .isEqualTo("UNSUPPORTED_TRAINING_PROFILE");
        verify(assets, never()).saveAndFlush(any());

        mvc(service).perform(post("/api/v2/code-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Trainer",
                                  "trainingProfile":"unsupported-profile"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CODE_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.reasonCode")
                        .value("UNSUPPORTED_TRAINING_PROFILE"));
    }

    @Test
    void createsAndListsDedicatedAssetDtosWithoutInternalProperties() throws Exception {
        V2CodeAssetService service = mock(V2CodeAssetService.class);
        V2CodeAssetDto asset = asset("asset-1", 0L, false);
        when(service.create(any())).thenReturn(asset);
        when(service.list()).thenReturn(List.of(asset));
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/v2/code-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Trainer",
                                  "trainingProfile":"PYTORCH",
                                  "purpose":"training",
                                  "runtime":"python3.11",
                                  "entryScript":"src/train.py",
                                  "trainingType":"CUSTOM",
                                  "remark":"draft"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("asset-1"))
                .andExpect(jsonPath("$.assetRevision").value(0))
                .andExpect(jsonPath("$.hasOpenWorkspace").value(false))
                .andExpect(jsonPath("$.ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.rowVersion").doesNotExist())
                .andExpect(jsonPath("$.deleted").doesNotExist())
                .andExpect(jsonPath("$.storagePath").doesNotExist());

        mvc.perform(get("/api/v2/code-assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("asset-1"));
    }

    @Test
    void duplicateNameReturnsStableV2ConflictContract() throws Exception {
        V2CodeAssetService service = mock(V2CodeAssetService.class);
        when(service.create(any())).thenThrow(new AssetNameConflictException("code"));

        mvc(service).perform(post("/api/v2/code-assets")
                        .header("X-Trace-Id", "trace-code-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":" Trainer ",
                                  "trainingProfile":"profile-a"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Trace-Id", "trace-code-name"))
                .andExpect(jsonPath("$.errorCode")
                        .value("CODE_ASSET_NAME_CONFLICT"))
                .andExpect(jsonPath("$.errorMessage")
                        .value("同一用户下已存在同名训练代码资产"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void createPrecheckAndDatabaseRaceUseTheSameNameConflict() {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        AuthContext auth = mock(AuthContext.class);
        when(auth.currentUserId()).thenReturn(7);
        V2CodeAssetService service = service(
                assets,
                mock(CodeVersionRepository.class),
                mock(CodeWorkspaceRepository.class),
                mock(CodeAssetAuditService.class),
                auth
        );
        V2CodeAssetCreateRequest request = new V2CodeAssetCreateRequest(
                "  Trainer  ", null, null, null, null, null, null
        );
        when(assets.existsActiveNormalizedName(7, "Trainer", null))
                .thenReturn(true);

        org.junit.jupiter.api.Assertions.assertThrows(
                AssetNameConflictException.class,
                () -> service.create(request)
        );
        verify(assets, never()).saveAndFlush(any());

        when(assets.existsActiveNormalizedName(7, "Trainer", null))
                .thenReturn(false);
        when(assets.saveAndFlush(any())).thenThrow(
                new DataIntegrityViolationException(
                        "uk_code_asset_owner_normalized_name"
                )
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                AssetNameConflictException.class,
                () -> service.create(request)
        );
    }

    @Test
    void ownerAndAdministratorRenameCheckTheAssetsOwnerScope() throws Exception {
        CodeAssetRepository ownerAssets = mock(CodeAssetRepository.class);
        AuthContext ownerAuth = mock(AuthContext.class);
        CodeAsset owned = entity(7, 3L);
        when(ownerAssets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(owned));
        when(ownerAuth.currentUserId()).thenReturn(7);
        when(ownerAssets.existsActiveNormalizedName(
                7,
                "Renamed",
                "asset-1"
        )).thenReturn(true);
        V2CodeAssetPatchRequest patch = new ObjectMapper().readValue(
                "{\"assetRevision\":3,\"name\":\"  Renamed  \"}",
                V2CodeAssetPatchRequest.class
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                AssetNameConflictException.class,
                () -> service(
                        ownerAssets,
                        mock(CodeVersionRepository.class),
                        mock(CodeWorkspaceRepository.class),
                        mock(CodeAssetAuditService.class),
                        ownerAuth
                ).patch("asset-1", patch)
        );
        verify(ownerAssets, never()).saveAndFlush(any());

        CodeAssetRepository adminAssets = mock(CodeAssetRepository.class);
        AuthContext adminAuth = mock(AuthContext.class);
        CodeAsset foreign = entity(19, 3L);
        when(adminAssets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(foreign));
        when(adminAuth.isAdmin()).thenReturn(true);
        when(adminAssets.existsActiveNormalizedName(
                19,
                "Renamed",
                "asset-1"
        )).thenReturn(true);

        org.junit.jupiter.api.Assertions.assertThrows(
                AssetNameConflictException.class,
                () -> service(
                        adminAssets,
                        mock(CodeVersionRepository.class),
                        mock(CodeWorkspaceRepository.class),
                        mock(CodeAssetAuditService.class),
                        adminAuth
                ).patchAdmin("asset-1", patch)
        );
        verify(adminAssets, never()).saveAndFlush(any());
    }

    @Test
    void patchRequestDistinguishesAbsentMetadataFromExplicitNull() throws Exception {
        V2CodeAssetPatchRequest request = new ObjectMapper().readValue(
                "{\"assetRevision\":4,\"remark\":null}",
                V2CodeAssetPatchRequest.class
        );

        org.assertj.core.api.Assertions.assertThat(request.isRemarkPresent()).isTrue();
        org.assertj.core.api.Assertions.assertThat(request.getRemark()).isNull();
        org.assertj.core.api.Assertions.assertThat(request.isRuntimePresent()).isFalse();
        org.assertj.core.api.Assertions.assertThat(request.getAssetRevision()).isEqualTo(4L);
    }

    @Test
    void mapsOwnerMissTo404AndRevisionConflictTo409WithTraceId() throws Exception {
        V2CodeAssetService service = mock(V2CodeAssetService.class);
        when(service.get("hidden")).thenThrow(new CodeAssetAccessException());
        when(service.patch(org.mockito.ArgumentMatchers.eq("asset-1"), any()))
                .thenThrow(new CodeWorkspaceConflictException(
                        "ASSET_REVISION_CONFLICT",
                        "Code asset revision is stale"
                ));
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/v2/code-assets/hidden")
                        .header("X-Trace-Id", "trace-owner"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Trace-Id", "trace-owner"))
                .andExpect(jsonPath("$.traceId").value("trace-owner"))
                .andExpect(jsonPath("$.errorCode").value("CODE_ASSET_NOT_FOUND"));

        mvc.perform(patch("/api/v2/code-assets/asset-1")
                        .header("X-Trace-Id", "trace-cas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetRevision\":1,\"name\":\"renamed\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reasonCode")
                        .value("ASSET_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.details.reason").doesNotExist())
                .andExpect(jsonPath("$.traceId").value("trace-cas"));
    }

    @Test
    void deletesOwnedAssetWithRevisionCasAndReturns204() throws Exception {
        V2CodeAssetService service = mock(V2CodeAssetService.class);

        mvc(service).perform(delete("/api/v2/code-assets/asset-1")
                        .queryParam("expectedAssetRevision", "3"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).delete("asset-1", 3L);
    }

    @Test
    void mapsImmutableTrainingProfileConflictTo409() throws Exception {
        V2CodeAssetService service = mock(V2CodeAssetService.class);
        when(service.patch(eq("asset-1"), any()))
                .thenThrow(new CodeWorkspaceConflictException(
                        "TRAINING_PROFILE_IMMUTABLE",
                        "Code asset training profile is immutable"
                ));

        mvc(service).perform(patch("/api/v2/code-assets/asset-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetRevision\":1,\"trainingProfile\":\"profile-b\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CODE_ASSET_CONFLICT"))
                .andExpect(jsonPath("$.details.reasonCode")
                        .value("TRAINING_PROFILE_IMMUTABLE"));
    }

    @Test
    void sanitizesNonStableConflictReasonCode() throws Exception {
        V2CodeAssetService service = mock(V2CodeAssetService.class);
        when(service.patch(eq("asset-1"), any()))
                .thenThrow(new CodeWorkspaceConflictException(
                        "users/7/private.zip",
                        "must not leak"
                ));

        mvc(service).perform(patch("/api/v2/code-assets/asset-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetRevision\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reasonCode").value("CONFLICT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("users/7/private.zip")
                )));
    }

    @Test
    void adminGetsNoImplicitCrossOwnerAccessAndOwnerCheckPrecedesRevision() throws Exception {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        CodeVersionRepository versions = mock(CodeVersionRepository.class);
        CodeWorkspaceRepository workspaces = mock(CodeWorkspaceRepository.class);
        CodeAssetAuditService audit = mock(CodeAssetAuditService.class);
        AuthContext auth = mock(AuthContext.class);
        CodeAsset foreign = entity(7, 9L);
        when(assets.findByIdAndDeletedFalse("asset-1")).thenReturn(Optional.of(foreign));
        when(assets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(foreign));
        when(auth.currentUserId()).thenReturn(99);
        when(auth.isAdmin()).thenReturn(true);
        V2CodeAssetService service = service(assets, versions, workspaces, audit, auth);
        V2CodeAssetPatchRequest patch = new ObjectMapper().readValue(
                "{\"assetRevision\":9,\"name\":\"leak\"}",
                V2CodeAssetPatchRequest.class
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                CodeAssetAccessException.class,
                () -> service.get("asset-1")
        );
        org.junit.jupiter.api.Assertions.assertThrows(
                CodeAssetAccessException.class,
                () -> service.patch("asset-1", patch)
        );
        V2CodeAssetPatchRequest missingRevision = new ObjectMapper().readValue(
                "{\"name\":\"still-hidden\"}",
                V2CodeAssetPatchRequest.class
        );
        org.junit.jupiter.api.Assertions.assertThrows(
                CodeAssetAccessException.class,
                () -> service.patch("asset-1", missingRevision)
        );
        verify(assets, never()).saveAndFlush(any());
        verify(versions, never()).existsByAssetIdAndDeletedFalse(any());
        verify(audit, never()).assetUpdated(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void explicitAdminEntryPointCanManageCrossOwnerWithoutChangingOwnership() throws Exception {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        CodeVersionRepository versions = mock(CodeVersionRepository.class);
        CodeWorkspaceRepository workspaces = mock(CodeWorkspaceRepository.class);
        CodeAssetAuditService audit = mock(CodeAssetAuditService.class);
        AuthContext auth = mock(AuthContext.class);
        CodeAsset foreign = entity(7, 9L);
        when(assets.findByIdAndDeletedFalse("asset-1")).thenReturn(Optional.of(foreign));
        when(assets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(foreign));
        when(assets.saveAndFlush(foreign)).thenAnswer(invocation -> {
            foreign.setRowVersion(10L);
            return foreign;
        });
        when(auth.isAdmin()).thenReturn(true);
        V2CodeAssetService service = service(assets, versions, workspaces, audit, auth);
        V2CodeAssetPatchRequest patch = new ObjectMapper().readValue(
                "{\"assetRevision\":9,\"name\":\"managed-by-admin\"}",
                V2CodeAssetPatchRequest.class
        );

        assertEquals("asset-1", service.getAdmin("asset-1").id());
        V2CodeAssetDto updated = service.patchAdmin("asset-1", patch);

        assertEquals("managed-by-admin", updated.name());
        assertEquals(7, foreign.getOwnerUserId());
        verify(audit).assetUpdated("asset-1", 10L);
    }

    @Test
    void patchClearsExplicitNullPreservesAbsentFieldAndAuditsNewRevision() throws Exception {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        CodeVersionRepository versions = mock(CodeVersionRepository.class);
        CodeWorkspaceRepository workspaces = mock(CodeWorkspaceRepository.class);
        CodeAssetAuditService audit = mock(CodeAssetAuditService.class);
        AuthContext auth = mock(AuthContext.class);
        CodeAsset owned = entity(7, 3L);
        owned.setRemark("old");
        owned.setRuntime("python3.11");
        when(assets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(owned));
        when(assets.saveAndFlush(owned)).thenAnswer(invocation -> {
            owned.setRowVersion(4L);
            return owned;
        });
        when(workspaces.findOpenByAssetId("asset-1")).thenReturn(Optional.empty());
        when(auth.currentUserId()).thenReturn(7);
        V2CodeAssetService service = service(assets, versions, workspaces, audit, auth);
        V2CodeAssetPatchRequest patch = new ObjectMapper().readValue(
                "{\"assetRevision\":3,\"remark\":null}",
                V2CodeAssetPatchRequest.class
        );

        V2CodeAssetDto result = service.patch("asset-1", patch);

        org.assertj.core.api.Assertions.assertThat(owned.getRemark()).isNull();
        org.assertj.core.api.Assertions.assertThat(owned.getRuntime()).isEqualTo("python3.11");
        org.assertj.core.api.Assertions.assertThat(result.assetRevision()).isEqualTo(4L);
        verify(versions, never()).existsByAssetIdAndDeletedFalse(any());
        verify(audit).assetUpdated("asset-1", 4L);
    }

    @Test
    void staleAssetRevisionDoesNotWriteOrAudit() throws Exception {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        CodeVersionRepository versions = mock(CodeVersionRepository.class);
        CodeWorkspaceRepository workspaces = mock(CodeWorkspaceRepository.class);
        CodeAssetAuditService audit = mock(CodeAssetAuditService.class);
        AuthContext auth = mock(AuthContext.class);
        CodeAsset owned = entity(7, 4L);
        when(assets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(owned));
        when(auth.currentUserId()).thenReturn(7);
        V2CodeAssetService service = service(assets, versions, workspaces, audit, auth);
        V2CodeAssetPatchRequest stale = new ObjectMapper().readValue(
                "{\"assetRevision\":3,\"remark\":\"new\"}",
                V2CodeAssetPatchRequest.class
        );

        CodeWorkspaceConflictException conflict = org.junit.jupiter.api.Assertions.assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.patch("asset-1", stale)
        );
        org.assertj.core.api.Assertions.assertThat(conflict.getReasonCode())
                .isEqualTo("ASSET_REVISION_CONFLICT");
        verify(assets, never()).saveAndFlush(any());
        verify(versions, never()).existsByAssetIdAndDeletedFalse(any());
        verify(audit, never()).assetUpdated(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void softDeleteLocksOwnedAssetAndPreservesRowsAndObjectsByOnlyUpdatingAsset() {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        CodeVersionRepository versions = mock(CodeVersionRepository.class);
        CodeWorkspaceRepository workspaces = mock(CodeWorkspaceRepository.class);
        CodeAssetReferenceChecker references = mock(CodeAssetReferenceChecker.class);
        CodeAssetAuditService audit = mock(CodeAssetAuditService.class);
        AuthContext auth = mock(AuthContext.class);
        CodeAsset owned = entity(7, 3L);
        when(assets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(owned));
        when(workspaces.findOpenByAssetId("asset-1")).thenReturn(Optional.empty());
        when(references.hasReferences("asset-1")).thenReturn(false);
        when(assets.saveAndFlush(owned)).thenAnswer(invocation -> {
            owned.setRowVersion(4L);
            return owned;
        });
        when(auth.currentUserId()).thenReturn(7);
        V2CodeAssetService service = service(
                assets, versions, workspaces, references, audit, auth
        );

        service.delete("asset-1", 3L);

        org.assertj.core.api.Assertions.assertThat(owned.getDeleted()).isTrue();
        org.assertj.core.api.Assertions.assertThat(owned.getDeletedAt()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(owned.getUpdatedAt())
                .isEqualTo(owned.getDeletedAt());
        verify(assets).saveAndFlush(owned);
        verify(versions, never()).delete(any(CodeVersion.class));
        verify(workspaces, never()).delete(any());
        verify(audit).assetDeleted("asset-1", 4L);

        var order = org.mockito.Mockito.inOrder(assets, workspaces, references);
        order.verify(assets).findByIdAndDeletedFalseForUpdate("asset-1");
        order.verify(workspaces).findOpenByAssetId("asset-1");
        order.verify(references).hasReferences("asset-1");
    }

    @Test
    void softDeleteRejectsStaleAssetRevisionBeforeWorkspaceAndReferenceChecks() {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        CodeVersionRepository versions = mock(CodeVersionRepository.class);
        CodeWorkspaceRepository workspaces = mock(CodeWorkspaceRepository.class);
        CodeAssetReferenceChecker references = mock(CodeAssetReferenceChecker.class);
        CodeAssetAuditService audit = mock(CodeAssetAuditService.class);
        AuthContext auth = mock(AuthContext.class);
        when(assets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(entity(7, 4L)));
        when(auth.currentUserId()).thenReturn(7);
        V2CodeAssetService service = service(
                assets, versions, workspaces, references, audit, auth
        );

        CodeWorkspaceConflictException stale = org.junit.jupiter.api.Assertions.assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.delete("asset-1", 3L)
        );

        org.assertj.core.api.Assertions.assertThat(stale.getReasonCode())
                .isEqualTo("ASSET_REVISION_CONFLICT");
        verify(workspaces, never()).findOpenByAssetId(any());
        verify(references, never()).hasReferences(any());
        verify(assets, never()).saveAndFlush(any());
        verify(audit, never()).assetDeleted(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void softDeleteRejectsOpenWorkspaceAndPersistedReferencesWithoutMutation() {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        CodeVersionRepository versions = mock(CodeVersionRepository.class);
        CodeWorkspaceRepository workspaces = mock(CodeWorkspaceRepository.class);
        CodeAssetReferenceChecker references = mock(CodeAssetReferenceChecker.class);
        CodeAssetAuditService audit = mock(CodeAssetAuditService.class);
        AuthContext auth = mock(AuthContext.class);
        CodeAsset owned = entity(7, 3L);
        when(assets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(owned));
        when(auth.currentUserId()).thenReturn(7);
        when(workspaces.findOpenByAssetId("asset-1"))
                .thenReturn(Optional.of(new com.tss.platform.entity.CodeWorkspace()));
        V2CodeAssetService service = service(
                assets, versions, workspaces, references, audit, auth
        );

        CodeWorkspaceConflictException open = org.junit.jupiter.api.Assertions.assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.delete("asset-1", 3L)
        );
        org.assertj.core.api.Assertions.assertThat(open.getReasonCode())
                .isEqualTo("OPEN_WORKSPACE_EXISTS");
        verify(references, never()).hasReferences(any());

        when(workspaces.findOpenByAssetId("asset-1")).thenReturn(Optional.empty());
        when(references.hasReferences("asset-1")).thenReturn(true);
        CodeWorkspaceConflictException inUse = org.junit.jupiter.api.Assertions.assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.delete("asset-1", 3L)
        );
        org.assertj.core.api.Assertions.assertThat(inUse.getReasonCode())
                .isEqualTo("CODE_ASSET_IN_USE");
        org.assertj.core.api.Assertions.assertThat(owned.getDeleted()).isFalse();
        verify(assets, never()).saveAndFlush(any());
        verify(audit, never()).assetDeleted(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void softDeleteHidesForeignAssetBeforeRevisionAndReferenceChecks() {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        CodeVersionRepository versions = mock(CodeVersionRepository.class);
        CodeWorkspaceRepository workspaces = mock(CodeWorkspaceRepository.class);
        CodeAssetReferenceChecker references = mock(CodeAssetReferenceChecker.class);
        CodeAssetAuditService audit = mock(CodeAssetAuditService.class);
        AuthContext auth = mock(AuthContext.class);
        when(assets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(entity(8, 3L)));
        when(auth.currentUserId()).thenReturn(7);
        V2CodeAssetService service = service(
                assets, versions, workspaces, references, audit, auth
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                CodeAssetAccessException.class,
                () -> service.delete("asset-1", 999L)
        );

        verify(workspaces, never()).findOpenByAssetId(any());
        verify(references, never()).hasReferences(any());
        verify(assets, never()).saveAndFlush(any());
    }

    @Test
    void existingVersionRejectsDifferentProfileBeforeAnyPartialMutation() throws Exception {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        CodeVersionRepository versions = mock(CodeVersionRepository.class);
        CodeWorkspaceRepository workspaces = mock(CodeWorkspaceRepository.class);
        CodeAssetAuditService audit = mock(CodeAssetAuditService.class);
        AuthContext auth = mock(AuthContext.class);
        CodeAsset owned = entity(7, 3L);
        owned.setTrainingProfile("profile-a");
        owned.setRemark("old-remark");
        when(assets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(owned));
        when(versions.existsByAssetIdAndDeletedFalse("asset-1")).thenReturn(true);
        when(auth.currentUserId()).thenReturn(7);
        V2CodeAssetService service = service(assets, versions, workspaces, audit, auth);
        V2CodeAssetPatchRequest patch = new ObjectMapper().readValue(
                "{\"assetRevision\":3,\"trainingProfile\":\" profile-b \","
                        + "\"name\":\"Renamed\",\"remark\":\"new-remark\"}",
                V2CodeAssetPatchRequest.class
        );

        CodeWorkspaceConflictException conflict = org.junit.jupiter.api.Assertions.assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.patch("asset-1", patch)
        );

        org.assertj.core.api.Assertions.assertThat(conflict.getReasonCode())
                .isEqualTo("TRAINING_PROFILE_IMMUTABLE");
        org.assertj.core.api.Assertions.assertThat(owned.getTrainingProfile()).isEqualTo("profile-a");
        org.assertj.core.api.Assertions.assertThat(owned.getName()).isEqualTo("Trainer");
        org.assertj.core.api.Assertions.assertThat(owned.getRemark()).isEqualTo("old-remark");
        org.assertj.core.api.Assertions.assertThat(owned.getUpdatedAt()).isEqualTo(Instant.EPOCH);
        verify(versions).existsByAssetIdAndDeletedFalse("asset-1");
        verify(assets, never()).saveAndFlush(any());
        verify(audit, never()).assetUpdated(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void existingVersionRejectsExplicitNullProfileBeforeAnyPartialMutation() throws Exception {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        CodeVersionRepository versions = mock(CodeVersionRepository.class);
        CodeWorkspaceRepository workspaces = mock(CodeWorkspaceRepository.class);
        CodeAssetAuditService audit = mock(CodeAssetAuditService.class);
        AuthContext auth = mock(AuthContext.class);
        CodeAsset owned = entity(7, 3L);
        owned.setTrainingProfile("profile-a");
        when(assets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(owned));
        when(versions.existsByAssetIdAndDeletedFalse("asset-1")).thenReturn(true);
        when(auth.currentUserId()).thenReturn(7);
        V2CodeAssetService service = service(assets, versions, workspaces, audit, auth);
        V2CodeAssetPatchRequest patch = new ObjectMapper().readValue(
                "{\"assetRevision\":3,\"trainingProfile\":null,\"name\":\"Renamed\"}",
                V2CodeAssetPatchRequest.class
        );

        CodeWorkspaceConflictException conflict = org.junit.jupiter.api.Assertions.assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.patch("asset-1", patch)
        );

        org.assertj.core.api.Assertions.assertThat(conflict.getReasonCode())
                .isEqualTo("TRAINING_PROFILE_IMMUTABLE");
        org.assertj.core.api.Assertions.assertThat(owned.getTrainingProfile()).isEqualTo("profile-a");
        org.assertj.core.api.Assertions.assertThat(owned.getName()).isEqualTo("Trainer");
        org.assertj.core.api.Assertions.assertThat(owned.getUpdatedAt()).isEqualTo(Instant.EPOCH);
        verify(versions).existsByAssetIdAndDeletedFalse("asset-1");
        verify(assets, never()).saveAndFlush(any());
        verify(audit, never()).assetUpdated(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void profileCanChangeInAllDirectionsBeforeTheFirstVersion() throws Exception {
        assertProfileChangeWithoutVersions(null, " profile-a ", "profile-a");
        assertProfileChangeWithoutVersions("profile-a", null, null);
        assertProfileChangeWithoutVersions("profile-a", "profile-b", "profile-b");
    }

    @Test
    void sameProfileWithExistingVersionIsATrueNoOp() throws Exception {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        CodeVersionRepository versions = mock(CodeVersionRepository.class);
        CodeWorkspaceRepository workspaces = mock(CodeWorkspaceRepository.class);
        CodeAssetAuditService audit = mock(CodeAssetAuditService.class);
        AuthContext auth = mock(AuthContext.class);
        CodeAsset owned = entity(7, 3L);
        owned.setTrainingProfile("profile-a");
        when(assets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(owned));
        when(workspaces.findOpenByAssetId("asset-1")).thenReturn(Optional.empty());
        when(auth.currentUserId()).thenReturn(7);
        V2CodeAssetService service = service(assets, versions, workspaces, audit, auth);
        V2CodeAssetPatchRequest patch = new ObjectMapper().readValue(
                "{\"assetRevision\":3,\"trainingProfile\":\" profile-a \"}",
                V2CodeAssetPatchRequest.class
        );

        V2CodeAssetDto result = service.patch("asset-1", patch);

        org.assertj.core.api.Assertions.assertThat(result.trainingProfile()).isEqualTo("profile-a");
        org.assertj.core.api.Assertions.assertThat(result.assetRevision()).isEqualTo(3L);
        org.assertj.core.api.Assertions.assertThat(owned.getUpdatedAt()).isEqualTo(Instant.EPOCH);
        verify(versions, never()).existsByAssetIdAndDeletedFalse(any());
        verify(assets, never()).saveAndFlush(any());
        verify(audit, never()).assetUpdated(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void existingVersionStillAllowsOtherMetadataWithSameProfile() throws Exception {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        CodeVersionRepository versions = mock(CodeVersionRepository.class);
        CodeWorkspaceRepository workspaces = mock(CodeWorkspaceRepository.class);
        CodeAssetAuditService audit = mock(CodeAssetAuditService.class);
        AuthContext auth = mock(AuthContext.class);
        CodeAsset owned = entity(7, 3L);
        owned.setTrainingProfile("profile-a");
        owned.setRemark("old-remark");
        when(assets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(owned));
        when(assets.saveAndFlush(owned)).thenAnswer(invocation -> {
            owned.setRowVersion(4L);
            return owned;
        });
        when(workspaces.findOpenByAssetId("asset-1")).thenReturn(Optional.empty());
        when(auth.currentUserId()).thenReturn(7);
        V2CodeAssetService service = service(assets, versions, workspaces, audit, auth);
        V2CodeAssetPatchRequest patch = new ObjectMapper().readValue(
                "{\"assetRevision\":3,\"trainingProfile\":\" profile-a \","
                        + "\"name\":\"Renamed\",\"remark\":\"new-remark\"}",
                V2CodeAssetPatchRequest.class
        );

        V2CodeAssetDto result = service.patch("asset-1", patch);

        org.assertj.core.api.Assertions.assertThat(result.trainingProfile()).isEqualTo("profile-a");
        org.assertj.core.api.Assertions.assertThat(owned.getName()).isEqualTo("Renamed");
        org.assertj.core.api.Assertions.assertThat(owned.getRemark()).isEqualTo("new-remark");
        org.assertj.core.api.Assertions.assertThat(result.assetRevision()).isEqualTo(4L);
        verify(versions, never()).existsByAssetIdAndDeletedFalse(any());
        verify(assets).saveAndFlush(owned);
        verify(audit).assetUpdated("asset-1", 4L);
    }

    @Test
    void exposesZeroOrOneOpenWorkspaceAndCreatesThroughWorkspaceService() throws Exception {
        V2CodeAssetService service = mock(V2CodeAssetService.class);
        V2CodeWorkspaceDto workspace = workspaceDto("workspace-1", false);
        when(service.openWorkspaces("asset-1")).thenReturn(List.of(workspace));
        when(service.openWorkspace(eq("asset-1"), any())).thenReturn(workspace);
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/v2/code-assets/asset-1/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("workspace-1"))
                .andExpect(jsonPath("$[0].ownerUserId").doesNotExist());
        mvc.perform(post("/api/v2/code-assets/asset-1/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersionId\":null}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.readOnly").value(false));
    }

    private static MockMvc mvc(V2CodeAssetService service) {
        return MockMvcBuilders
                .standaloneSetup(new V2CodeAssetController(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();
    }

    private static V2CodeAssetDto asset(String id, long revision, boolean open) {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        return new V2CodeAssetDto(
                id,
                "Trainer",
                "PYTORCH",
                "training",
                "python3.11",
                "src/train.py",
                "CUSTOM",
                "draft",
                revision,
                now,
                now,
                open,
                false
        );
    }

    private static V2CodeAssetService service(
            CodeAssetRepository assets,
            CodeVersionRepository versions,
            CodeWorkspaceRepository workspaces,
            CodeAssetAuditService audit,
            AuthContext auth
    ) {
        return service(
                assets,
                versions,
                workspaces,
                mock(CodeAssetReferenceChecker.class),
                audit,
                auth
        );
    }

    private static V2CodeAssetService service(
            CodeAssetRepository assets,
            CodeVersionRepository versions,
            CodeWorkspaceRepository workspaces,
            CodeAssetReferenceChecker references,
            CodeAssetAuditService audit,
            AuthContext auth
    ) {
        return new V2CodeAssetService(
                assets,
                versions,
                workspaces,
                mock(CodeWorkspaceService.class),
                references,
                audit,
                new CodePathPolicy(),
                mock(TrainingPlanRegistry.class),
                new CodeAccessPolicy(auth)
        );
    }

    private static void assertProfileChangeWithoutVersions(
            String currentProfile,
            String requestedProfile,
            String expectedProfile
    ) throws Exception {
        CodeAssetRepository assets = mock(CodeAssetRepository.class);
        CodeVersionRepository versions = mock(CodeVersionRepository.class);
        CodeWorkspaceRepository workspaces = mock(CodeWorkspaceRepository.class);
        CodeAssetAuditService audit = mock(CodeAssetAuditService.class);
        AuthContext auth = mock(AuthContext.class);
        CodeAsset owned = entity(7, 3L);
        owned.setTrainingProfile(currentProfile);
        when(assets.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(owned));
        when(versions.existsByAssetIdAndDeletedFalse("asset-1")).thenReturn(false);
        when(assets.saveAndFlush(owned)).thenAnswer(invocation -> {
            owned.setRowVersion(4L);
            return owned;
        });
        when(workspaces.findOpenByAssetId("asset-1")).thenReturn(Optional.empty());
        when(auth.currentUserId()).thenReturn(7);
        V2CodeAssetService service = service(assets, versions, workspaces, audit, auth);
        String profileJson = requestedProfile == null
                ? "null"
                : new ObjectMapper().writeValueAsString(requestedProfile);
        V2CodeAssetPatchRequest patch = new ObjectMapper().readValue(
                "{\"assetRevision\":3,\"trainingProfile\":" + profileJson + "}",
                V2CodeAssetPatchRequest.class
        );

        V2CodeAssetDto result = service.patch("asset-1", patch);

        org.assertj.core.api.Assertions.assertThat(result.trainingProfile()).isEqualTo(expectedProfile);
        org.assertj.core.api.Assertions.assertThat(result.assetRevision()).isEqualTo(4L);
        verify(versions).existsByAssetIdAndDeletedFalse("asset-1");
        verify(assets).saveAndFlush(owned);
        verify(audit).assetUpdated("asset-1", 4L);
    }

    private static CodeAsset entity(int ownerId, long revision) {
        CodeAsset asset = new CodeAsset();
        asset.setId("asset-1");
        asset.setName("Trainer");
        asset.setOwnerUserId(ownerId);
        asset.setRowVersion(revision);
        asset.setCreatedAt(Instant.EPOCH);
        asset.setUpdatedAt(Instant.EPOCH);
        asset.setDeleted(false);
        return asset;
    }

    private static V2CodeWorkspaceDto workspaceDto(String id, boolean readOnly) {
        return new V2CodeWorkspaceDto(
                id, "asset-1", null, null, readOnly ? "ABANDONED" : "OPEN", 0L,
                Instant.EPOCH, Instant.EPOCH, readOnly ? Instant.EPOCH : null, readOnly
        );
    }
}
