package com.tss.platform.service;

import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.model.DatasetTaskType;
import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.model.manifest.ManifestData;
import com.tss.platform.model.manifest.ManifestImportPlan;
import com.tss.platform.model.manifest.ManifestSample;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import io.minio.StatObjectResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipException;

@Service
public class SingleModalDatasetIndexer {

    private static final String READY = "READY";
    private static final String DRAFT = "DRAFT";
    private static final String PRIMARY = "PRIMARY";

    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final DatasetPackageRepository packageRepo;
    private final DatasetVersionPackageRepository versionPackageRepo;
    private final DatasetSampleRepository sampleRepo;
    private final DatasetSampleDataRepository dataRepo;
    private final ZipCentralDirectoryReader zipReader;
    private final SingleModalImportPlanBuilder planBuilder;
    private final MinioService minioService;

    public SingleModalDatasetIndexer(
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            DatasetPackageRepository packageRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            ZipCentralDirectoryReader zipReader,
            SingleModalImportPlanBuilder planBuilder,
            MinioService minioService
    ) {
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.packageRepo = packageRepo;
        this.versionPackageRepo = versionPackageRepo;
        this.sampleRepo = sampleRepo;
        this.dataRepo = dataRepo;
        this.zipReader = zipReader;
        this.planBuilder = planBuilder;
        this.minioService = minioService;
    }

    public long indexNewVersion(DatasetAsset asset, DatasetVersion version) {
        if (sampleRepo.countByDatasetVersionIdAndDeletedFalse(version.getId()) > 0) {
            return sampleRepo.countByDatasetVersionIdAndDeletedFalse(version.getId());
        }
        return persistPrepared(asset, version, prepareIndex(asset, version));
    }

    public PreparedIndex prepareIndex(DatasetAsset asset, DatasetVersion version) {
        requireIndexable(asset, version);
        StatObjectResponse stat;
        try {
            stat = minioService.stat(version.getStoragePath());
        } catch (Exception exception) {
            throw new DatasetStorageAccessException("dataset object could not be inspected", exception);
        }
        if (stat.size() != version.getSizeBytes()) {
            throw new IllegalArgumentException("dataset object size does not match version metadata");
        }

        String sourceName = sourceName(version);
        String taskType = DatasetTaskType.normalize(asset.getType());
        boolean zipPackage = sourceName.toLowerCase(Locale.ROOT).endsWith(".zip");
        List<ZipEntryInfo> entries;
        if (zipPackage) {
            try {
                entries = zipReader.read(version.getStoragePath(), version.getSizeBytes());
            } catch (IllegalArgumentException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new DatasetStorageAccessException("dataset ZIP could not be indexed", exception);
            }
        } else {
            entries = List.of(new ZipEntryInfo(
                    sourceName,
                    sourceName.replace('\\', '/'),
                    0,
                    version.getSizeBytes(),
                    version.getSizeBytes(),
                    0L,
                    0L,
                    0L,
                    false,
                    false
            ));
        }

        ManifestImportPlan plan = planBuilder.build(taskType, entries, 0);
        if (plan.totalSamples() <= 0 || plan.totalDataCount() <= 0) {
            throw new IllegalArgumentException("single-modal dataset has no consumable files");
        }
        return new PreparedIndex(
                asset.getId(),
                version.getId(),
                version.getStoragePath(),
                version.getSizeBytes(),
                taskType,
                zipPackage,
                plan
        );
    }

    public long persistPrepared(
            DatasetAsset asset,
            DatasetVersion version,
            PreparedIndex prepared
    ) {
        requireIndexable(asset, version);
        requireMatchingPreparation(asset, version, prepared);
        long existing = sampleRepo.countByDatasetVersionIdAndDeletedFalse(version.getId());
        if (existing > 0) {
            return existing;
        }
        return persistPlan(asset, version, prepared);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EnsureResult ensureIndexed(String versionId) {
        DatasetVersion version = versionRepo.findByIdAndDeletedFalseForUpdate(versionId)
                .orElseThrow(() -> new IllegalArgumentException("dataset version not found: " + versionId));
        long existing = sampleRepo.countByDatasetVersionIdAndDeletedFalse(versionId);
        if (existing > 0) {
            return EnsureResult.success(existing);
        }
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalseForUpdate(version.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "dataset asset not found: " + version.getAssetId()
                ));
        if ("MULTIMODAL".equals(DatasetTaskType.normalize(asset.getType()))) {
            demoteUnconsumable(asset, version);
            return EnsureResult.invalid("multimodal READY version has no consumable samples");
        }
        try {
            PreparedIndex prepared = prepareIndex(asset, version);
            validateHistoricalContent(asset, version, prepared);
            long indexed = persistPrepared(asset, version, prepared);
            if (indexed <= 0) {
                demoteUnconsumable(asset, version);
                return EnsureResult.invalid("dataset version has no consumable samples");
            }
            return EnsureResult.success(indexed);
        } catch (Exception exception) {
            if (isDeterministicFailure(exception)) {
                demoteUnconsumable(asset, version);
                return EnsureResult.invalid(rootMessage(exception));
            }
            return EnsureResult.storageUnavailable(rootMessage(exception));
        }
    }

