package com.tss.platform.service;

import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import com.tss.platform.training.plan.TrainingRunSpec;
import com.tss.platform.training.plan.TrainingRunSpecCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Service
public class TrainingModelPublishService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHING = "PUBLISHING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_FAILED = "FAILED";

    private static final Logger LOG = LoggerFactory.getLogger(TrainingModelPublishService.class);
    private static final Duration STALE_PUBLISH_AFTER = Duration.ofMinutes(10);

    private final TrainingExperimentVersionRepository trainingRepo;
    private final ModelAssetRepository modelAssetRepo;
    private final ModelVersionRepository modelVersionRepo;
    private final MinioService minioService;
    private final ArtifactDigestService artifactDigestService;
    private final ModelArtifactIntegrityService modelArtifactIntegrityService;
    private final TrainingRunSpecCodec runSpecCodec;
    private final TransactionTemplate transactionTemplate;

    public TrainingModelPublishService(
            TrainingExperimentVersionRepository trainingRepo,
            ModelAssetRepository modelAssetRepo,
            ModelVersionRepository modelVersionRepo,
            MinioService minioService,
            ArtifactDigestService artifactDigestService,
            ModelArtifactIntegrityService modelArtifactIntegrityService,
            TrainingRunSpecCodec runSpecCodec,
            PlatformTransactionManager transactionManager
    ) {
        this.trainingRepo = trainingRepo;
        this.modelAssetRepo = modelAssetRepo;
        this.modelVersionRepo = modelVersionRepo;
        this.minioService = minioService;
        this.artifactDigestService = artifactDigestService;
        this.modelArtifactIntegrityService = modelArtifactIntegrityService;
        this.runSpecCodec = runSpecCodec;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<String> pendingTrainingIds() {
        return trainingRepo.findTop20ByModelPublishStatusOrderByUpdatedAtAsc(STATUS_PENDING)
                .stream()
                .map(TrainingExperimentVersion::getId)
                .toList();
    }

    public void recoverStalePublishes() {
        Instant now = Instant.now();
        Integer reset = transactionTemplate.execute(status -> trainingRepo.resetStaleModelPublishes(
                now.minus(STALE_PUBLISH_AFTER),
                "服务重启或发布超时，已重新排队",
                now
        ));
        if (reset != null && reset > 0) {
            LOG.warn("重置超时的训练模型发布任务: count={}", reset);
        }
    }

    public void publishIfPending(String trainingId) {
        if (!claim(trainingId)) {
            return;
        }
        try {
            publishClaimed(trainingId);
        } catch (Exception e) {
            markFailed(trainingId, rootMessage(e));
            LOG.error("训练模型发布失败: trainingId={}, error={}", trainingId, rootMessage(e), e);
        }
    }

    private boolean claim(String trainingId) {
        Integer updated = transactionTemplate.execute(status -> trainingRepo.claimModelPublish(
                trainingId,
                List.of(STATUS_PENDING),
                Instant.now()
        ));
        return updated != null && updated > 0;
    }

    private void publishClaimed(String trainingId) throws Exception {
        TrainingExperimentVersion snapshot = transactionTemplate.execute(status ->
                trainingRepo.findById(trainingId)
                        .orElseThrow(() -> new IllegalArgumentException("训练任务不存在: " + trainingId))
        );
        if (snapshot == null || !STATUS_PUBLISHING.equals(snapshot.getModelPublishStatus())) {
            return;
        }
        if (snapshot.getRunSpecJson() != null && !snapshot.getRunSpecJson().isBlank()) {
            publishRunSpecClaimed(snapshot);
            return;
        }
        throw new IllegalArgumentException("训练方案不支持自动发布模型: " + snapshot.getTrainingProfile());
    }

    private void publishRunSpecClaimed(TrainingExperimentVersion snapshot) throws Exception {
        TrainingRunSpec runSpec = runSpecCodec.decode(snapshot);
        TrainingPlanDefinition.Artifact contract = runSpec.outputs().artifacts().stream()
                .filter(artifact -> Boolean.TRUE.equals(artifact.publishAsModel()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("RunSpec has no publishable model artifact"));
        String expectedSource = artifactPrefix(snapshot.getId()) + contract.path();
        String sourcePath = normalizeObjectName(snapshot.getModelArtifactPath());
        if (sourcePath == null) {
            sourcePath = expectedSource;
        }
        if (!expectedSource.equals(sourcePath)) {
            throw new IllegalArgumentException("training model artifact path does not match RunSpec");
        }
        String taskType = modelVersionRepo.findById(snapshot.getModelVersionId())
                .flatMap(version -> modelAssetRepo.findById(version.getAssetId()))
                .map(ModelAsset::getType)
                .filter(type -> type != null && !type.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("input model task type is unavailable"));
        long sourceSize = snapshot.getModelArtifactSizeBytes() == null
                ? minioService.stat(sourcePath).size()
                : snapshot.getModelArtifactSizeBytes();
        ArtifactDigestService.DigestResult source = artifactDigestService.digest(sourcePath, sourceSize);
        String expectedDigest = normalizeSha256(snapshot.getModelArtifactSha256());
        if (expectedDigest != null && !expectedDigest.equals(source.sha256())) {
            throw new IllegalArgumentException("training model artifact SHA-256 mismatch");
        }
        ModelArtifactIntegrityService.Inspection sourceInspection =
                modelArtifactIntegrityService.inspect(sourcePath, sourceSize, taskType);
        if (!sourceInspection.sha256().equals(source.sha256())) {
            throw new IllegalArgumentException("training model artifact changed during validation");
        }
        requirePublishedModelSpec(contract, sourceInspection);

        String assetId = deterministicId("model-asset-train-", snapshot.getExperimentId());
        String modelVersionId = deterministicId("model-ver-train-", snapshot.getId());
        String versionName = "v" + snapshot.getVersionNo();
        String fileName = leafName(contract.path());
        String targetPath = "users/" + snapshot.getOwnerUserId()
                + "/models/" + assetId + "/" + versionName + "/" + fileName;
        if (!minioService.objectExists(targetPath)) {
            minioService.copyObject(sourcePath, targetPath);
        }
        long targetSize = minioService.stat(targetPath).size();
        ModelArtifactIntegrityService.Inspection target =
                modelArtifactIntegrityService.inspect(targetPath, targetSize, taskType);
        if (!target.sha256().equals(source.sha256())) {
            throw new IllegalStateException("published model artifact SHA-256 mismatch");
        }
        requirePublishedModelSpec(contract, target);
        transactionTemplate.executeWithoutResult(status -> persistPublishedRunSpecModel(
                snapshot.getId(), runSpec, contract, source, assetId, modelVersionId,
                versionName, targetPath, targetSize, fileName, taskType
        ));
    }

    private void requirePublishedModelSpec(
            TrainingPlanDefinition.Artifact contract,
            ModelArtifactIntegrityService.Inspection inspection
    ) {
        String expectedSpecId = contract.publishedModelSpecId();
        if (expectedSpecId == null || expectedSpecId.isBlank()) {
            return;
        }
        if (!expectedSpecId.equals(inspection.artifactSpecId())) {
            throw new IllegalArgumentException(
                    "training model artifact does not satisfy publishedModelSpecId: "
                            + expectedSpecId
            );
        }
    }

    private void persistPublishedRunSpecModel(
            String trainingId,
            TrainingRunSpec runSpec,
            TrainingPlanDefinition.Artifact contract,
            ArtifactDigestService.DigestResult artifact,
            String assetId,
            String modelVersionId,
            String versionName,
            String targetPath,
            long targetSize,
            String fileName,
            String taskType
    ) {
        TrainingExperimentVersion training = trainingRepo.findById(trainingId)
                .orElseThrow(() -> new IllegalArgumentException("training task does not exist: " + trainingId));
        if (training.getProducedModelVersionId() != null) {
            return;
        }
        if (!STATUS_PUBLISHING.equals(training.getModelPublishStatus())) {
            throw new IllegalStateException("training model publish state changed");
        }
        Instant now = Instant.now();
        ModelAsset asset = modelAssetRepo.findById(assetId).orElse(null);
        if (asset == null) {
            asset = new ModelAsset();
            asset.setId(assetId);
            asset.setName(publishedAssetName(training, assetId));
            asset.setType(taskType);
            asset.setRemark(limit("published from training " + training.getExperimentId(), 1024));
            asset.setOwnerUserId(training.getOwnerUserId());
            asset.setCreatedAt(now);
            asset.setUpdatedAt(now);
            asset.setDeleted(false);
            modelAssetRepo.saveAndFlush(asset);
        } else if (Boolean.TRUE.equals(asset.getDeleted())
                || !Objects.equals(asset.getOwnerUserId(), training.getOwnerUserId())) {
            throw new IllegalStateException("published model asset does not match the training owner");
        }

        ModelVersion modelVersion = modelVersionRepo.findById(modelVersionId).orElse(null);
        if (modelVersion == null) {
            if (modelVersionRepo.existsByAssetIdAndVersion(assetId, versionName)) {
                throw new IllegalStateException("published model version already exists: " + versionName);
            }
            modelVersion = new ModelVersion();
            modelVersion.setId(modelVersionId);
            modelVersion.setAssetId(assetId);
            modelVersion.setVersion(versionName);
            modelVersion.setFileName(fileName);
            modelVersion.setStoragePath(targetPath);
            modelVersion.setSizeBytes(targetSize);
            modelVersion.setArtifactSha256(artifact.sha256());
            modelVersion.setArtifactSpecId(contract.publishedModelSpecId());
            modelVersion.setArtifactAttestedSha256(artifact.sha256());
            modelVersion.setArtifactAttestedAt(now);
            modelVersion.setDescription(limit("published from RunSpec training " + trainingId + ", format=" + contract.format(), 2048));
            modelVersion.setChangeLog("plan=" + runSpec.plan().id() + ", datasetVersionId="
                    + training.getDatasetVersionId() + ", codeVersionId=" + training.getCodeVersionId()
                    + ", sha256=" + artifact.sha256());
            modelVersion.setStatus("READY");
            modelVersion.setPublishedAt(now);
            modelVersion.setCreatedBy(training.getOwnerUserId());
            modelVersion.setOwnerUserId(training.getOwnerUserId());
            modelVersion.setCreatedAt(now);
            modelVersion.setDeleted(false);
            modelVersionRepo.saveAndFlush(modelVersion);
        } else if (Boolean.TRUE.equals(modelVersion.getDeleted())
                || !Objects.equals(modelVersion.getAssetId(), assetId)
                || !Objects.equals(modelVersion.getStoragePath(), targetPath)
                || !Objects.equals(modelVersion.getArtifactSha256(), artifact.sha256())
                || !Objects.equals(
                        modelVersion.getArtifactSpecId(),
                        contract.publishedModelSpecId()
                )) {
            throw new IllegalStateException("published model version does not match the training artifact");
        }
        asset.setCurrentVersionId(modelVersionId);
        asset.setUpdatedAt(now);
        modelAssetRepo.saveAndFlush(asset);

        training.setProducedModelVersionId(modelVersionId);
        training.setModelPublishStatus(STATUS_PUBLISHED);
        training.setModelPublishError(null);
        training.setModelPublishedAt(now);
        training.setModelArtifactPath(artifactPrefix(trainingId) + contract.path());
        training.setModelArtifactSha256(artifact.sha256());
        training.setModelArtifactSizeBytes(artifact.sizeBytes());
        training.setUpdatedAt(now);
        trainingRepo.saveAndFlush(training);
    }

    private String leafName(String path) {
        int slash = path == null ? -1 : path.lastIndexOf('/');
        String leaf = slash >= 0 ? path.substring(slash + 1) : path;
        if (leaf == null || leaf.isBlank() || ".".equals(leaf) || "..".equals(leaf)) {
            throw new IllegalArgumentException("RunSpec model artifact file name is invalid");
        }
        return leaf;
    }

    private void markFailed(String trainingId, String message) {
        transactionTemplate.executeWithoutResult(status -> trainingRepo.findById(trainingId).ifPresent(training -> {
            if (training.getProducedModelVersionId() != null) {
                training.setModelPublishStatus(STATUS_PUBLISHED);
                training.setModelPublishError(null);
                return;
            }
            if (!STATUS_PUBLISHING.equals(training.getModelPublishStatus())) {
                return;
            }
            training.setModelPublishStatus(STATUS_FAILED);
            training.setModelPublishError(limit(message, 4000));
            training.setUpdatedAt(Instant.now());
            trainingRepo.save(training);
        }));
    }

    private String artifactPrefix(String trainingId) {
        return "training-results/" + trainingId + "/artifacts/";
    }

    private String defaultModelName(TrainingExperimentVersion training) {
        String name = training.getName() == null || training.getName().isBlank()
                ? training.getExperimentId()
                : training.getName().trim();
        return name + "-训练模型";
    }

    private String publishedAssetName(TrainingExperimentVersion training, String assetId) {
        String suffix = "-" + assetId.substring(Math.max(0, assetId.length() - 8));
        return limit(defaultModelName(training), 255 - suffix.length()) + suffix;
    }

    private String deterministicId(String prefix, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
            return prefix + hash.substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成训练模型 ID", e);
        }
    }

    private String normalizeObjectName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace('\\', '/');
        if (normalized.startsWith("minio://")) {
            normalized = normalized.substring("minio://".length());
        }
        normalized = normalized.replaceFirst("^/+", "");
        if (normalized.startsWith("models/")) {
            normalized = normalized.substring("models/".length());
        }
        return normalized;
    }

    private String normalizeSha256(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("模型产物 sha256 格式不正确");
        }
        return normalized;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
