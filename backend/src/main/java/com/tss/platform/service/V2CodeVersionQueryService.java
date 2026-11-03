package com.tss.platform.service;

import com.tss.platform.dto.v2.V2CodeApprovalRequest;
import com.tss.platform.dto.v2.V2CodeApprovalResult;
import com.tss.platform.dto.v2.V2CodeConsumerManifest;
import com.tss.platform.dto.v2.V2CodeFileContent;
import com.tss.platform.dto.v2.V2CodeFileNode;
import com.tss.platform.dto.v2.V2CodeValidationResult;
import com.tss.platform.dto.v2.V2CodeVersionDto;
import com.tss.platform.entity.CodeApprovalRecord;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable code-version facade with explicit owner and administrator entry points. */
@Service
public class V2CodeVersionQueryService {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String ZIP_CONTENT_TYPE = "application/zip";

    private final CodeVersionRepository versionRepository;
    private final CodeAssetRepository assetRepository;
    private final CodeVersionArchiveReader archiveReader;
    private final CodeFilePolicy filePolicy;
    private final CodePathPolicy pathPolicy;
    private final CodeArtifactStorageService storageService;
    private final CodeValidationService validationService;
    private final CodeApprovalService approvalService;
    private final CodeArtifactResolver artifactResolver;
    private final CodeAssetAuditService auditService;
    private final AuthContext authContext;
    private final CodeAccessPolicy accessPolicy;

    public V2CodeVersionQueryService(
            CodeVersionRepository versionRepository,
            CodeAssetRepository assetRepository,
            CodeVersionArchiveReader archiveReader,
            CodeFilePolicy filePolicy,
            CodePathPolicy pathPolicy,
            CodeArtifactStorageService storageService,
            CodeValidationService validationService,
            CodeApprovalService approvalService,
            CodeArtifactResolver artifactResolver,
            CodeAssetAuditService auditService,
            AuthContext authContext,
            CodeAccessPolicy accessPolicy
    ) {
        this.versionRepository = versionRepository;
        this.assetRepository = assetRepository;
        this.archiveReader = archiveReader;
        this.filePolicy = filePolicy;
        this.pathPolicy = pathPolicy;
        this.storageService = storageService;
        this.validationService = validationService;
        this.approvalService = approvalService;
        this.artifactResolver = artifactResolver;
        this.auditService = auditService;
        this.authContext = authContext;
        this.accessPolicy = accessPolicy;
    }

    @Transactional(readOnly = true)
    public V2CodeVersionDto get(String versionId) {
        return get(versionId, CodeAccessScope.OWNER);
    }

    @Transactional(readOnly = true)
    public V2CodeVersionDto getAdmin(String versionId) {
        return get(versionId, CodeAccessScope.ADMIN);
    }

    private V2CodeVersionDto get(String versionId, CodeAccessScope scope) {
        return V2CodeVersionDto.from(versionScope(versionId, false, scope).version());
    }

    @Transactional(readOnly = true)
    public List<V2CodeVersionDto> listForAsset(String assetId) {
        return listForAsset(assetId, CodeAccessScope.OWNER);
    }

    @Transactional(readOnly = true)
    public List<V2CodeVersionDto> listForAssetAdmin(String assetId) {
        return listForAsset(assetId, CodeAccessScope.ADMIN);
    }

