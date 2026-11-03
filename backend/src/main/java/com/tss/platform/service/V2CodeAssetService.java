package com.tss.platform.service;

import com.tss.platform.dto.v2.V2CodeAssetCreateRequest;
import com.tss.platform.dto.v2.V2CodeAssetDto;
import com.tss.platform.dto.v2.V2CodeAssetPatchRequest;
import com.tss.platform.dto.v2.V2CodeWorkspaceDto;
import com.tss.platform.dto.v2.V2CodeWorkspaceOpenRequest;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeWorkspace;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.repository.CodeWorkspaceRepository;
import com.tss.platform.training.plan.TrainingPlanRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Shared V2 facade with explicit owner and administrator entry points. */
@Service
public class V2CodeAssetService {

    private final CodeAssetRepository assetRepository;
    private final CodeVersionRepository versionRepository;
    private final CodeWorkspaceRepository workspaceRepository;
    private final CodeWorkspaceService workspaceService;
    private final CodeAssetReferenceChecker referenceChecker;
    private final CodeAssetAuditService auditService;
    private final CodePathPolicy pathPolicy;
    private final TrainingPlanRegistry trainingPlanRegistry;
    private final CodeAccessPolicy accessPolicy;

    public V2CodeAssetService(
            CodeAssetRepository assetRepository,
            CodeVersionRepository versionRepository,
            CodeWorkspaceRepository workspaceRepository,
            CodeWorkspaceService workspaceService,
            CodeAssetReferenceChecker referenceChecker,
            CodeAssetAuditService auditService,
            CodePathPolicy pathPolicy,
            TrainingPlanRegistry trainingPlanRegistry,
            CodeAccessPolicy accessPolicy
    ) {
        this.assetRepository = assetRepository;
        this.versionRepository = versionRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceService = workspaceService;
        this.referenceChecker = referenceChecker;
        this.auditService = auditService;
        this.pathPolicy = pathPolicy;
        this.trainingPlanRegistry = trainingPlanRegistry;
        this.accessPolicy = accessPolicy;
    }

    @Transactional
    public V2CodeAssetDto create(V2CodeAssetCreateRequest request) {
        if (request == null) {
            throw invalid("INVALID_REQUEST", "Code asset request is required");
        }
        Integer ownerUserId = currentUserId();
        String name = AssetNamePolicy.normalizeRequired(request.name());
        requireUniqueAssetName(ownerUserId, name, null);
        Instant now = Instant.now();
        CodeAsset asset = new CodeAsset();
        asset.setId("code-asset-" + compactUuid());
        asset.setName(name);
        asset.setTrainingProfile(supportedTrainingProfile(request.trainingProfile()));
        asset.setPurpose(optional(request.purpose(), "purpose", 1024));
        asset.setRuntime(optional(request.runtime(), "runtime", 128));
        asset.setEntryScript(optionalPath(request.entryScript()));
        asset.setTrainingType(optional(request.trainingType(), "trainingType", 128));
        asset.setRemark(optional(request.remark(), "remark", 1024));
        asset.setOwnerUserId(ownerUserId);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset.setDeleted(false);
        CodeAsset saved = saveAsset(asset);
        auditService.assetCreated(saved.getId(), revision(saved));
        return toDto(saved, false);
    }

