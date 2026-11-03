package com.tss.platform.service;

import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.CodeWorkspace;
import com.tss.platform.entity.CodeWorkspaceFileDelta;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.repository.CodeWorkspaceFileDeltaRepository;
import com.tss.platform.repository.CodeWorkspaceRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class CodeWorkspaceOverlayService {

    private final CodeWorkspaceRepository workspaceRepository;
    private final CodeWorkspaceFileDeltaRepository deltaRepository;
    private final CodeAssetRepository assetRepository;
    private final CodeVersionRepository versionRepository;
    private final CodeVersionArchiveReader archiveReader;
    private final CodePathPolicy pathPolicy;
    private final CodeFilePolicy filePolicy;
    private final CodeAssetAuditService auditService;
    private final AuthContext authContext;

    public CodeWorkspaceOverlayService(
            CodeWorkspaceRepository workspaceRepository,
            CodeWorkspaceFileDeltaRepository deltaRepository,
            CodeAssetRepository assetRepository,
            CodeVersionRepository versionRepository,
            CodeVersionArchiveReader archiveReader,
            CodePathPolicy pathPolicy,
            CodeFilePolicy filePolicy,
            CodeAssetAuditService auditService,
            AuthContext authContext
    ) {
        this.workspaceRepository = workspaceRepository;
        this.deltaRepository = deltaRepository;
        this.assetRepository = assetRepository;
        this.versionRepository = versionRepository;
        this.archiveReader = archiveReader;
        this.pathPolicy = pathPolicy;
        this.filePolicy = filePolicy;
        this.auditService = auditService;
        this.authContext = authContext;
    }

    @Transactional(readOnly = true)
    public List<CodeWorkspaceTreeNode> tree(String workspaceId, String prefix) {
        String normalizedPrefix = pathPolicy.normalizeDirectoryPrefix(prefix);
        AccessScope scope = readableScope(workspaceId);
        Map<String, EffectiveMetadata> effective = effectiveMetadata(
                scope,
                normalizedPrefix.isEmpty() ? "" : normalizedPrefix + "/",
                true
        );

        Map<String, CodeWorkspaceTreeNode> directChildren = new LinkedHashMap<>();
        String prefixWithSlash = normalizedPrefix.isEmpty() ? "" : normalizedPrefix + "/";
        for (EffectiveMetadata metadata : effective.values()) {
            if (!metadata.path().startsWith(prefixWithSlash)) {
                continue;
            }
            String remainder = metadata.path().substring(prefixWithSlash.length());
            if (remainder.isEmpty()) {
                continue;
            }
            int slash = remainder.indexOf('/');
            if (slash >= 0) {
                String name = remainder.substring(0, slash);
                String childPath = prefixWithSlash + name;
                directChildren.putIfAbsent(
                        childPath,
                        CodeWorkspaceTreeNode.directory(childPath, name)
                );
            } else {
                CodeFileDescriptor descriptor = filePolicy.describeTrustedMetadata(
                        metadata.path(),
                        metadata.sizeBytes(),
                        metadata.contentHash()
                );
                directChildren.put(metadata.path(), CodeWorkspaceTreeNode.file(descriptor));
            }
        }

        return directChildren.values().stream()
                .sorted(Comparator
                        .comparing((CodeWorkspaceTreeNode node) ->
                                "DIRECTORY".equals(node.nodeType()) ? 0 : 1)
                        .thenComparing(CodeWorkspaceTreeNode::name)
                        .thenComparing(CodeWorkspaceTreeNode::path))
                .toList();
    }

    @Transactional(readOnly = true)
    public CodeWorkspaceContent content(String workspaceId, String path) {
        String normalizedPath = pathPolicy.normalizeFilePath(path);
        AccessScope scope = readableScope(workspaceId);
        EffectiveFile file = effectiveFile(scope, normalizedPath)
                .orElseThrow(CodeAssetAccessException::new);
        Long currentRevision = workspaceRepository.findRevisionByIdAndDeletedFalse(workspaceId)
                .orElseThrow(CodeAssetAccessException::new);
        if (!Objects.equals(scope.workspace().getRevision(), currentRevision)) {
            throw conflict(
                    "WORKSPACE_REVISION_CONFLICT",
                    "Code workspace changed while content was read"
            );
        }
        return content(scope.workspace(), normalizedPath, file.bytes());
    }

    /**
     * Returns a verified raw-content hash even when the file is too large for
     * online preview. Base-version entries are hashed as bounded streams.
     */
    @Transactional(readOnly = true)
    public CodeWorkspaceFileMetadata metadata(String workspaceId, String path) {
        String normalizedPath = pathPolicy.normalizeFilePath(path);
        AccessScope scope = readableScope(workspaceId);
        VerifiedMetadata metadata = verifiedMetadata(scope, normalizedPath)
                .orElseThrow(CodeAssetAccessException::new);
        Long currentRevision = workspaceRepository.findRevisionByIdAndDeletedFalse(workspaceId)
                .orElseThrow(CodeAssetAccessException::new);
        if (!Objects.equals(scope.workspace().getRevision(), currentRevision)) {
            throw conflict(
                    "WORKSPACE_REVISION_CONFLICT",
                    "Code workspace changed while metadata was read"
            );
        }
        return new CodeWorkspaceFileMetadata(
                filePolicy.describeTrustedMetadata(
                        normalizedPath,
                        metadata.sizeBytes(),
                        metadata.contentHash()
                ),
                metadata.contentHash(),
                currentRevision,
                !CodeWorkspace.STATUS_OPEN.equals(scope.workspace().getStatus())
        );
    }

    /**
     * Downloads a bounded file without applying the 1 MiB online-preview limit.
     * Ownership, path, archive range, length, CRC and delta hash checks remain in force.
     */
    @Transactional(readOnly = true)
    public CodeWorkspaceDownload download(String workspaceId, String path) {
        String normalizedPath = pathPolicy.normalizeFilePath(path);
        AccessScope scope = readableScope(workspaceId);
        EffectiveFile file = effectiveFile(
                scope,
                normalizedPath,
                CodeVersionArchiveReader.MAX_CODE_UNCOMPRESSED_BYTES
        ).orElseThrow(CodeAssetAccessException::new);
        Long currentRevision = workspaceRepository.findRevisionByIdAndDeletedFalse(workspaceId)
                .orElseThrow(CodeAssetAccessException::new);
        if (!Objects.equals(scope.workspace().getRevision(), currentRevision)) {
            throw conflict(
                    "WORKSPACE_REVISION_CONFLICT",
                    "Code workspace changed while file was read"
            );
        }
        byte[] bytes = file.bytes();
        return new CodeWorkspaceDownload(
                filePolicy.describe(normalizedPath, bytes),
                bytes
        );
    }

    @Transactional
    public CodeWorkspaceContent upsert(
            String workspaceId,
            String path,
            byte[] bytes,
            long expectedRevision,
            String expectedContentHash
    ) {
        String normalizedPath = pathPolicy.normalizeFilePath(path);
        byte[] safeBytes = defensiveCopy(bytes);
        AccessScope scope = writableScope(workspaceId, expectedRevision);
        Optional<EffectiveFile> current = effectiveFile(scope, normalizedPath);
        requireContentHash(current, expectedContentHash);
        CodeFileDescriptor descriptor = validateEditable(normalizedPath, safeBytes);

        Set<String> paths = new LinkedHashSet<>(effectiveMetadata(scope, "", false).keySet());
        paths.remove(normalizedPath);
        paths.add(normalizedPath);
        pathPolicy.validateNoTreeConflicts(paths);

        Instant now = Instant.now();
        CodeWorkspaceFileDelta delta = deltaRepository
                .findByWorkspaceIdAndPath(workspaceId, normalizedPath)
                .orElseGet(() -> newDelta(workspaceId, normalizedPath, now));
        writeUpsert(delta, safeBytes, descriptor.contentHash(), now);
        deltaRepository.save(delta);

        long revision = increment(scope.workspace(), now);
        auditService.fileUpserted(
                scope.asset().getId(),
                workspaceId,
                revision,
                current.map(EffectiveFile::contentHash).orElse(null),
                descriptor.contentHash()
        );
        return content(scope.workspace(), normalizedPath, safeBytes);
    }

    @Transactional
    public CodeWorkspaceContent move(
            String workspaceId,
            String source,
            String target,
            long expectedRevision,
            String expectedContentHash
    ) {
        String normalizedSource = pathPolicy.normalizeFilePath(source);
        String normalizedTarget = pathPolicy.normalizeFilePath(target);
        if (normalizedSource.equals(normalizedTarget)) {
            throw conflict("SOURCE_TARGET_SAME", "Source and target must differ");
        }
        filePolicy.validateSupportedPath(normalizedTarget);

        AccessScope scope = writableScope(workspaceId, expectedRevision);
        EffectiveFile sourceFile = effectiveFile(scope, normalizedSource)
                .orElseThrow(CodeAssetAccessException::new);
        requireContentHash(Optional.of(sourceFile), expectedContentHash);

        Map<String, EffectiveMetadata> effective = effectiveMetadata(scope, "", false);
        if (effective.containsKey(normalizedTarget)) {
            throw conflict("TARGET_EXISTS", "Target code file already exists");
        }
        Set<String> paths = new LinkedHashSet<>(effective.keySet());
        paths.remove(normalizedSource);
        paths.add(normalizedTarget);
        pathPolicy.validateNoTreeConflicts(paths);

        Instant now = Instant.now();
        CodeWorkspaceFileDelta targetDelta = deltaRepository
                .findByWorkspaceIdAndPath(workspaceId, normalizedTarget)
                .orElseGet(() -> newDelta(workspaceId, normalizedTarget, now));
        writeUpsert(targetDelta, sourceFile.bytes(), sourceFile.contentHash(), now);

        CodeWorkspaceFileDelta sourceDelta = deltaRepository
                .findByWorkspaceIdAndPath(workspaceId, normalizedSource)
                .orElseGet(() -> newDelta(workspaceId, normalizedSource, now));
        writeDelete(sourceDelta, now);

        deltaRepository.save(targetDelta);
        deltaRepository.save(sourceDelta);
        long revision = increment(scope.workspace(), now);
        auditService.fileMoved(
                scope.asset().getId(),
                workspaceId,
                revision,
                sourceFile.contentHash()
        );
        return content(scope.workspace(), normalizedTarget, sourceFile.bytes());
    }

    @Transactional
    public long delete(
            String workspaceId,
            String path,
            long expectedRevision,
            String expectedContentHash
    ) {
        String normalizedPath = pathPolicy.normalizeFilePath(path);
        AccessScope scope = writableScope(workspaceId, expectedRevision);
        VerifiedMetadata current = verifiedMetadata(scope, normalizedPath)
                .orElseThrow(CodeAssetAccessException::new);
        requireRawContentHash(Optional.of(current.contentHash()), expectedContentHash);

        Instant now = Instant.now();
        CodeWorkspaceFileDelta delta = deltaRepository
                .findByWorkspaceIdAndPath(workspaceId, normalizedPath)
                .orElseGet(() -> newDelta(workspaceId, normalizedPath, now));
        writeDelete(delta, now);
        deltaRepository.save(delta);
        long revision = increment(scope.workspace(), now);
        auditService.fileDeleted(
                scope.asset().getId(),
                workspaceId,
                revision,
                current.contentHash()
        );
        return revision;
    }

    private AccessScope readableScope(String workspaceId) {
        CodeWorkspace workspace = workspaceRepository.findByIdAndDeletedFalse(workspaceId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = assetRepository.findByIdAndDeletedFalse(workspace.getAssetId())
                .orElseThrow(CodeAssetAccessException::new);
        authorizeWorkspaceOwner(workspace, asset);
        return new AccessScope(workspace, asset, resolveBase(workspace, asset));
    }

    private boolean canAccessObject(String objectName, Integer ownerUserId) {
        try {
            return authContext.canAccessObjectName(objectName, ownerUserId);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private AccessScope writableScope(String workspaceId, long expectedRevision) {
        String assetId = workspaceRepository.findAssetIdByIdAndDeletedFalse(workspaceId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = assetRepository.findByIdAndDeletedFalseForUpdate(assetId)
                .orElseThrow(CodeAssetAccessException::new);

        CodeWorkspace workspace = workspaceRepository.findByIdAndDeletedFalseForUpdate(workspaceId)
                .orElseThrow(CodeAssetAccessException::new);
        if (!asset.getId().equals(workspace.getAssetId())) {
            throw new CodeAssetAccessException();
        }
        authorizeWorkspaceOwner(workspace, asset);
        AccessScope scope = new AccessScope(workspace, asset, resolveBase(workspace, asset));
        if (!CodeWorkspace.STATUS_OPEN.equals(scope.workspace().getStatus())) {
            throw conflict("WORKSPACE_READ_ONLY", "Code workspace is read-only");
        }
        if (scope.workspace().getRevision() == null
                || scope.workspace().getRevision() != expectedRevision) {
            throw conflict(
                    "WORKSPACE_REVISION_CONFLICT",
                    "Code workspace revision is stale"
            );
        }
        return scope;
    }

    private void authorizeWorkspaceOwner(CodeWorkspace workspace, CodeAsset asset) {
        if (asset.getOwnerUserId() == null
                || !asset.getOwnerUserId().equals(workspace.getOwnerUserId())
                || !canAccessOwner(asset.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
    }

    private CodeVersion resolveBase(CodeWorkspace workspace, CodeAsset asset) {
        if (workspace.getBaseVersionId() == null) {
            return null;
        }
        CodeVersion baseVersion = versionRepository.findByIdAndAssetIdAndDeletedFalse(
                        workspace.getBaseVersionId(),
                        asset.getId()
                )
                .orElseThrow(CodeAssetAccessException::new);
        if (!asset.getOwnerUserId().equals(baseVersion.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
        if (baseVersion.getStoragePath() == null
                || baseVersion.getStoragePath().isBlank()
                || !canAccessObject(baseVersion.getStoragePath(), asset.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
        return baseVersion;
    }

    private boolean canAccessOwner(Integer ownerUserId) {
        try {
            return authContext.canAccessOwner(ownerUserId);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Optional<EffectiveFile> effectiveFile(AccessScope scope, String normalizedPath) {
        return effectiveFile(scope, normalizedPath, CodeFilePolicy.EDITABLE_LIMIT_BYTES);
    }

    private Optional<VerifiedMetadata> verifiedMetadata(
            AccessScope scope,
            String normalizedPath
    ) {
        Optional<CodeWorkspaceFileDelta> delta = deltaRepository.findByWorkspaceIdAndPath(
                scope.workspace().getId(),
                normalizedPath
        );
        if (delta.isPresent()) {
            CodeWorkspaceFileDelta value = delta.get();
            if (CodeWorkspaceFileDelta.OPERATION_DELETE.equals(value.getOperation())) {
                return Optional.empty();
            }
            if (!CodeWorkspaceFileDelta.OPERATION_UPSERT.equals(value.getOperation())
                    || value.getSizeBytes() == null
                    || value.getSizeBytes() < 0
                    || value.getContentHash() == null) {
                throw new CodeAssetAccessException();
            }
            byte[] bytes = value.getContentBytes();
            if (bytes == null
                    || bytes.length != value.getSizeBytes()
                    || !filePolicy.sha256(bytes).equals(value.getContentHash())) {
                throw new CodeAssetAccessException();
            }
            return Optional.of(new VerifiedMetadata(
                    normalizedPath,
                    value.getSizeBytes(),
                    value.getContentHash()
            ));
        }
        if (scope.baseVersion() == null) {
            return Optional.empty();
        }
        CodeArchiveEntry entry = archiveReader.list(
                        scope.baseVersion(),
                        scope.asset().getOwnerUserId()
                ).stream()
                .filter(candidate -> normalizedPath.equals(candidate.path()))
                .findFirst()
                .orElse(null);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(new VerifiedMetadata(
                normalizedPath,
                entry.uncompressedSize(),
                archiveReader.sha256(
                        scope.baseVersion(),
                        scope.asset().getOwnerUserId(),
                        entry
                )
        ));
    }

    private Optional<EffectiveFile> effectiveFile(
            AccessScope scope,
            String normalizedPath,
            long outputLimit
    ) {
        Optional<CodeWorkspaceFileDelta> delta = deltaRepository.findByWorkspaceIdAndPath(
                scope.workspace().getId(),
                normalizedPath
        );
        if (delta.isPresent()) {
            CodeWorkspaceFileDelta value = delta.get();
            if (CodeWorkspaceFileDelta.OPERATION_DELETE.equals(value.getOperation())) {
                return Optional.empty();
            }
            if (!CodeWorkspaceFileDelta.OPERATION_UPSERT.equals(value.getOperation())
                    || value.getSizeBytes() == null
                    || value.getSizeBytes() < 0
                    || value.getContentHash() == null) {
                throw new CodeAssetAccessException();
            }
            if (value.getSizeBytes() > outputLimit
                    || value.getSizeBytes() > Integer.MAX_VALUE) {
                throw new CodeContentTooLargeException();
            }
            byte[] bytes = value.getContentBytes();
            if (bytes == null
                    || bytes.length != value.getSizeBytes()
                    || !filePolicy.sha256(bytes).equals(value.getContentHash())) {
                throw new CodeAssetAccessException();
            }
            return Optional.of(new EffectiveFile(bytes, value.getContentHash()));
        }
        if (scope.baseVersion() == null) {
            return Optional.empty();
        }
        CodeArchiveEntry entry = archiveReader.list(
                        scope.baseVersion(),
                        scope.asset().getOwnerUserId()
                ).stream()
                .filter(candidate -> normalizedPath.equals(candidate.path()))
                .findFirst()
                .orElse(null);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.uncompressedSize() > outputLimit) {
            throw new CodeContentTooLargeException();
        }
        byte[] bytes = archiveReader.read(
                scope.baseVersion(),
                scope.asset().getOwnerUserId(),
                entry,
                outputLimit
        );
        return Optional.of(new EffectiveFile(bytes, filePolicy.sha256(bytes)));
    }

    private Map<String, EffectiveMetadata> effectiveMetadata(
            AccessScope scope,
            String deltaPrefix,
            boolean prefixQuery
    ) {
        Map<String, EffectiveMetadata> effective = new LinkedHashMap<>();
        if (scope.baseVersion() != null) {
            for (CodeArchiveEntry entry : archiveReader.list(
                    scope.baseVersion(),
                    scope.asset().getOwnerUserId()
            )) {
                effective.put(entry.path(), new EffectiveMetadata(
                        entry.path(),
                        entry.uncompressedSize(),
                        null
                ));
            }
        }

        List<CodeWorkspaceFileDeltaRepository.DeltaMetadata> deltas = prefixQuery
                ? deltaRepository.findMetadataByWorkspaceIdAndPathStartingWithOrderByPathAsc(
                        scope.workspace().getId(),
                        escapeLikePrefix(deltaPrefix)
                )
                : deltaRepository.findMetadataByWorkspaceIdOrderByPathAsc(
                        scope.workspace().getId()
                );
        for (CodeWorkspaceFileDeltaRepository.DeltaMetadata delta : deltas) {
            String path = pathPolicy.normalizeFilePath(delta.getPath());
            if (CodeWorkspaceFileDelta.OPERATION_DELETE.equals(delta.getOperation())) {
                effective.remove(path);
            } else if (CodeWorkspaceFileDelta.OPERATION_UPSERT.equals(delta.getOperation())
                    && delta.getSizeBytes() != null
                    && delta.getSizeBytes() >= 0
                    && delta.getContentHash() != null) {
                effective.put(path, new EffectiveMetadata(
                        path,
                        delta.getSizeBytes(),
                        delta.getContentHash()
                ));
            } else {
                throw new CodeAssetAccessException();
            }
        }
        return effective;
    }

    private static String escapeLikePrefix(String literalPrefix) {
        return literalPrefix
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private CodeFileDescriptor validateEditable(String normalizedPath, byte[] bytes) {
        if (bytes.length > CodeFilePolicy.EDITABLE_LIMIT_BYTES) {
            throw new CodeContentTooLargeException();
        }
        filePolicy.validateSupportedPath(normalizedPath);
        filePolicy.decodeEditable(bytes);
        return filePolicy.describe(normalizedPath, bytes);
    }

    private CodeWorkspaceContent content(
            CodeWorkspace workspace,
            String normalizedPath,
            byte[] bytes
    ) {
        CodeFileDescriptor descriptor = validateEditable(normalizedPath, bytes);
        String decoded = filePolicy.decodeEditable(bytes);
        return new CodeWorkspaceContent(
                descriptor,
                decoded,
                "UTF-8",
                descriptor.contentHash(),
                workspace.getRevision(),
                !CodeWorkspace.STATUS_OPEN.equals(workspace.getStatus()),
                bytes
        );
    }

    private static void requireContentHash(
            Optional<EffectiveFile> current,
            String expectedContentHash
    ) {
        String expected = expectedContentHash == null || expectedContentHash.isBlank()
                ? null
                : expectedContentHash;
        if (current.isEmpty()) {
            if (expected != null) {
                throw conflict("CONTENT_HASH_CONFLICT", "Code file content hash is stale");
            }
            return;
        }
        if (expected == null || !Objects.equals(current.get().contentHash(), expected)) {
            throw conflict("CONTENT_HASH_CONFLICT", "Code file content hash is stale");
        }
    }

    private static void requireRawContentHash(
            Optional<String> currentHash,
            String expectedContentHash
    ) {
        String expected = expectedContentHash == null || expectedContentHash.isBlank()
                ? null
                : expectedContentHash;
        if (currentHash.isEmpty()) {
            if (expected != null) {
                throw conflict("CONTENT_HASH_CONFLICT", "Code file content hash is stale");
            }
            return;
        }
        if (expected == null || !Objects.equals(currentHash.get(), expected)) {
            throw conflict("CONTENT_HASH_CONFLICT", "Code file content hash is stale");
        }
    }

    private long increment(CodeWorkspace workspace, Instant now) {
        long next = workspace.getRevision() + 1;
        workspace.setRevision(next);
        workspace.setUpdatedAt(now);
        workspaceRepository.save(workspace);
        return next;
    }

    private static CodeWorkspaceFileDelta newDelta(
            String workspaceId,
            String path,
            Instant now
    ) {
        CodeWorkspaceFileDelta delta = new CodeWorkspaceFileDelta();
        delta.setId("code-delta-" + UUID.randomUUID().toString().replace("-", ""));
        delta.setWorkspaceId(workspaceId);
        delta.setPath(path);
        delta.setCreatedAt(now);
        return delta;
    }

    private static void writeUpsert(
            CodeWorkspaceFileDelta delta,
            byte[] bytes,
            String contentHash,
            Instant now
    ) {
        delta.setOperation(CodeWorkspaceFileDelta.OPERATION_UPSERT);
        delta.setContentBytes(bytes);
        delta.setContentHash(contentHash);
        delta.setSizeBytes((long) bytes.length);
        delta.setUpdatedAt(now);
    }

    private static void writeDelete(CodeWorkspaceFileDelta delta, Instant now) {
        delta.setOperation(CodeWorkspaceFileDelta.OPERATION_DELETE);
        delta.setContentBytes(null);
        delta.setContentHash(null);
        delta.setSizeBytes(null);
        delta.setUpdatedAt(now);
    }

    private static byte[] defensiveCopy(byte[] bytes) {
        if (bytes == null) {
            throw new CodeValidationException("INVALID_CONTENT", "Code file content is required");
        }
        return bytes.clone();
    }

    private static CodeWorkspaceConflictException conflict(String code, String message) {
        return new CodeWorkspaceConflictException(code, message);
    }

    private record AccessScope(
            CodeWorkspace workspace,
            CodeAsset asset,
            CodeVersion baseVersion
    ) {
    }

    private record EffectiveMetadata(String path, long sizeBytes, String contentHash) {
    }

    private record VerifiedMetadata(String path, long sizeBytes, String contentHash) {
    }

    private record EffectiveFile(byte[] bytes, String contentHash) {

        private EffectiveFile {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
