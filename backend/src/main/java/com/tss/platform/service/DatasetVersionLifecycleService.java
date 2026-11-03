package com.tss.platform.service;

import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetVersionRepository;
import org.springframework.stereotype.Service;

@Service
public class DatasetVersionLifecycleService {

    private static final String READY = "READY";
    private static final String DRAFT = "DRAFT";

    private final DatasetVersionRepository versionRepo;

    public DatasetVersionLifecycleService(DatasetVersionRepository versionRepo) {
        this.versionRepo = versionRepo;
    }

    public DatasetVersion assertReadyVersion(String versionId) {
        return assertReadyVersion(requireVersion(versionId));
    }

    public DatasetVersion assertMutableDraftVersion(String versionId) {
        DatasetVersion version = requireVersion(versionId);
        if (!DRAFT.equals(version.getStatus())) {
            throw new IllegalArgumentException(
                    "dataset version must be mutable DRAFT: " + version.getId()
                            + ", status=" + version.getStatus()
            );
        }
        return version;
    }

    DatasetVersion assertReadyVersion(DatasetVersion version) {
        if (version == null || version.getId() == null) {
            throw new IllegalArgumentException("dataset version not found");
        }
        if (!READY.equals(version.getStatus())) {
            throw new IllegalArgumentException(
                    "dataset version must be READY: " + version.getId()
                            + ", status=" + version.getStatus()
            );
        }
        return version;
    }

    private DatasetVersion requireVersion(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("dataset version not found");
        }
        return versionRepo.findByIdAndDeletedFalse(versionId)
                .orElseThrow(() ->
                        new IllegalArgumentException("dataset version not found: " + versionId)
                );
    }
}