    @Transactional(readOnly = true)
    public List<V2CodeAssetDto> list() {
        Integer ownerUserId = currentUserId();
        // 本人代码资产 + 演示代码资产（is_demo=true，全局共享、只读）
        List<CodeAsset> assets = new ArrayList<>(
                assetRepository.findByOwnerUserIdAndDeletedFalseOrderByCreatedAtDesc(ownerUserId));
        assets.addAll(assetRepository.findByIsDemoTrueAndDeletedFalseOrderByCreatedAtDesc());
        Set<String> visited = new HashSet<>();
        return assets.stream()
                .filter(asset -> {
                    boolean isDemo = Boolean.TRUE.equals(asset.getIsDemo());
                    return visited.add(asset.getId())
                            && (isDemo || Objects.equals(ownerUserId, asset.getOwnerUserId()));
                })
                .map(asset -> toDto(
                        asset,
                        workspaceRepository.findOpenByAssetId(asset.getId()).isPresent()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public V2CodeAssetDto get(String assetId) {
        return get(assetId, CodeAccessScope.OWNER);
    }

    @Transactional(readOnly = true)
    public V2CodeAssetDto getAdmin(String assetId) {
        return get(assetId, CodeAccessScope.ADMIN);
    }

    private V2CodeAssetDto get(String assetId, CodeAccessScope scope) {
        if (scope != CodeAccessScope.ADMIN) {
            // 演示代码（is_demo=true）对所有用户只读可见
            CodeAsset demoAsset = assetRepository.findByIdAndDeletedFalse(assetId).orElse(null);
            if (demoAsset != null && Boolean.TRUE.equals(demoAsset.getIsDemo())) {
                return toDto(
                        demoAsset,
                        workspaceRepository.findOpenByAssetId(demoAsset.getId()).isPresent()
                );
            }
        }
        CodeAsset asset = requireAsset(assetId, false, scope);
        return toDto(asset, workspaceRepository.findOpenByAssetId(asset.getId()).isPresent());
    }

    @Transactional
    public V2CodeAssetDto patch(String assetId, V2CodeAssetPatchRequest request) {
        return patch(assetId, request, CodeAccessScope.OWNER);
    }

    @Transactional
    public V2CodeAssetDto patchAdmin(
            String assetId,
            V2CodeAssetPatchRequest request
    ) {
        return patch(assetId, request, CodeAccessScope.ADMIN);
    }

    private V2CodeAssetDto patch(
            String assetId,
            V2CodeAssetPatchRequest request,
            CodeAccessScope scope
    ) {
        CodeAsset asset = requireAsset(assetId, true, scope);
        if (request == null || request.getAssetRevision() == null
                || request.getAssetRevision() < 0) {
            throw invalid("ASSET_REVISION_REQUIRED", "assetRevision is required");
        }
        if (revision(asset) != request.getAssetRevision()) {
            throw conflict("ASSET_REVISION_CONFLICT", "Code asset revision is stale");
        }
        String normalizedTrainingProfile = asset.getTrainingProfile();
        boolean trainingProfileChanged = false;
        if (request.isTrainingProfilePresent()) {
            normalizedTrainingProfile = supportedTrainingProfile(request.getTrainingProfile());
            trainingProfileChanged = !Objects.equals(
                    asset.getTrainingProfile(), normalizedTrainingProfile
            );
            if (trainingProfileChanged
                    && versionRepository.existsByAssetIdAndDeletedFalse(asset.getId())) {
                throw conflict(
                        "TRAINING_PROFILE_IMMUTABLE",
                        "Code asset training profile is immutable after the first version"
                );
            }
        }
        if (request.isNamePresent()) {
            String name = AssetNamePolicy.normalizeRequired(request.getName());
            requireUniqueAssetName(asset.getOwnerUserId(), name, asset.getId());
            asset.setName(name);
        }
        if (trainingProfileChanged) {
            asset.setTrainingProfile(normalizedTrainingProfile);
        }
        if (request.isPurposePresent()) {
            asset.setPurpose(optional(request.getPurpose(), "purpose", 1024));
        }
        if (request.isRuntimePresent()) {
            asset.setRuntime(optional(request.getRuntime(), "runtime", 128));
        }
        if (request.isEntryScriptPresent()) {
            asset.setEntryScript(optionalPath(request.getEntryScript()));
        }
        if (request.isTrainingTypePresent()) {
            asset.setTrainingType(optional(request.getTrainingType(), "trainingType", 128));
        }
        if (request.isRemarkPresent()) {
            asset.setRemark(optional(request.getRemark(), "remark", 1024));
        }
        if (trainingProfileChanged || hasNonProfileChanges(request)) {
            asset.setUpdatedAt(Instant.now());
            asset = saveAsset(asset);
            auditService.assetUpdated(asset.getId(), revision(asset));
        }
        return toDto(asset, workspaceRepository.findOpenByAssetId(asset.getId()).isPresent());
    }

    @Transactional
    public void delete(String assetId, long expectedAssetRevision) {
        delete(assetId, expectedAssetRevision, CodeAccessScope.OWNER);
    }

    @Transactional
    public void deleteAdmin(String assetId, long expectedAssetRevision) {
        delete(assetId, expectedAssetRevision, CodeAccessScope.ADMIN);
    }

    private void delete(
            String assetId,
            long expectedAssetRevision,
            CodeAccessScope scope
    ) {
        CodeAsset asset = requireAsset(assetId, true, scope);
        if (expectedAssetRevision < 0 || revision(asset) != expectedAssetRevision) {
            throw conflict("ASSET_REVISION_CONFLICT", "Code asset revision is stale");
        }
        if (workspaceRepository.findOpenByAssetId(asset.getId()).isPresent()) {
            throw conflict("OPEN_WORKSPACE_EXISTS", "Code asset has an open workspace");
        }
        if (referenceChecker.hasReferences(asset.getId())) {
            throw conflict("CODE_ASSET_IN_USE", "Code asset is referenced by another module");
        }

        Instant now = Instant.now();
        asset.setDeleted(true);
        asset.setDeletedAt(now);
        asset.setUpdatedAt(now);
        CodeAsset saved = assetRepository.saveAndFlush(asset);
        auditService.assetDeleted(saved.getId(), revision(saved));
    }

    @Transactional(readOnly = true)
    public List<V2CodeWorkspaceDto> openWorkspaces(String assetId) {
        return openWorkspaces(assetId, CodeAccessScope.OWNER);
    }

    @Transactional(readOnly = true)
    public List<V2CodeWorkspaceDto> openWorkspacesAdmin(String assetId) {
        return openWorkspaces(assetId, CodeAccessScope.ADMIN);
    }

    private List<V2CodeWorkspaceDto> openWorkspaces(
            String assetId,
            CodeAccessScope scope
    ) {
        CodeAsset asset = requireAsset(assetId, false, scope);
        return workspaceRepository.findOpenByAssetId(asset.getId())
                .filter(workspace -> identityMatches(asset, workspace))
                .map(workspace -> List.of(toWorkspaceDto(workspace)))
                .orElseGet(List::of);
    }

    public V2CodeWorkspaceDto openWorkspace(
            String assetId,
            V2CodeWorkspaceOpenRequest request
    ) {
        return openWorkspace(assetId, request, CodeAccessScope.OWNER);
    }

    public V2CodeWorkspaceDto openWorkspaceAdmin(
            String assetId,
            V2CodeWorkspaceOpenRequest request
    ) {
        return openWorkspace(assetId, request, CodeAccessScope.ADMIN);
    }

    private V2CodeWorkspaceDto openWorkspace(
            String assetId,
            V2CodeWorkspaceOpenRequest request,
            CodeAccessScope scope
    ) {
        requireAsset(assetId, false, scope);
        String baseVersionId = request == null ? null : request.baseVersionId();
        return toWorkspaceDto(workspaceService.open(assetId, baseVersionId));
    }

    @Transactional(readOnly = true)
    public V2CodeWorkspaceDto requireOwnedWorkspace(String workspaceId) {
        return requireWorkspace(workspaceId, CodeAccessScope.OWNER);
    }

    @Transactional(readOnly = true)
    public V2CodeWorkspaceDto requireAdminWorkspace(String workspaceId) {
        return requireWorkspace(workspaceId, CodeAccessScope.ADMIN);
    }

    private V2CodeWorkspaceDto requireWorkspace(
            String workspaceId,
            CodeAccessScope scope
    ) {
        if (scope == CodeAccessScope.ADMIN) {
            accessPolicy.requireAdministrator();
        }
        String assetId = workspaceRepository.findAssetIdByIdAndDeletedFalse(workspaceId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = requireAsset(assetId, false, scope);
        CodeWorkspace workspace = workspaceRepository.findByIdAndDeletedFalse(workspaceId)
                .orElseThrow(CodeAssetAccessException::new);
        if (!identityMatches(asset, workspace)) {
            throw new CodeAssetAccessException();
        }
        return toWorkspaceDto(workspace);
    }

    public V2CodeWorkspaceDto abandonWorkspace(String workspaceId, long expectedRevision) {
        return abandonWorkspace(workspaceId, expectedRevision, CodeAccessScope.OWNER);
    }

    public V2CodeWorkspaceDto abandonWorkspaceAdmin(
            String workspaceId,
            long expectedRevision
    ) {
        return abandonWorkspace(workspaceId, expectedRevision, CodeAccessScope.ADMIN);
    }

    private V2CodeWorkspaceDto abandonWorkspace(
            String workspaceId,
            long expectedRevision,
            CodeAccessScope scope
    ) {
        requireWorkspace(workspaceId, scope);
        return toWorkspaceDto(workspaceService.abandon(workspaceId, expectedRevision));
    }

    private CodeAsset requireAsset(
            String assetId,
            boolean lock,
            CodeAccessScope scope
    ) {
        if (scope == CodeAccessScope.ADMIN) {
            accessPolicy.requireAdministrator();
        }
        CodeAsset asset = (lock
                ? assetRepository.findByIdAndDeletedFalseForUpdate(assetId)
                : assetRepository.findByIdAndDeletedFalse(assetId))
                .orElseThrow(CodeAssetAccessException::new);
        accessPolicy.require(scope, asset.getOwnerUserId());
        return asset;
    }

    private String optionalPath(String value) {
        String normalized = optional(value, "entryScript", 1024);
        return normalized == null ? null : pathPolicy.normalizeFilePath(normalized);
    }

    private String supportedTrainingProfile(String value) {
        String normalized = optional(value, "trainingProfile", 128);
        if (normalized == null) {
            return null;
        }
        try {
            trainingPlanRegistry.requireEnabled(normalized, null);
        } catch (IllegalArgumentException exception) {
            throw invalid(
                    "UNSUPPORTED_TRAINING_PROFILE",
                    "trainingProfile is not supported"
            );
        }
        return normalized;
    }

    private static boolean hasNonProfileChanges(V2CodeAssetPatchRequest request) {
        return request.isNamePresent()
                || request.isPurposePresent()
                || request.isRuntimePresent()
                || request.isEntryScriptPresent()
                || request.isTrainingTypePresent()
                || request.isRemarkPresent();
    }

    private Integer currentUserId() {
        return accessPolicy.currentUserId();
    }

    private void requireUniqueAssetName(
            Integer ownerUserId,
            String name,
            String excludedId
    ) {
        if (assetRepository.existsActiveNormalizedName(
                ownerUserId,
                name,
                excludedId
        )) {
            throw new AssetNameConflictException("code");
        }
    }

    private CodeAsset saveAsset(CodeAsset asset) {
        try {
            return assetRepository.saveAndFlush(asset);
        } catch (DataIntegrityViolationException exception) {
            if (AssetNamePolicy.isNameConstraintViolation(exception)) {
                throw new AssetNameConflictException("code");
            }
            throw exception;
        }
    }

    private static V2CodeAssetDto toDto(CodeAsset asset, boolean hasOpenWorkspace) {
        return new V2CodeAssetDto(
                asset.getId(),
                asset.getName(),
                asset.getTrainingProfile(),
                asset.getPurpose(),
                asset.getRuntime(),
                asset.getEntryScript(),
                asset.getTrainingType(),
                asset.getRemark(),
                revision(asset),
                asset.getCreatedAt(),
                asset.getUpdatedAt(),
                hasOpenWorkspace,
                asset.getIsDemo()
        );
    }

    public static V2CodeWorkspaceDto toWorkspaceDto(CodeWorkspace workspace) {
        boolean readOnly = !CodeWorkspace.STATUS_OPEN.equals(workspace.getStatus());
        return new V2CodeWorkspaceDto(
                workspace.getId(),
                workspace.getAssetId(),
                workspace.getBaseVersionId(),
                workspace.getClosedVersionId(),
                workspace.getStatus(),
                workspace.getRevision() == null ? 0 : workspace.getRevision(),
                workspace.getCreatedAt(),
                workspace.getUpdatedAt(),
                workspace.getClosedAt(),
                readOnly
        );
    }

    private static boolean identityMatches(CodeAsset asset, CodeWorkspace workspace) {
        return Objects.equals(asset.getId(), workspace.getAssetId())
                && Objects.equals(asset.getOwnerUserId(), workspace.getOwnerUserId());
    }

    private static long revision(CodeAsset asset) {
        return asset.getRowVersion() == null ? 0L : asset.getRowVersion();
    }

    private static String required(String value, String field, int maxLength) {
        String normalized = optional(value, field, maxLength);
        if (normalized == null) {
            throw invalid("INVALID_" + field.toUpperCase(), field + " is required");
        }
        return normalized;
    }

    private static String optional(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw invalid("INVALID_" + field.toUpperCase(), field + " is too long");
        }
        return normalized;
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static CodeValidationException invalid(String code, String message) {
        return new CodeValidationException(code, message);
    }

    private static CodeWorkspaceConflictException conflict(String code, String message) {
        return new CodeWorkspaceConflictException(code, message);
    }
}
