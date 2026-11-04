package com.tss.platform.service;

import com.tss.platform.dto.CodeVersionApprovalDto;
import com.tss.platform.dto.CodeVersionListItemDto;
import com.tss.platform.dto.CodeVersionTrainingCheckDto;
import com.tss.platform.entity.CodeApprovalRecord;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.model.CodeApprovalStatus;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.training.plan.TrainingPlanRegistry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class CodeVersionService {

    private final CodeVersionRepository codeVersionRepository;
    private final CodeAssetRepository codeAssetRepository;
    private final AuthContext authContext;
    private final CodeValidationService validationService;
    private final CodeApprovalService approvalService;
    private final CodeArtifactResolver artifactResolver;
    private final TrainingPlanRegistry trainingPlanRegistry;

    public CodeVersionService(
            CodeVersionRepository codeVersionRepository,
            CodeAssetRepository codeAssetRepository,
            AuthContext authContext,
            CodeValidationService validationService,
            CodeApprovalService approvalService,
            CodeArtifactResolver artifactResolver,
            TrainingPlanRegistry trainingPlanRegistry
    ) {
        this.codeVersionRepository = codeVersionRepository;
        this.codeAssetRepository = codeAssetRepository;
        this.authContext = authContext;
        this.validationService = validationService;
        this.approvalService = approvalService;
        this.artifactResolver = artifactResolver;
        this.trainingPlanRegistry = trainingPlanRegistry;
    }

    public CodeVersionApprovalDto approve(String codeVersionId) {
        String versionId = requireVersionId(codeVersionId);
        CodeApprovalRecord record = approvalService.decide(
                versionId,
                CodeApprovalService.Decision.APPROVE,
                null
        );
        return CodeVersionApprovalDto.builder()
                .codeVersionId(versionId)
                .approvalStatus(record.getDecision())
                .build();
    }

    public List<CodeVersionListItemDto> listApprovedForTraining() {
        Integer currentUserId = authContext.currentUserId();
        if (currentUserId == null) {
            throw new CodeAssetAccessException();
        }
        List<CodeVersionListItemDto> items = new ArrayList<>();
        // 本人代码资产 + 演示代码资产（ownerUserId=0，全局共享、只读）
        List<CodeAsset> assets = new ArrayList<>(codeAssetRepository
                .findByOwnerUserIdAndDeletedFalseOrderByCreatedAtDesc(currentUserId));
        assets.addAll(codeAssetRepository.findByIsDemoTrueAndDeletedFalseOrderByCreatedAtDesc());
        Set<String> visitedAssetIds = new HashSet<>();
        for (CodeAsset asset : assets) {
            boolean isDemo = Boolean.TRUE.equals(asset.getIsDemo());
            if (!isDemo && !Objects.equals(currentUserId, asset.getOwnerUserId())) {
                continue;
            }
            if (!visitedAssetIds.add(asset.getId())) {
                continue;
            }
            for (CodeVersion version : codeVersionRepository
                    .findByAssetIdAndDeletedFalseOrderByCreatedAtDesc(asset.getId())) {
                if (!Objects.equals(asset.getId(), version.getAssetId())
                        || (!isDemo && !Objects.equals(currentUserId, version.getOwnerUserId()))
                        || !"READY".equals(version.getStatus())
                        || !CodeApprovalStatus.APPROVED.equals(
                                version.getApprovalStatus())) {
                    continue;
                }
                try {
                    artifactResolver.resolve(version.getId(), currentUserId);
                } catch (CodeAssetAccessException | CodeValidationException exception) {
                    continue;
                }
                items.add(CodeVersionListItemDto.builder()
                        .codeVersionId(version.getId())
                        .codeAssetId(asset.getId())
                        .codeAssetName(asset.getName())
                        .version(version.getVersion())
                        .fileName(version.getFileName())
                        .trainingProfile(version.getTrainingProfile())
                        .approvalStatus(version.getApprovalStatus())
                        .status(version.getStatus())
                        .isDemo(isDemo)
                        .build());
            }
        }
        items.sort(Comparator.comparing(
                        CodeVersionListItemDto::getCodeAssetName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                )
                .thenComparing(
                        CodeVersionListItemDto::getVersion,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                ));
        return items;
    }

    public ResolvedCodeArtifact requireApprovedForTraining(String codeVersionId) {
        String versionId = requireVersionId(codeVersionId);
        Integer currentUserId = authContext.currentUserId();
        if (currentUserId == null) {
            throw new IllegalArgumentException("训练代码版本不存在或无权限");
        }
        try {
            return artifactResolver.resolve(versionId, currentUserId);
        } catch (CodeAssetAccessException exception) {
            throw new IllegalArgumentException("训练代码版本不存在或无权限");
        } catch (CodeValidationException exception) {
            throw new IllegalArgumentException("训练代码版本未满足准入条件");
        }
    }

    /**
     * Compatibility validation endpoint. It refreshes validation evidence only;
     * administrator approval is a separate explicit decision.
     */
    public CodeVersionTrainingCheckDto trainingCheck(
            String codeVersionId,
            String trainingProfile
    ) {
        String versionId = requireVersionId(codeVersionId);
        Integer currentUserId = currentUserIdOrNotFound();
        requireCurrentOwnerVersion(versionId, currentUserId);
        String profile = trainingProfile == null ? "" : trainingProfile.trim();
        Instant checkedAt = Instant.now();
        CodeValidationResult validation = validationService.validateVersion(versionId);
        CodeVersion version = requireCurrentOwnerVersion(versionId, currentUserId);

        List<String> reasons = new ArrayList<>();
        if (!validation.passed()) {
            reasons.add(validation.reasonCode() == null
                    ? "VALIDATION_FAILED"
                    : validation.reasonCode());
        }
        try {
            trainingPlanRegistry.requireEnabled(profile, null);
        } catch (IllegalArgumentException exception) {
            reasons.add("TRAINING_PLAN_UNSUPPORTED");
        }
        if (version.getTrainingProfile() == null
                || !profile.equals(version.getTrainingProfile().trim())) {
            reasons.add("TRAINING_PROFILE_MISMATCH");
        }
        return CodeVersionTrainingCheckDto.builder()
                .codeVersionId(versionId)
                .trainingProfile(profile)
                .trainingProfileDisplayName(profile)
                .passed(reasons.isEmpty())
                .reused(validation.reused())
                .approvalStatus(version.getApprovalStatus())
                .reasons(List.copyOf(reasons))
                .checkedAt(checkedAt)
                .build();
    }

    private CodeVersion requireCurrentOwnerVersion(
            String versionId,
            Integer currentUserId
    ) {
        String assetId = codeVersionRepository.findAssetIdByIdAndDeletedFalse(versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = codeAssetRepository.findByIdAndDeletedFalse(assetId)
                .orElseThrow(CodeAssetAccessException::new);
        if (!Objects.equals(currentUserId, asset.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
        CodeVersion version = codeVersionRepository
                .findByIdAndAssetIdAndDeletedFalse(versionId, assetId)
                .orElseThrow(CodeAssetAccessException::new);
        if (!Objects.equals(assetId, version.getAssetId())
                || !Objects.equals(currentUserId, version.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
        return version;
    }

    private Integer currentUserIdOrNotFound() {
        try {
            Integer currentUserId = authContext.currentUserId();
            if (currentUserId != null) {
                return currentUserId;
            }
        } catch (RuntimeException ignored) {
            // Hide authentication provider details behind the resource boundary.
        }
        throw new CodeAssetAccessException();
    }

    private static String requireVersionId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("codeVersionId 不能为空");
        }
        return value.trim();
    }
}
