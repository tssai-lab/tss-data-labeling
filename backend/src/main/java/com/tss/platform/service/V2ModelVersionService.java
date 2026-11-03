package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.ModelCodePreviewDto;
import com.tss.platform.dto.v2.V2ModelConsumerManifest;
import com.tss.platform.dto.v2.V2ModelCurrentVersionResult;
import com.tss.platform.dto.v2.V2ModelFileNode;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class V2ModelVersionService {

    private static final int MAX_ENTRIES = 10_000;

    private final ModelVersionRepository versionRepo;
    private final ModelAssetRepository assetRepo;
    private final ModelArtifactAttestationService attestationService;
    private final ModelArtifactIntegrityService integrityService;
    private final ModelVersionLifecycleService lifecycleService;
    private final ModelCodePreviewService codePreviewService;
    private final MinioService minioService;
    private final AuthContext authContext;

    public V2ModelVersionService(
            ModelVersionRepository versionRepo,
            ModelAssetRepository assetRepo,
            ModelArtifactAttestationService attestationService,
            ModelArtifactIntegrityService integrityService,
            ModelVersionLifecycleService lifecycleService,
            ModelCodePreviewService codePreviewService,
            MinioService minioService,
            AuthContext authContext
    ) {
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.attestationService = attestationService;
        this.integrityService = integrityService;
        this.lifecycleService = lifecycleService;
        this.codePreviewService = codePreviewService;
        this.minioService = minioService;
        this.authContext = authContext;
    }

    public V2ModelCurrentVersionResult switchCurrent(String assetId, String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new V2BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "versionId 不能为空"
            );
        }
        try {
            ModelAsset asset = lifecycleService.switchCurrent(assetId, versionId.trim());
            return new V2ModelCurrentVersionResult(asset.getId(), asset.getCurrentVersionId());
        } catch (ModelArtifactException exception) {
            throw artifactFailure(exception);
        } catch (IllegalArgumentException exception) {
            throw lifecycleFailure(exception);
        }
    }

    public V2ModelConsumerManifest consumerManifest(String versionId) {
        AttestedScope scope = attestOwnerVersion(versionId);
        ModelVersion version = scope.artifact().version();
        ModelAsset asset = scope.artifact().asset();
        String base = "/api/v2/model-versions/" + version.getId();
        return new V2ModelConsumerManifest(
                asset.getId(),
                version.getId(),
                version.getVersion(),
                version.getStatus(),
                asset.getType(),
                version.getFileName(),
                scope.artifact().sizeBytes(),
                scope.artifact().sha256(),
                version.getCommitInfo(),
                version.getHyperParams() == null ? Map.of() : Map.copyOf(version.getHyperParams()),
                Objects.equals(asset.getCurrentVersionId(), version.getId()),
                base + "/download",
                base + "/files"
        );
    }

    public Download download(String versionId) {
        OwnerScope ownerScope = requireOwnerVersion(versionId);
        ModelVersion version = ownerScope.version();
        if (!"READY".equals(version.getStatus())) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MODEL_ARTIFACT_INVALID",
                    "模型版本尚未通过 READY 准入"
            );
        }
        String storagePath = version.getStoragePath();
        Long sizeBytes = version.getSizeBytes();
        String expectedSha256 = version.getArtifactSha256();
        if (storagePath == null || storagePath.isBlank()
                || sizeBytes == null || sizeBytes <= 0) {
            attestationService.invalidate(versionId);
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MODEL_ARTIFACT_INVALID",
                    "模型制品元数据不完整"
            );
        }
        Consumer<ModelArtifactIntegrityService.Inspection> successHandler =
                expectedSha256 == null
                        ? inspection -> attestationService.recordStreamingVerification(
                                versionId,
                                storagePath,
                                sizeBytes,
                                inspection.sha256()
                        )
                        : null;
        try {
            return new Download(
                    safeFileName(version),
                    sizeBytes,
                    expectedSha256,
                    integrityService.openVerified(
                            storagePath,
                            sizeBytes,
                            expectedSha256,
                            successHandler,
                            () -> attestationService.invalidate(versionId)
                    )
            );
        } catch (ModelArtifactException exception) {
            if (!exception.isStorageUnavailable()) {
                attestationService.invalidate(version.getId());
            }
            throw artifactFailure(exception);
        }
    }

    public List<V2ModelFileNode> files(String versionId) {
        AttestedScope scope = attestOwnerVersion(versionId);
        ModelVersion version = scope.artifact().version();
        List<V2ModelFileNode> files = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        Set<String> filePaths = new HashSet<>();
        try (InputStream input = minioService.downloadStream(version.getStoragePath());
             ZipInputStream zip = new ZipInputStream(
                     new BufferedInputStream(input),
                     StandardCharsets.UTF_8
             )) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (files.size() >= MAX_ENTRIES) {
                    invalidateAndReject(version.getId(), "模型 ZIP 条目数量超过限制");
                }
                String path = normalizePath(entry.getName());
                if (!ZipPathValidator.isSafeEntryPath(path)) {
                    invalidateAndReject(version.getId(), "模型 ZIP 包含非法或重复路径");
                }
                path = ZipPathValidator.normalizeEntryPath(path);
                String canonical = canonicalPath(path);
                if (!paths.add(canonical)) {
                    invalidateAndReject(version.getId(), "模型 ZIP 包含非法或重复路径");
                }
                rejectParentFileConflict(version.getId(), canonical, filePaths);
                if (!entry.isDirectory()) {
                    for (String existing : paths) {
                        if (!existing.equals(canonical)
                                && existing.startsWith(canonical + "/")) {
                            invalidateAndReject(version.getId(), "模型 ZIP 包含文件/目录冲突");
                        }
                    }
                    filePaths.add(canonical);
                }
                files.add(new V2ModelFileNode(
                        path,
                        nameOf(path),
                        entry.isDirectory() ? "DIRECTORY" : "FILE",
                        entry.isDirectory() || entry.getSize() < 0 ? null : entry.getSize()
                ));
                zip.closeEntry();
            }
        } catch (V2BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new V2BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MODEL_STORAGE_UNAVAILABLE",
                    "模型存储暂时不可用"
            );
        }
        files.sort(Comparator.comparing(V2ModelFileNode::path));
        return files;
    }

    private void rejectParentFileConflict(
            String versionId,
            String path,
            Set<String> filePaths
    ) {
        int slash = path.indexOf('/');
        while (slash >= 0) {
            if (filePaths.contains(path.substring(0, slash))) {
                invalidateAndReject(versionId, "模型 ZIP 包含文件/目录冲突");
            }
            slash = path.indexOf('/', slash + 1);
        }
    }

    public ModelCodePreviewDto content(String versionId, String path) {
        attestOwnerVersion(versionId);
        try {
            return codePreviewService.previewCode(versionId, path);
        } catch (IllegalArgumentException exception) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MODEL_FILE_NOT_PREVIEWABLE",
                    "模型文件无法预览"
            );
        }
    }

    private AttestedScope attestOwnerVersion(String versionId) {
        OwnerScope ownerScope = requireOwnerVersion(versionId);
        try {
            return new AttestedScope(
                    attestationService.attestReady(ownerScope.version().getId())
            );
        } catch (ModelArtifactException exception) {
            throw artifactFailure(exception);
        } catch (IllegalArgumentException exception) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MODEL_VERSION_NOT_READY",
                    "模型版本尚不可消费"
            );
        }
    }

    private OwnerScope requireOwnerVersion(String versionId) {
        ModelVersion version = versionRepo.findByIdAndDeletedFalse(versionId)
                .orElseThrow(this::notFound);
        ModelAsset asset = assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(this::notFound);
        if (!authContext.canAccessOwner(effectiveOwner(version, asset))) {
            throw notFound();
        }
        return new OwnerScope(version, asset);
    }

    private void invalidateAndReject(String versionId, String message) {
        attestationService.invalidate(versionId);
        throw new V2BusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MODEL_ARTIFACT_INVALID",
                message
        );
    }

    private V2BusinessException artifactFailure(ModelArtifactException exception) {
        return new V2BusinessException(
                exception.isStorageUnavailable()
                        ? HttpStatus.SERVICE_UNAVAILABLE
                        : HttpStatus.UNPROCESSABLE_ENTITY,
                exception.isStorageUnavailable()
                        ? "MODEL_STORAGE_UNAVAILABLE"
                        : "MODEL_ARTIFACT_INVALID",
                exception.isStorageUnavailable()
                        ? "模型存储暂时不可用"
                        : "模型制品完整性校验失败"
        );
    }

    private V2BusinessException lifecycleFailure(IllegalArgumentException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("not found") || message.contains("no permission")) {
            return notFound();
        }
        return new V2BusinessException(
                HttpStatus.CONFLICT,
                "MODEL_VERSION_CONFLICT",
                "模型版本状态不允许执行该操作"
        );
    }

    private V2BusinessException notFound() {
        return new V2BusinessException(
                HttpStatus.NOT_FOUND,
                "MODEL_VERSION_NOT_FOUND",
                "模型版本不存在或无权访问"
        );
    }

    private Integer effectiveOwner(ModelVersion version, ModelAsset asset) {
        return version.getOwnerUserId() == null
                ? asset.getOwnerUserId()
                : version.getOwnerUserId();
    }

    private String safeFileName(ModelVersion version) {
        String fileName = version.getFileName();
        if (fileName == null || fileName.isBlank() || containsControl(fileName)) {
            return "model-version-" + version.getId() + ".zip";
        }
        return fileName;
    }

    private boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index += 1) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private String canonicalPath(String path) {
        String canonical = path;
        while (canonical.endsWith("/") && !canonical.isEmpty()) {
            canonical = canonical.substring(0, canonical.length() - 1);
        }
        return canonical;
    }

    private String nameOf(String path) {
        String canonical = canonicalPath(path);
        int slash = canonical.lastIndexOf('/');
        return slash >= 0 ? canonical.substring(slash + 1) : canonical;
    }

    private record AttestedScope(ModelArtifactAttestationService.AttestedArtifact artifact) {
    }

    private record OwnerScope(ModelVersion version, ModelAsset asset) {
    }

    public record Download(
            String fileName,
            long sizeBytes,
            String sha256,
            InputStream inputStream
    ) {
    }
}
