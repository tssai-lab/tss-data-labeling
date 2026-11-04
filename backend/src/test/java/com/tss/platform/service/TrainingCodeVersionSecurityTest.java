package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.CreateTrainingExperimentRequest;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.model.CodeApprovalStatus;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.training.TrainingExecutorRouter;
import com.tss.platform.training.plan.TrainingOutputValidator;
import com.tss.platform.training.plan.TrainingPlanRegistry;
import com.tss.platform.training.plan.TrainingRunSpecFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrainingCodeVersionSecurityTest {

    private CodeVersionRepository codeVersionRepo;
    private CodeAssetRepository codeAssetRepo;
    private CodeVersionService codeVersionService;
    private AuthContext authContext;
    private CodeValidationService codeValidationService;
    private CodeApprovalService codeApprovalService;
    private CodeArtifactResolver codeArtifactResolver;

    private TrainingExperimentService trainingExperimentService;
    private TrainingRunSpecFactory trainingRunSpecFactory;
    private DatasetVersionRepository datasetVersionRepo;
    private DatasetAssetRepository datasetAssetRepo;
    private ModelVersionRepository modelVersionRepo;
    private ModelAssetRepository modelAssetRepo;
    private ModelArtifactAttestationService modelArtifactAttestationService;

    @BeforeEach
    void setUp() {
        codeVersionRepo = mock(CodeVersionRepository.class);
        codeAssetRepo = mock(CodeAssetRepository.class);
        authContext = mock(AuthContext.class);
        codeValidationService = mock(CodeValidationService.class);
        codeApprovalService = mock(CodeApprovalService.class);
        codeArtifactResolver = mock(CodeArtifactResolver.class);
        codeVersionService = new CodeVersionService(
                codeVersionRepo,
                codeAssetRepo,
                authContext,
                codeValidationService,
                codeApprovalService,
                codeArtifactResolver,
                mock(TrainingPlanRegistry.class)
        );

        datasetVersionRepo = mock(DatasetVersionRepository.class);
        datasetAssetRepo = mock(DatasetAssetRepository.class);
        modelVersionRepo = mock(ModelVersionRepository.class);
        modelAssetRepo = mock(ModelAssetRepository.class);
        trainingRunSpecFactory = mock(TrainingRunSpecFactory.class);
        modelArtifactAttestationService = mock(ModelArtifactAttestationService.class);
        trainingExperimentService = new TrainingExperimentService(
                mock(TrainingExperimentVersionRepository.class),
                modelVersionRepo,
                modelAssetRepo,
                modelArtifactAttestationService,
                datasetVersionRepo,
                datasetAssetRepo,
                codeVersionRepo,
                codeAssetRepo,
                codeVersionService,
                trainingRunSpecFactory,
                mock(TrainingOutputValidator.class),
                mock(TrainingExecutorRouter.class),
                mock(JobScheduler.class),
                new org.springframework.transaction.support.TransactionTemplate(
                        mock(org.springframework.transaction.PlatformTransactionManager.class)),
                new ObjectMapper(),
                authContext,
                mock(MlflowTrackingService.class),
                mock(com.tss.platform.training.TrainingFailureDiagnosticService.class),
                new TrainingSubmissionGuard(null, new ObjectMapper())
        );

        doNothing().when(authContext).requireOwnerAccess(anyInt(), org.mockito.ArgumentMatchers.anyString());
        when(authContext.currentUserId()).thenReturn(1);
        when(codeArtifactResolver.resolve(anyString(), anyInt()))
                .thenReturn(resolved("code-ver-approved"));
        when(modelArtifactAttestationService.attestReady(anyString()))
                .thenAnswer(invocation -> {
                    String versionId = invocation.getArgument(0);
                    ModelVersion version = modelVersionRepo
                            .findByIdAndDeletedFalse(versionId)
                            .orElseThrow();
                    ModelAsset asset = modelAssetRepo
                            .findByIdAndDeletedFalse(version.getAssetId())
                            .orElseThrow();
                    version.setArtifactAttestedAt(Instant.now());
                    return new ModelArtifactAttestationService.AttestedArtifact(
                            version,
                            asset,
                            1L,
                            "a".repeat(64),
                            null
                    );
                });
    }

    @Test
    void pendingCodeVersionRejectedForTraining() {
        CodeVersion pending = readyCodeVersion("code-ver-pending", CodeApprovalStatus.PENDING);
        when(codeArtifactResolver.resolve(pending.getId(), 1)).thenThrow(
                new CodeValidationException("APPROVAL_REQUIRED", "internal approval detail")
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> codeVersionService.requireApprovedForTraining(pending.getId())
        );
        assertEquals("训练代码版本未满足准入条件", error.getMessage());
        org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("internal"));
    }

    @Test
    void approvedCodeVersionPassesApprovalCheck() {
        CodeVersion approved = readyCodeVersion("code-ver-approved", CodeApprovalStatus.APPROVED);

        assertDoesNotThrow(() -> codeVersionService.requireApprovedForTraining(approved.getId()));
        verify(codeArtifactResolver).resolve(approved.getId(), 1);
    }

    @Test
    void missingCodeVersionRejectedForTraining() {
        when(codeArtifactResolver.resolve("missing-code", 1))
                .thenThrow(new CodeAssetAccessException());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> codeVersionService.requireApprovedForTraining("missing-code")
        );
        assertEquals("训练代码版本不存在或无权限", error.getMessage());
    }

    @Test
    void administratorCannotRequireAnotherOwnersCodeForTraining() {
        when(authContext.currentUserId()).thenReturn(99);
        when(authContext.isAdmin()).thenReturn(true);
        when(codeArtifactResolver.resolve("owner-7-version", 99))
                .thenThrow(new CodeAssetAccessException());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> codeVersionService.requireApprovedForTraining("owner-7-version")
        );

        assertEquals("训练代码版本不存在或无权限", error.getMessage());
        verify(codeArtifactResolver).resolve("owner-7-version", 99);
    }

    @Test
    void trainingCheckRevalidatesButNeverApproves() {
        CodeVersion pending = readyCodeVersion("code-ver-pending", CodeApprovalStatus.PENDING);
        pending.setTrainingProfile("image_text_consistency_fusion_logreg");
        when(codeValidationService.validateVersion(pending.getId())).thenReturn(
                new CodeValidationResult(
                        CodeArtifactAssembler.POLICY_VERSION,
                        "a".repeat(64),
                        "PASSED",
                        null,
                        "Code artifact validation passed",
                        1,
                        true
                )
        );
        when(codeVersionRepo.findAssetIdByIdAndDeletedFalse(pending.getId()))
                .thenReturn(Optional.of(pending.getAssetId()));
        when(codeAssetRepo.findByIdAndDeletedFalse(pending.getAssetId()))
                .thenReturn(Optional.of(codeAsset(pending.getAssetId())));
        when(codeVersionRepo.findByIdAndAssetIdAndDeletedFalse(
                pending.getId(), pending.getAssetId()
        ))
                .thenReturn(Optional.of(pending));

        var result = codeVersionService.trainingCheck(
                pending.getId(),
                "image_text_consistency_fusion_logreg"
        );

        assertEquals(true, result.getPassed());
        assertEquals(true, result.getReused());
        assertEquals(CodeApprovalStatus.PENDING, result.getApprovalStatus());
        assertEquals(CodeApprovalStatus.PENDING, pending.getApprovalStatus());
        verify(codeApprovalService, never()).decide(
                anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        );
        verify(codeVersionRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void administratorCannotUseLegacyTrainingCheckAcrossOwners() {
        when(authContext.currentUserId()).thenReturn(99);
        when(authContext.isAdmin()).thenReturn(true);
        when(codeVersionRepo.findAssetIdByIdAndDeletedFalse("owner-7-version"))
                .thenReturn(Optional.of("owner-7-asset"));
        CodeAsset otherOwner = codeAsset("owner-7-asset");
        otherOwner.setOwnerUserId(7);
        when(codeAssetRepo.findByIdAndDeletedFalse("owner-7-asset"))
                .thenReturn(Optional.of(otherOwner));

        assertThrows(
                CodeAssetAccessException.class,
                () -> codeVersionService.trainingCheck(
                        "owner-7-version",
                        "image_text_consistency_fusion_logreg"
                )
        );

        verify(codeValidationService, never()).validateVersion(anyString());
        verify(codeVersionRepo, never()).findByIdAndAssetIdAndDeletedFalse(
                anyString(), anyString()
        );
    }

    @Test
    void legacyApproveDelegatesToAdministratorOnlyApprovalService() {
        when(codeApprovalService.decide(
                "secret-version", CodeApprovalService.Decision.APPROVE, null
        )).thenThrow(new CodeApprovalForbiddenException());

        assertThrows(
                CodeApprovalForbiddenException.class,
                () -> codeVersionService.approve("secret-version")
        );

        verify(codeVersionRepo, never()).findByIdAndDeletedFalse(anyString());
        verify(codeVersionRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void approvedListUsesOnlyCurrentOwnerCandidatesAndResolverEligibility() {
        when(authContext.isAdmin()).thenReturn(true);
        CodeAsset ownerAsset = codeAsset("asset-1");
        CodeVersion eligible = readyCodeVersion(
                "code-ver-eligible", CodeApprovalStatus.APPROVED
        );
        eligible.setVersion("v1");
        eligible.setFileName("eligible.zip");
        CodeVersion stale = readyCodeVersion(
                "code-ver-stale", CodeApprovalStatus.APPROVED
        );
        stale.setVersion("v2");
        stale.setFileName("stale.zip");
        CodeVersion crossOwner = readyCodeVersion(
                "code-ver-cross", CodeApprovalStatus.APPROVED
        );
        crossOwner.setOwnerUserId(2);
        when(codeAssetRepo.findByOwnerUserIdAndDeletedFalseOrderByCreatedAtDesc(1))
                .thenReturn(List.of(ownerAsset));
        when(codeVersionRepo.findByAssetIdAndDeletedFalseOrderByCreatedAtDesc("asset-1"))
                .thenReturn(List.of(eligible, stale, crossOwner));
        when(codeArtifactResolver.resolve(eligible.getId(), 1))
                .thenReturn(resolved(eligible.getId()));
        when(codeArtifactResolver.resolve(stale.getId(), 1)).thenThrow(
                new CodeValidationException("APPROVAL_EVIDENCE_STALE", "internal stale detail")
        );

        var result = codeVersionService.listApprovedForTraining();

        assertEquals(1, result.size());
        assertEquals(eligible.getId(), result.get(0).getCodeVersionId());
        verify(codeArtifactResolver, never()).resolve(crossOwner.getId(), 1);
    }

    @Test
    void missingBaseModelVersionRejectedOnCreateExperiment() {
        CodeVersion approved = readyCodeVersion("code-ver-approved", CodeApprovalStatus.APPROVED);
        CodeAsset asset = codeAsset("asset-1");
        asset.setTrainingProfile("image_text_consistency_fusion_logreg");
        when(codeVersionRepo.findByIdAndDeletedFalse(approved.getId())).thenReturn(Optional.of(approved));
        when(codeAssetRepo.findByIdAndDeletedFalse(approved.getAssetId())).thenReturn(Optional.of(asset));

        CreateTrainingExperimentRequest req = new CreateTrainingExperimentRequest();
        req.setCodeVersionId(approved.getId());
        req.setDatasetVersionId("dataset-ver-1");
        req.setTrainingProfile("image_text_consistency_fusion_logreg");
        req.setHyperParams(java.util.Map.of());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> trainingExperimentService.createExperiment(req)
        );
        assertEquals("baseModelVersionId 不能为空", error.getMessage());
    }

    @Test
    void conflictingBaseAndLegacyModelVersionRejected() {
        CreateTrainingExperimentRequest req = new CreateTrainingExperimentRequest();
        req.setCodeVersionId("code-ver-approved");
        req.setDatasetVersionId("dataset-ver-1");
        req.setTrainingProfile("image_text_consistency_fusion_logreg");
        req.setBaseModelVersionId("model-ver-a");
        req.setModelVersionId("model-ver-b");
        req.setHyperParams(java.util.Map.of());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> trainingExperimentService.createExperiment(req)
        );
        assertEquals("baseModelVersionId 与 modelVersionId 不一致", error.getMessage());
    }

    @Test
    void trainingProfileMismatchRejectedOnCreateExperiment() {
        CodeVersion approved = readyCodeVersion("code-ver-approved", CodeApprovalStatus.APPROVED);
        CodeAsset asset = codeAsset("asset-1");
        asset.setTrainingProfile("other_profile");
        ModelVersion modelVersion = readyModelVersion("model-ver-1");
        when(codeVersionRepo.findByIdAndDeletedFalse(approved.getId())).thenReturn(Optional.of(approved));
        when(codeAssetRepo.findByIdAndDeletedFalse(approved.getAssetId())).thenReturn(Optional.of(asset));
        when(modelVersionRepo.findByIdAndDeletedFalse(modelVersion.getId())).thenReturn(Optional.of(modelVersion));
        when(modelAssetRepo.findByIdAndDeletedFalse(modelVersion.getAssetId())).thenReturn(Optional.of(modelAsset()));
        when(trainingRunSpecFactory.create(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("code trainingProfile does not match selected plan"));

        CreateTrainingExperimentRequest req = new CreateTrainingExperimentRequest();
        req.setCodeVersionId(approved.getId());
        req.setDatasetVersionId("dataset-ver-1");
        req.setBaseModelVersionId(modelVersion.getId());
        req.setTrainingProfile("image_text_consistency_fusion_logreg");
        req.setHyperParams(java.util.Map.of());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> trainingExperimentService.createExperiment(req)
        );
        assertEquals("code trainingProfile does not match selected plan", error.getMessage());
    }

    @Test
    void missingDatasetVersionRejectedOnCreateExperiment() {
        CodeVersion approved = readyCodeVersion("code-ver-approved", CodeApprovalStatus.APPROVED);
        CodeAsset asset = codeAsset("asset-1");
        asset.setTrainingProfile("image_text_consistency_fusion_logreg");
        ModelVersion modelVersion = readyModelVersion("model-ver-1");
        when(codeVersionRepo.findByIdAndDeletedFalse(approved.getId())).thenReturn(Optional.of(approved));
        when(codeAssetRepo.findByIdAndDeletedFalse(approved.getAssetId())).thenReturn(Optional.of(asset));
        when(modelVersionRepo.findByIdAndDeletedFalse(modelVersion.getId())).thenReturn(Optional.of(modelVersion));
        when(modelAssetRepo.findByIdAndDeletedFalse(modelVersion.getAssetId())).thenReturn(Optional.of(modelAsset()));
        when(datasetVersionRepo.findByIdAndDeletedFalse("missing-dataset")).thenReturn(Optional.empty());
        when(trainingRunSpecFactory.create(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("dataset version does not exist"));

        CreateTrainingExperimentRequest req = new CreateTrainingExperimentRequest();
        req.setCodeVersionId(approved.getId());
        req.setDatasetVersionId("missing-dataset");
        req.setBaseModelVersionId(modelVersion.getId());
        req.setTrainingProfile("image_text_consistency_fusion_logreg");
        req.setHyperParams(java.util.Map.of());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> trainingExperimentService.createExperiment(req)
        );
        assertEquals("dataset version does not exist", error.getMessage());
        verify(modelArtifactAttestationService).attestReady(modelVersion.getId());
    }

    private static CodeVersion readyCodeVersion(String id, String approvalStatus) {
        CodeVersion version = new CodeVersion();
        version.setId(id);
        version.setAssetId("asset-1");
        version.setStatus("READY");
        version.setApprovalStatus(approvalStatus);
        version.setStoragePath("users/1/codes/asset-1/v1/code.zip");
        version.setOwnerUserId(1);
        version.setDeleted(false);
        return version;
    }

    private static CodeAsset codeAsset(String id) {
        CodeAsset asset = new CodeAsset();
        asset.setId(id);
        asset.setName("Code asset");
        asset.setTrainingProfile("image_text_consistency_fusion_logreg");
        asset.setOwnerUserId(1);
        asset.setDeleted(false);
        return asset;
    }

    private static ModelVersion readyModelVersion(String id) {
        ModelVersion version = new ModelVersion();
        version.setId(id);
        version.setAssetId("model-asset-1");
        version.setStoragePath("users/1/models/model-asset-1/v1/weights.zip");
        version.setStatus("READY");
        version.setOwnerUserId(1);
        version.setDeleted(false);
        return version;
    }

    private static ModelAsset modelAsset() {
        ModelAsset asset = new ModelAsset();
        asset.setId("model-asset-1");
        asset.setOwnerUserId(1);
        asset.setDeleted(false);
        return asset;
    }

    private static ResolvedCodeArtifact resolved(String versionId) {
        return new ResolvedCodeArtifact(
                "asset-1",
                versionId,
                "TRAINING",
                "python:3.11",
                "train.py",
                "NLP",
                "image_text_consistency_fusion_logreg",
                "a".repeat(64),
                "validation-1",
                CodeArtifactAssembler.POLICY_VERSION,
                "approval-1",
                "users/1/codes/asset-1/versions/" + versionId + "/artifact.zip"
        );
    }
}
