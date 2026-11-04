package com.tss.platform.service;

import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;

/** Reads requirements.txt from the already-approved immutable code archive. */
@Service
public class CodeDependencyManifestService {

    private final CodeArtifactStorageService storageService;
    private final CodeZipArchiveService archiveService;
    private final PythonRequirementsValidator requirementsValidator;

    public CodeDependencyManifestService(
            CodeArtifactStorageService storageService,
            CodeZipArchiveService archiveService,
            PythonRequirementsValidator requirementsValidator
    ) {
        this.storageService = storageService;
        this.archiveService = archiveService;
        this.requirementsValidator = requirementsValidator;
    }

    public PythonRequirementsValidator.DependencyManifest resolve(ResolvedCodeArtifact artifact) {
        if (artifact == null || artifact.storagePath() == null || artifact.storagePath().isBlank()) {
            throw new IllegalArgumentException("approved code artifact storage path is required");
        }
        StoredCodeArtifact stored = storageService.read(artifact.storagePath());
        if (!artifact.artifactSha256().equals(stored.artifactSha256())) {
            throw new CodeValidationException("ARTIFACT_SHA256_MISMATCH", "approved code artifact hash does not match");
        }
        LinkedHashMap<String, byte[]> entries = archiveService.readEntries(new ByteArrayInputStream(stored.bytes()));
        return requirementsValidator.parse(entries.get("requirements.txt"));
    }
}