    private long persistPlan(
            DatasetAsset asset,
            DatasetVersion version,
            PreparedIndex prepared
    ) {
        String packageId = null;
        if (prepared.zipPackage()) {
            DatasetPackage datasetPackage = primaryPackage(asset, version);
            packageId = datasetPackage.getId();
        }

        ManifestImportPlan plan = prepared.plan();
        Instant now = Instant.now();
        List<DatasetSample> samples = new ArrayList<>(plan.totalSamples());
        List<DatasetSampleData> dataItems = new ArrayList<>(plan.totalDataCount());
        for (ManifestSample source : plan.samples()) {
            DatasetSample sample = toSample(version, packageId, source, now);
            samples.add(sample);
            for (ManifestData data : source.data()) {
                dataItems.add(toData(version, sample, packageId, data, now));
            }
        }
        sampleRepo.saveAllAndFlush(samples);
        dataRepo.saveAllAndFlush(dataItems);
        return samples.size();
    }

    private void validateHistoricalContent(
            DatasetAsset asset,
            DatasetVersion version,
            PreparedIndex prepared
    ) {
        try (InputStream input = minioService.downloadStream(version.getStoragePath())) {
            if (prepared.zipPackage()) {
                DatasetZipValidator.validateDatasetZipEntries(
                        prepared.taskType(),
                        version.getAnnotationFormat(),
                        input
                );
            } else {
                DatasetZipValidator.validateSingleFileContent(
                        prepared.taskType(),
                        version.getAnnotationFormat(),
                        sourceName(version),
                        input,
                        version.getSizeBytes()
                );
            }
        } catch (ZipException exception) {
            throw new IllegalArgumentException("dataset ZIP content is invalid", exception);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DatasetStorageAccessException(
                    "dataset object content could not be validated",
                    exception
            );
        }
    }

    private void requireMatchingPreparation(
            DatasetAsset asset,
            DatasetVersion version,
            PreparedIndex prepared
    ) {
        if (prepared == null
                || !asset.getId().equals(prepared.assetId())
                || !version.getId().equals(prepared.versionId())
                || !version.getStoragePath().equals(prepared.storagePath())
                || version.getSizeBytes() == null
                || version.getSizeBytes() != prepared.sizeBytes()
                || !DatasetTaskType.normalize(asset.getType()).equals(prepared.taskType())) {
            throw new IllegalArgumentException("single-modal index preparation does not match dataset version");
        }
    }

    private DatasetPackage primaryPackage(DatasetAsset asset, DatasetVersion version) {
        DatasetPackage datasetPackage = packageRepo
                .findByStoragePathAndDeletedFalse(version.getStoragePath())
                .orElseGet(() -> {
                    DatasetPackage created = new DatasetPackage();
                    created.setId(id("dataset-pkg"));
                    created.setDatasetAssetId(asset.getId());
                    created.setStoragePath(version.getStoragePath());
                    created.setFileName(sourceName(version));
                    created.setSizeBytes(version.getSizeBytes());
                    created.setChecksum(version.getFileFingerprint());
                    created.setStatus(READY);
                    created.setCreatedAt(Instant.now());
                    created.setDeleted(false);
                    return packageRepo.saveAndFlush(created);
                });
        if (!asset.getId().equals(datasetPackage.getDatasetAssetId())) {
            throw new IllegalArgumentException("dataset package belongs to another asset");
        }
        if (!versionPackageRepo.existsByDatasetVersionIdAndPackageId(
                version.getId(),
                datasetPackage.getId()
        )) {
            DatasetVersionPackage relation = new DatasetVersionPackage();
            relation.setDatasetVersionId(version.getId());
            relation.setPackageId(datasetPackage.getId());
            relation.setPackageRole(PRIMARY);
            relation.setPackageOrder(0);
            relation.setCreatedAt(Instant.now());
            versionPackageRepo.saveAndFlush(relation);
        }
        return datasetPackage;
    }

