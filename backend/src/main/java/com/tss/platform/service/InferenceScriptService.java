package com.tss.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.InferenceScriptUploadResultDto;
import com.tss.platform.dto.InferenceScriptVersionDto;
import com.tss.platform.entity.InferenceScriptAsset;
import com.tss.platform.entity.InferenceScriptVersion;
import com.tss.platform.repository.InferenceScriptAssetRepository;
import com.tss.platform.repository.InferenceScriptVersionRepository;
import com.tss.platform.repository.InferenceTaskRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

@Service
public class InferenceScriptService {

    private static final String RUNTIME_PYTHON3 = "PYTHON3";
    private static final String STATUS_READY = "READY";
    private static final String SOURCE_INFERENCE_SCRIPT_VERSION = "INFERENCE_SCRIPT_VERSION";

    private final InferenceScriptAssetRepository assetRepo;
    private final InferenceScriptVersionRepository versionRepo;
    private final InferenceTaskRepository taskRepo;
    private final MinioService minioService;
    private final MinioDeleteTaskService minioDeleteTaskService;
    private final AuthContext authContext;
    private final ObjectMapper objectMapper;

    public InferenceScriptService(
            InferenceScriptAssetRepository assetRepo,
            InferenceScriptVersionRepository versionRepo,
            InferenceTaskRepository taskRepo,
            MinioService minioService,
            MinioDeleteTaskService minioDeleteTaskService,
            AuthContext authContext,
            ObjectMapper objectMapper
    ) {
        this.assetRepo = assetRepo;
        this.versionRepo = versionRepo;
        this.taskRepo = taskRepo;
        this.minioService = minioService;
        this.minioDeleteTaskService = minioDeleteTaskService;
        this.authContext = authContext;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public InferenceScriptUploadResultDto upload(
            MultipartFile file,
            String scriptName,
            String version,
            String runtime,
            String entryFile,
            String paramsSchemaJson,
            String remark
    ) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请上传推理脚本 ZIP 文件");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("推理脚本仅支持 .zip 格式");
        }
        String normalizedRuntime = normalizeRuntime(runtime);
        String normalizedEntryFile = InferenceScriptZipValidator.normalizeRequiredEntryFile(entryFile);
        validateZip(file, normalizedEntryFile);
        JsonNode paramsSchema = parseOptionalJson(paramsSchemaJson, "paramsSchemaJson 必须是合法 JSON");

        String assetName = requireText(scriptName, "scriptName 不能为空");
        String versionLabel = normalizeText(version);
        if (versionLabel == null) {
            versionLabel = "v1";
        }
        Integer ownerUserId = authContext.currentUserId();
        Instant now = Instant.now();
        String assetId = "infer-script-asset-" + UUID.randomUUID().toString().replace("-", "");
        String versionId = "infer-script-ver-" + UUID.randomUUID().toString().replace("-", "");
        String storagePath = "users/" + ownerUserId
                + "/inference-scripts/" + assetId
                + "/" + sanitizeSegment(versionLabel)
                + "/" + sanitizeSegment(fileName);

        try {
            minioService.uploadFile(storagePath, file);
        } catch (Exception e) {
            throw new IllegalArgumentException("上传推理脚本失败: " + e.getMessage());
        }

        InferenceScriptAsset asset = new InferenceScriptAsset();
        asset.setId(assetId);
        asset.setName(assetName);
        asset.setRemark(normalizeText(remark));
        asset.setOwnerUserId(ownerUserId);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset.setDeleted(false);

        InferenceScriptVersion scriptVersion = new InferenceScriptVersion();
        scriptVersion.setId(versionId);
        scriptVersion.setAssetId(assetId);
        scriptVersion.setVersion(versionLabel);
        scriptVersion.setFileName(fileName);
        scriptVersion.setStoragePath(storagePath);
        scriptVersion.setSizeBytes(file.getSize());
        scriptVersion.setRuntime(normalizedRuntime);
        scriptVersion.setEntryFile(normalizedEntryFile);
        scriptVersion.setParamsSchemaJson(paramsSchema == null ? null : writeJson(paramsSchema, "paramsSchemaJson 必须是合法 JSON"));
        scriptVersion.setStatus(STATUS_READY);
        scriptVersion.setOwnerUserId(ownerUserId);
        scriptVersion.setCreatedAt(now);
        scriptVersion.setDeleted(false);

