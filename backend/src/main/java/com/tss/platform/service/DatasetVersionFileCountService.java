package com.tss.platform.service;

import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasetVersionFileCountService {

    private final DatasetSampleDataRepository dataRepo;
    private final DatasetAnnotationRepository annotationRepo;
    private final ZipCentralDirectoryReader zipReader;
    private final DatasetVersionRepository versionRepo;

    @Autowired
    public DatasetVersionFileCountService(
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo,
            ZipCentralDirectoryReader zipReader,
            DatasetVersionRepository versionRepo
    ) {
        this.dataRepo = dataRepo;
        this.annotationRepo = annotationRepo;
        this.zipReader = zipReader;
        this.versionRepo = versionRepo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long countCurrentVersionFiles(DatasetAsset asset, DatasetVersion version) {
        if (version == null) {
            return null;
        }
        if (version.getFileCount() != null) {
            return version.getFileCount();
        }
        Long computed = computeCurrentVersionFiles(asset, version);
        if (computed != null && version.getId() != null) {
            versionRepo.updateFileCountIfAbsent(version.getId(), computed);
        }
        return computed;
    }

    private Long computeCurrentVersionFiles(DatasetAsset asset, DatasetVersion version) {
        long metadataCount = dataRepo.countByDatasetVersionId(version.getId())
                + annotationRepo.countByDatasetVersionId(version.getId());
        if (asset != null && "MULTIMODAL".equalsIgnoreCase(asset.getType())) {
            return metadataCount;
        }
        if (metadataCount > 0) {
            return metadataCount;
        }
        if (isBlank(version.getStoragePath())) {
            return null;
        }
        if (!isZip(sourceName(version))) {
            return 1L;
        }
        if (version.getSizeBytes() == null) {
            return null;
        }
        try {
            return zipReader.read(version.getStoragePath(), version.getSizeBytes()).stream()
                    .filter(entry -> !entry.directory())
                    .count();
        } catch (Exception exception) {
            return null;
        }
    }

    private String sourceName(DatasetVersion version) {
        return !isBlank(version.getFileName())
                ? version.getFileName()
                : version.getStoragePath();
    }

    private boolean isZip(String path) {
        return path != null && path.toLowerCase().endsWith(".zip");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