    private void requireIndexable(DatasetAsset asset, DatasetVersion version) {
        if (asset == null || version == null || !asset.getId().equals(version.getAssetId())) {
            throw new IllegalArgumentException("dataset asset/version mismatch");
        }
        if (asset.getType() == null || asset.getType().isBlank()) {
            throw new IllegalArgumentException("dataset task type is blank");
        }
        if ("MULTIMODAL".equals(DatasetTaskType.normalize(asset.getType()))) {
            throw new IllegalArgumentException("single-modal indexer does not support MULTIMODAL");
        }
        if (version.getStoragePath() == null || version.getStoragePath().isBlank()) {
            throw new IllegalArgumentException("dataset version storagePath is blank");
        }
        if (version.getSizeBytes() == null || version.getSizeBytes() < 0) {
            throw new IllegalArgumentException("dataset version size is invalid");
        }
        if (sourceName(version).isBlank()) {
            throw new IllegalArgumentException("dataset version fileName is blank");
        }
    }

    private void demoteUnconsumable(DatasetAsset asset, DatasetVersion version) {
        version.setStatus(DRAFT);
        version.setPublishedAt(null);
        versionRepo.saveAndFlush(version);
        if (version.getId().equals(asset.getCurrentVersionId())) {
            asset.setCurrentVersionId(null);
            asset.setUpdatedAt(Instant.now());
            assetRepo.saveAndFlush(asset);
        }
    }

    private static DatasetSample toSample(
            DatasetVersion version,
            String packageId,
            ManifestSample source,
            Instant now
    ) {
        DatasetSample target = new DatasetSample();
        target.setId(id("sample"));
        target.setDatasetVersionId(version.getId());
        target.setCreatedByPackageId(packageId);
        target.setExternalId(source.externalId());
        target.setSampleIndex(source.sampleIndex());
        target.setTags(source.tags());
        target.setMetadata(source.metadata());
        target.setOwnerUserId(version.getOwnerUserId());
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        target.setDeleted(false);
        return target;
    }

    private static DatasetSampleData toData(
            DatasetVersion version,
            DatasetSample sample,
            String packageId,
            ManifestData source,
            Instant now
    ) {
        ZipEntryInfo entry = source.zipEntryInfo();
        DatasetSampleData target = new DatasetSampleData();
        target.setId(id("data"));
        target.setSampleId(sample.getId());
        target.setDatasetVersionId(version.getId());
        target.setPackageId(packageId);
        target.setDataType(source.dataType());
        target.setSensor(source.sensor());
        target.setChannel(source.channel());
        target.setSeq(source.seq());
        target.setFormat(source.format());
        target.setOriginalPath(source.path());
        target.setFileName(source.fileName());
        target.setSizeBytes(entry.uncompressedSize());
        target.setChecksum(packageId == null ? version.getFileFingerprint() : null);
        target.setContentType(source.contentType());
        target.setZipEntryOffset(entry.localHeaderOffset());
        target.setZipDataOffset(entry.zipDataOffset());
        target.setCompressedSize(entry.compressedSize());
        target.setUncompressedSize(entry.uncompressedSize());
        target.setCompressionMethod(entry.method() == 0 ? "STORED" : "DEFLATED");
        target.setCrc32(entry.crc32());
        target.setMetadata(source.metadata() == null ? Map.of() : source.metadata());
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        return target;
    }

    private static boolean isDeterministicFailure(Exception exception) {
        if (exception instanceof IllegalArgumentException
                && !(exception instanceof DatasetStorageAccessException)) {
            return true;
        }
        String message = rootMessage(exception).toLowerCase(Locale.ROOT);
        return message.contains("nosuchkey")
                || message.contains("no such key")
                || message.contains("not found")
                || message.contains("404");
    }

    private static String sourceName(DatasetVersion version) {
        return version.getFileName() == null || version.getFileName().isBlank()
                ? version.getStoragePath()
                : version.getFileName();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    public record EnsureResult(long sampleCount, boolean storageUnavailable, String message) {
        static EnsureResult success(long count) {
            return new EnsureResult(count, false, null);
        }

        static EnsureResult invalid(String message) {
            return new EnsureResult(0, false, message);
        }

        static EnsureResult storageUnavailable(String message) {
            return new EnsureResult(0, true, message);
        }

        public boolean successful() {
            return sampleCount > 0;
        }
    }

    public record PreparedIndex(
            String assetId,
            String versionId,
            String storagePath,
            long sizeBytes,
            String taskType,
            boolean zipPackage,
            ManifestImportPlan plan
    ) {
    }

    private static final class DatasetStorageAccessException extends IllegalArgumentException {
        private DatasetStorageAccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