        try {
            assetRepo.saveAndFlush(asset);
            versionRepo.saveAndFlush(scriptVersion);
        } catch (RuntimeException e) {
            removeObjectQuietly(storagePath);
            throw new IllegalArgumentException("保存推理脚本资产失败: " + e.getMessage());
        }

        return InferenceScriptUploadResultDto.builder()
                .scriptAssetId(assetId)
                .scriptVersionId(versionId)
                .scriptName(assetName)
                .version(versionLabel)
                .fileName(fileName)
                .storagePath(storagePath)
                .sizeBytes(file.getSize())
                .runtime(normalizedRuntime)
                .entryFile(normalizedEntryFile)
                .paramsSchema(paramsSchema == null ? objectMapper.createObjectNode() : paramsSchema)
                .status(STATUS_READY)
                .build();
    }

    @Transactional(readOnly = true)
    public List<InferenceScriptVersionDto> listScripts() {
        List<InferenceScriptVersion> versions = authContext.isAdmin()
                ? versionRepo.findByDeletedFalseOrderByCreatedAtDesc()
                : versionRepo.findByOwnerUserIdInAndDeletedFalseOrderByCreatedAtDesc(
                        List.of(authContext.currentUserId(), AuthContext.SYSTEM_USER_ID));
        Map<String, InferenceScriptAsset> assets = assetRepo.findAllById(
                        versions.stream().map(InferenceScriptVersion::getAssetId).toList()
                )
                .stream()
                .filter(asset -> Boolean.FALSE.equals(asset.getDeleted()))
                .collect(java.util.stream.Collectors.toMap(InferenceScriptAsset::getId, asset -> asset));
        return versions.stream()
                .filter(version -> assets.containsKey(version.getAssetId()))
                .map(version -> toDto(version, assets.get(version.getAssetId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public InferenceScriptVersionDto getScript(String versionId) {
        InferenceScriptVersion version = requireAccessibleVersion(versionId);
        InferenceScriptAsset asset = assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException("推理脚本资产不存在"));
        return toDto(version, asset);
    }

    @Transactional
    public Map<String, Object> deleteScriptVersion(String versionId) {
        InferenceScriptVersion version = versionRepo.findByIdAndDeletedFalse(requireText(versionId, "scriptVersionId 不能为空"))
                .orElseThrow(() -> new IllegalArgumentException("推理脚本版本不存在"));
        InferenceScriptAsset asset = assetRepo.findByIdAndDeletedFalse(version.getAssetId()).orElse(null);
        Integer ownerUserId = version.getOwnerUserId();
        if (ownerUserId == null && asset != null) {
            ownerUserId = asset.getOwnerUserId();
        }
        authContext.requireOwnerAccess(ownerUserId, "推理脚本版本不存在或无权限");
        if (asset != null) {
            authContext.rejectDemoWrite(asset.getIsDemo());
        }

        long taskReferences = taskRepo.countByScriptVersionId(version.getId());
        if (taskReferences > 0) {
            throw new IllegalArgumentException("推理脚本版本已被推理任务引用，不能删除");
        }

        boolean minioDeleteQueued = queueScriptFileDelete(version, ownerUserId);
        Instant now = Instant.now();
        version.setDeleted(true);
        version.setDeletedAt(now);
        versionRepo.save(version);

        boolean assetDeleted = false;
        if (asset != null && versionRepo.countByAssetIdAndDeletedFalse(asset.getId()) == 0) {
            asset.setDeleted(true);
            asset.setDeletedAt(now);
            asset.setUpdatedAt(now);
            assetRepo.save(asset);
            assetDeleted = true;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", version.getId());
        result.put("assetId", version.getAssetId());
        result.put("deleted", true);
        result.put("assetDeleted", assetDeleted);
        result.put("minioDeleteQueued", minioDeleteQueued);
        return result;
    }

    @Transactional(readOnly = true)
    public InferenceScriptVersion requireAccessibleVersion(String versionId) {
        InferenceScriptVersion version = versionRepo.findByIdAndDeletedFalse(requireText(versionId, "scriptVersionId 不能为空"))
                .orElseThrow(() -> new IllegalArgumentException("推理脚本版本不存在"));
        if (!STATUS_READY.equals(version.getStatus())) {
            throw new IllegalArgumentException("推理脚本版本不可用");
        }
        authContext.requireOwnerAccess(version.getOwnerUserId(), "no permission for scriptVersionId: " + versionId);
        return version;
    }

    public InferenceScriptVersionDto toDto(InferenceScriptVersion version, InferenceScriptAsset asset) {
        InferenceScriptVersionDto dto = new InferenceScriptVersionDto();
        dto.setId(version.getId());
        dto.setAssetId(version.getAssetId());
        dto.setScriptName(asset == null ? null : asset.getName());
        dto.setVersion(version.getVersion());
        dto.setFileName(version.getFileName());
        dto.setStoragePath(version.getStoragePath());
        dto.setSizeBytes(version.getSizeBytes());
        dto.setRuntime(version.getRuntime());
        dto.setEntryFile(version.getEntryFile());
        dto.setParamsSchema(fromJson(version.getParamsSchemaJson()));
        dto.setStatus(version.getStatus());
        dto.setOwnerUserId(version.getOwnerUserId());
        dto.setIsDemo(asset != null && Boolean.TRUE.equals(asset.getIsDemo()));
        dto.setCreatedAt(version.getCreatedAt());
        return dto;
    }

    private void validateZip(MultipartFile file, String normalizedEntryFile) {
        try (InputStream inputStream = file.getInputStream();
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(inputStream))) {
            InferenceScriptZipValidator.validate(zip, normalizedEntryFile);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("推理脚本 zip 校验失败: " + e.getMessage());
        }
    }

    private String normalizeRuntime(String runtime) {
        String normalized = normalizeText(runtime);
        if (normalized == null) {
            return RUNTIME_PYTHON3;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!RUNTIME_PYTHON3.equals(normalized)) {
            throw new IllegalArgumentException("runtime only supports PYTHON3");
        }
        return normalized;
    }

    private JsonNode parseOptionalJson(String json, String message) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(message);
        }
    }

    private JsonNode fromJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String writeJson(JsonNode node, String message) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (Exception e) {
            throw new IllegalArgumentException(message);
        }
    }

    private void removeObjectQuietly(String objectName) {
        try {
            minioDeleteTaskService.enqueueDefaultBucketDeleteImmediately(
                    objectName,
                    MinioDeleteTaskService.SOURCE_FILE_OBJECT,
                    objectName,
                    authContext.currentUserId()
            );
        } catch (Exception ignored) {
            // Preserve the original error path.
        }
    }

    private boolean queueScriptFileDelete(InferenceScriptVersion version, Integer ownerUserId) {
        String objectName = minioPathToObjectName(version.getStoragePath());
        if (objectName == null) {
            return false;
        }
        try {
            minioDeleteTaskService.enqueueDefaultBucketDelete(
                    objectName,
                    SOURCE_INFERENCE_SCRIPT_VERSION,
                    version.getId(),
                    ownerUserId
            );
            return true;
        } catch (Exception e) {
            throw new IllegalArgumentException("创建推理脚本文件删除任务失败: " + e.getMessage());
        }
    }

    private String minioPathToObjectName(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return null;
        }
        if (normalized.startsWith("minio://")) {
            normalized = normalized.substring("minio://".length());
        }
        String objectName = cleanObjectName(normalized);
        return objectName.isBlank() ? null : objectName;
    }

    private String cleanObjectName(String objectName) {
        String normalized = objectName.replace('\\', '/').replaceAll("^/+", "");
        for (String part : normalized.split("/")) {
            if (".".equals(part) || "..".equals(part) || part.contains("\u0000")) {
                throw new IllegalArgumentException("objectName 非法");
            }
        }
        return normalized;
    }

    private String sanitizeSegment(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return "unnamed";
        }
        return normalized.replaceAll("[\\\\/:*?\"<>|]", "_").toLowerCase(Locale.ROOT);
    }

    private String requireText(String value, String message) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
