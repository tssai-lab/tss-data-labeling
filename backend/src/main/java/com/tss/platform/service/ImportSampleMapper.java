package com.tss.platform.service;

import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.model.manifest.ManifestAnnotation;
import com.tss.platform.model.manifest.ManifestData;
import com.tss.platform.model.manifest.ManifestSample;
import java.time.Instant;
import java.util.UUID;

/** 导入计划到实体的纯转换；不负责保存、事务或任务状态。 */
final class ImportSampleMapper {
    private ImportSampleMapper() {}
    static DatasetSample toSample(
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

    static DatasetSampleData toSampleData(
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
        target.setContentType(source.contentType());
        applyZipIndex(target, entry);
        target.setMetadata(source.metadata());
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        return target;
    }

    static DatasetAnnotation toAnnotation(
            DatasetVersion version,
            DatasetSample sample,
            DatasetSampleData referencedData,
            String packageId,
            ManifestAnnotation source,
            Instant now
    ) {
        ZipEntryInfo entry = source.zipEntryInfo();
        DatasetAnnotation target = new DatasetAnnotation();
        target.setId(id("annotation"));
        target.setSampleId(sample.getId());
        target.setSampleDataId(referencedData == null ? null : referencedData.getId());
        target.setDatasetVersionId(version.getId());
        target.setPackageId(packageId);
        target.setAnnotationType(source.annotationType());
        target.setFormat(source.format());
        target.setOriginalPath(source.path());
        target.setFileName(source.fileName());
        target.setSizeBytes(entry.uncompressedSize());
        target.setContentType(source.contentType());
        target.setZipEntryOffset(entry.localHeaderOffset());
        target.setZipDataOffset(entry.zipDataOffset());
        target.setCompressedSize(entry.compressedSize());
        target.setUncompressedSize(entry.uncompressedSize());
        target.setCompressionMethod(compressionMethod(entry.method()));
        target.setCrc32(entry.crc32());
        target.setMetadata(source.metadata());
        target.setCreatedAt(now);
        return target;
    }

    static void applyZipIndex(DatasetSampleData target, ZipEntryInfo entry) {
        target.setZipEntryOffset(entry.localHeaderOffset());
        target.setZipDataOffset(entry.zipDataOffset());
        target.setCompressedSize(entry.compressedSize());
        target.setUncompressedSize(entry.uncompressedSize());
        target.setCompressionMethod(compressionMethod(entry.method()));
        target.setCrc32(entry.crc32());
    }

    static String compressionMethod(int method) {
        return switch (method) {
            case 0 -> "STORED";
            case 8 -> "DEFLATED";
            default -> throw new IllegalArgumentException("unsupported ZIP compression method: " + method);
        };
    }

    static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }
}