    private List<V2CodeVersionDto> listForAsset(
            String assetId,
            CodeAccessScope scope
    ) {
        CodeAsset asset = asset(assetId, false, scope);
        return versionRepository
                .findByAssetIdAndDeletedFalseOrderByCreatedAtDesc(asset.getId())
                .stream()
                .filter(version -> Objects.equals(asset.getId(), version.getAssetId()))
                .filter(version -> Objects.equals(asset.getOwnerUserId(), version.getOwnerUserId()))
                .map(V2CodeVersionDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<V2CodeFileNode> tree(String versionId, String prefix) {
        return tree(versionId, prefix, CodeAccessScope.OWNER);
    }

    @Transactional(readOnly = true)
    public List<V2CodeFileNode> treeAdmin(String versionId, String prefix) {
        return tree(versionId, prefix, CodeAccessScope.ADMIN);
    }

    private List<V2CodeFileNode> tree(
            String versionId,
            String prefix,
            CodeAccessScope scope
    ) {
        CodeVersion version = versionScope(versionId, false, scope).version();
        requirePreviewableVersion(version);
        requireStorageReference(version);
        String normalizedPrefix = pathPolicy.normalizeDirectoryPrefix(prefix);
        String prefixWithSlash = normalizedPrefix.isEmpty() ? "" : normalizedPrefix + "/";

        Map<String, V2CodeFileNode> direct = new LinkedHashMap<>();
        for (CodeArchiveEntry entry : archiveReader.list(version, version.getOwnerUserId())) {
            if (!entry.path().startsWith(prefixWithSlash)) {
                continue;
            }
            String remainder = entry.path().substring(prefixWithSlash.length());
            if (remainder.isEmpty()) {
                continue;
            }
            int slash = remainder.indexOf('/');
            if (slash >= 0) {
                String name = remainder.substring(0, slash);
                String directoryPath = prefixWithSlash + name;
                direct.putIfAbsent(
                        directoryPath,
                        V2CodeFileNode.directory(directoryPath, name)
                );
                continue;
            }
            CodeFileDescriptor descriptor = filePolicy.describeTrustedMetadata(
                    entry.path(), entry.uncompressedSize(), null
            );
            direct.put(entry.path(), V2CodeFileNode.file(descriptor, true));
        }
        return direct.values().stream()
                .sorted(Comparator
                        .comparing((V2CodeFileNode node) ->
                                "DIRECTORY".equals(node.nodeType()) ? 0 : 1)
                        .thenComparing(V2CodeFileNode::name)
                        .thenComparing(V2CodeFileNode::path))
                .toList();
    }

    @Transactional(readOnly = true)
    public V2CodeFileContent content(String versionId, String path) {
        return content(versionId, path, CodeAccessScope.OWNER);
    }

    @Transactional(readOnly = true)
    public V2CodeFileContent contentAdmin(String versionId, String path) {
        return content(versionId, path, CodeAccessScope.ADMIN);
    }

    private V2CodeFileContent content(
            String versionId,
            String path,
            CodeAccessScope scope
    ) {
        CodeVersion version = versionScope(versionId, false, scope).version();
        requirePreviewableVersion(version);
        requireStorageReference(version);
        String normalizedPath = pathPolicy.normalizeFilePath(path);
        CodeArchiveEntry entry = findEntry(version, normalizedPath);
        if (entry.uncompressedSize() > CodeFilePolicy.EDITABLE_LIMIT_BYTES) {
            throw new CodeContentTooLargeException();
        }
        byte[] bytes = archiveReader.read(
                version,
                version.getOwnerUserId(),
                entry,
                CodeFilePolicy.EDITABLE_LIMIT_BYTES
        );
        CodeFileDescriptor descriptor = filePolicy.describe(normalizedPath, bytes);
        return V2CodeFileContent.readOnly(descriptor, filePolicy.decodeEditable(bytes));
    }

    @Transactional(readOnly = true)
    public Download downloadFile(String versionId, String path) {
        return downloadFile(versionId, path, CodeAccessScope.OWNER);
    }

    @Transactional(readOnly = true)
    public Download downloadFileAdmin(String versionId, String path) {
        return downloadFile(versionId, path, CodeAccessScope.ADMIN);
    }

    private Download downloadFile(
            String versionId,
            String path,
            CodeAccessScope scope
    ) {
        CodeVersion version = versionScope(versionId, false, scope).version();
        requirePreviewableVersion(version);
        requireStorageReference(version);
        String normalizedPath = pathPolicy.normalizeFilePath(path);
        CodeArchiveEntry entry = findEntry(version, normalizedPath);
        byte[] bytes = archiveReader.read(
                version,
                version.getOwnerUserId(),
                entry,
                CodeVersionArchiveReader.MAX_CODE_UNCOMPRESSED_BYTES
        );
        CodeFileDescriptor descriptor = filePolicy.describeTrustedMetadata(
                normalizedPath, entry.uncompressedSize(), null
        );
        return new Download(descriptor.name(), descriptor.contentType(), bytes);
    }

    /**
     * Full immutable ZIP recovery is intentionally available without the preview
     * validation gate. Ownership and the object namespace are still mandatory.
     */
    @Transactional(readOnly = true)
    public Download downloadArchive(String versionId) {
        return downloadArchive(versionId, CodeAccessScope.OWNER);
    }

    @Transactional(readOnly = true)
    public Download downloadArchiveAdmin(String versionId) {
        return downloadArchive(versionId, CodeAccessScope.ADMIN);
    }

    private Download downloadArchive(
            String versionId,
            CodeAccessScope scope
    ) {
        CodeVersion version = versionScope(versionId, false, scope).version();
        requireStorageReference(version);
        StoredCodeArtifact stored = storageService.read(version.getStoragePath());
        if (!Objects.equals(version.getStoragePath(), stored.objectName())) {
            throw new CodeAssetAccessException();
        }
        if (validSha(version.getArtifactSha256())
                && !Objects.equals(version.getArtifactSha256(), stored.artifactSha256())) {
            throw validation(
                    "ARTIFACT_SHA256_MISMATCH",
                    "Code artifact hash does not match"
            );
        }
        String fileName = version.getFileName();
        if (fileName == null || fileName.isBlank()) {
            fileName = "code-version-" + version.getId() + ".zip";
        }
        return new Download(fileName, ZIP_CONTENT_TYPE, stored.bytes());
    }

    public V2CodeConsumerManifest consumerManifest(String versionId) {
        versionScope(versionId, false, CodeAccessScope.OWNER);
        return artifactResolver
                .resolve(versionId, currentUserId())
                .toConsumerManifest();
    }

    public V2CodeValidationResult validate(String versionId) {
        return validate(versionId, CodeAccessScope.OWNER);
    }

    public V2CodeValidationResult validateAdmin(String versionId) {
        return validate(versionId, CodeAccessScope.ADMIN);
    }

    private V2CodeValidationResult validate(
            String versionId,
            CodeAccessScope scope
    ) {
        versionScope(versionId, false, scope);
        CodeValidationResult result = validationService.validateVersion(versionId);
        if (!result.passed()) {
            if ("STORAGE_READ_FAILED".equals(result.reasonCode())) {
                throw new CodeArtifactStorageException();
            }
            throw validation(
                    safeReason(result.reasonCode(), "VALIDATION_FAILED"),
                    "Code artifact validation failed"
            );
        }
        return V2CodeValidationResult.from(result);
    }

    /** Approval deliberately delegates without owner lookup so admin authority is checked first. */
    public V2CodeApprovalResult approve(
            String versionId,
            V2CodeApprovalRequest request
    ) {
        approvalService.requireAdministratorAuthority();
        if (request == null || request.decision() == null || request.decision().isBlank()) {
            throw validation("APPROVAL_DECISION_REQUIRED", "Approval decision is required");
        }
        CodeApprovalService.Decision decision;
        try {
            decision = CodeApprovalService.Decision.valueOf(
                    request.decision().trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw validation("APPROVAL_DECISION_INVALID", "Approval decision is invalid");
        }
        try {
            CodeApprovalService.ApprovalExpectation expectation = null;
            if (decision != CodeApprovalService.Decision.REVOKE) {
                expectation = approvalExpectation(request);
            }
            CodeApprovalRecord record = approvalService.decide(
                    versionId, decision, request.reason(), expectation
            );
            return V2CodeApprovalResult.from(record);
        } catch (CodeValidationException exception) {
            if (isApprovalStateConflict(exception.getReasonCode())) {
                throw new CodeWorkspaceConflictException(
                        exception.getReasonCode(),
                        "Code version approval state has changed"
                );
            }
            throw exception;
        }
    }

    @Transactional
    public V2CodeVersionDto deprecate(String versionId) {
        return deprecate(versionId, CodeAccessScope.OWNER);
    }

    @Transactional
    public V2CodeVersionDto deprecateAdmin(String versionId) {
        return deprecate(versionId, CodeAccessScope.ADMIN);
    }

    private V2CodeVersionDto deprecate(
            String versionId,
            CodeAccessScope accessScope
    ) {
        VersionScope scope = versionScope(versionId, true, accessScope);
        CodeVersion version = scope.version();
        if ("DEPRECATED".equals(version.getStatus())) {
            return V2CodeVersionDto.from(version);
        }
        if (!"READY".equals(version.getStatus())) {
            throw lifecycleConflict();
        }
        Instant now = Instant.now();
        version.setStatus("DEPRECATED");
        version.setDeprecatedAt(now);
        version.setUpdatedAt(now);
        versionRepository.saveAndFlush(version);
        auditService.deprecated(scope.asset().getId(), version.getId());
        return V2CodeVersionDto.from(version);
    }

    @Transactional
    public V2CodeVersionDto archive(String versionId) {
        return archive(versionId, CodeAccessScope.OWNER);
    }

    @Transactional
    public V2CodeVersionDto archiveAdmin(String versionId) {
        return archive(versionId, CodeAccessScope.ADMIN);
    }

    private V2CodeVersionDto archive(
            String versionId,
            CodeAccessScope accessScope
    ) {
        VersionScope scope = versionScope(versionId, true, accessScope);
        CodeVersion version = scope.version();
        if ("ARCHIVED".equals(version.getStatus())) {
            return V2CodeVersionDto.from(version);
        }
        if (!"READY".equals(version.getStatus())
                && !"DEPRECATED".equals(version.getStatus())) {
            throw lifecycleConflict();
        }
        Instant now = Instant.now();
        version.setStatus("ARCHIVED");
        version.setArchivedAt(now);
        version.setUpdatedAt(now);
        versionRepository.saveAndFlush(version);
        auditService.archived(scope.asset().getId(), version.getId());
        return V2CodeVersionDto.from(version);
    }

    private VersionScope versionScope(
            String versionId,
            boolean lock,
            CodeAccessScope accessScope
    ) {
        if (accessScope == CodeAccessScope.ADMIN) {
            accessPolicy.requireAdministrator();
        }
        String assetId = versionRepository.findAssetIdByIdAndDeletedFalse(versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = asset(assetId, lock, accessScope);
        CodeVersion version = (lock
                ? versionRepository.findByIdAndDeletedFalseForUpdate(versionId)
                : versionRepository.findByIdAndAssetIdAndDeletedFalse(
                        versionId, asset.getId()
                ))
                .orElseThrow(CodeAssetAccessException::new);
        requireIdentity(asset, version);
        return new VersionScope(asset, version);
    }

    private CodeAsset asset(
            String assetId,
            boolean lock,
            CodeAccessScope accessScope
    ) {
        if (accessScope == CodeAccessScope.ADMIN) {
            accessPolicy.requireAdministrator();
        }
        CodeAsset asset = (lock
                ? assetRepository.findByIdAndDeletedFalseForUpdate(assetId)
                : assetRepository.findByIdAndDeletedFalse(assetId))
                .orElseThrow(CodeAssetAccessException::new);
        // 演示代码（is_demo=true）对普通用户只读可见；管理端仍走上方 ADMIN 校验
        if (!Boolean.TRUE.equals(asset.getIsDemo())) {
            accessPolicy.require(accessScope, asset.getOwnerUserId());
        }
        return asset;
    }

    private static void requireIdentity(CodeAsset asset, CodeVersion version) {
        if (!Objects.equals(asset.getId(), version.getAssetId())
                || !Objects.equals(asset.getOwnerUserId(), version.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
    }

    private void requirePreviewableVersion(CodeVersion version) {
        if (!"PASSED".equals(version.getValidationStatus())
                || !CodeArtifactAssembler.POLICY_VERSION.equals(
                        version.getValidationPolicyVersion())
                || !validSha(version.getArtifactSha256())) {
            throw validation(
                    "VALIDATION_NOT_CURRENT",
                    "Code version validation is not current"
            );
        }
    }

    private void requireStorageReference(CodeVersion version) {
        String storagePath = version.getStoragePath();
        String prefix = "users/" + version.getOwnerUserId()
                + "/codes/" + version.getAssetId() + "/";
        String suffix = storagePath != null && storagePath.startsWith(prefix)
                ? storagePath.substring(prefix.length())
                : null;
        if (suffix == null
                || suffix.isBlank()
                || suffix.contains("\\")
                || suffix.contains("?")
                || suffix.contains("#")
                || hasUnsafeObjectComponent(suffix)
                || !canAccessObject(storagePath, version.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
    }

    private static boolean hasUnsafeObjectComponent(String suffix) {
        for (String component : suffix.split("/", -1)) {
            if (component.isBlank() || ".".equals(component) || "..".equals(component)) {
                return true;
            }
        }
        return false;
    }

    private boolean canAccessObject(String storagePath, Integer ownerUserId) {
        try {
            return authContext.canAccessObjectName(storagePath, ownerUserId);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private CodeArchiveEntry findEntry(CodeVersion version, String normalizedPath) {
        return archiveReader.list(version, version.getOwnerUserId()).stream()
                .filter(entry -> normalizedPath.equals(entry.path()))
                .findFirst()
                .orElseThrow(CodeAssetAccessException::new);
    }

    private Integer currentUserId() {
        try {
            return authContext.currentUserId();
        } catch (RuntimeException exception) {
            throw new CodeAssetAccessException();
        }
    }

    private static boolean validSha(String sha) {
        return sha != null && SHA256.matcher(sha).matches();
    }

    private static String safeReason(String reason, String fallback) {
        if (reason == null || !reason.matches("[A-Z0-9_]+")) {
            return fallback;
        }
        return reason;
    }

    private static boolean isApprovalStateConflict(String reasonCode) {
        return "APPROVAL_TERMINAL".equals(reasonCode)
                || "APPROVAL_STATE_INVALID".equals(reasonCode)
                || "VERSION_NOT_READY".equals(reasonCode)
                || "APPROVAL_EVIDENCE_STALE".equals(reasonCode)
                || "RISK_EVIDENCE_STALE".equals(reasonCode);
    }

    private static CodeApprovalService.ApprovalExpectation approvalExpectation(
            V2CodeApprovalRequest request
    ) {
        if (isBlank(request.expectedValidationRunId())
                || isBlank(request.expectedRiskAssessmentId())
                || isBlank(request.expectedArtifactSha256())
                || isBlank(request.expectedPolicyVersion())) {
            throw validation(
                    "APPROVAL_EXPECTATION_REQUIRED",
                    "Current approval evidence is required"
            );
        }
        if (!SHA256.matcher(request.expectedArtifactSha256()).matches()) {
            throw validation(
                    "APPROVAL_EXPECTATION_INVALID",
                    "Approval evidence is invalid"
            );
        }
        return new CodeApprovalService.ApprovalExpectation(
                request.expectedValidationRunId(),
                request.expectedRiskAssessmentId(),
                request.expectedArtifactSha256(),
                request.expectedPolicyVersion()
        );
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static CodeValidationException validation(String code, String message) {
        return new CodeValidationException(code, message);
    }

    private static CodeWorkspaceConflictException lifecycleConflict() {
        return new CodeWorkspaceConflictException(
                "VERSION_LIFECYCLE_CONFLICT",
                "Code version lifecycle transition is not allowed"
        );
    }

    private record VersionScope(CodeAsset asset, CodeVersion version) {
    }

    public record Download(String fileName, String contentType, byte[] bytes) {
        public Download {
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("Download file name is required");
            }
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("Download content type is required");
            }
            bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }
    }
}
