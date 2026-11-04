package com.tss.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.entity.CodeAssetAuditLog;
import com.tss.platform.model.CodeAuditActorType;
import com.tss.platform.repository.CodeAssetAuditLogRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CodeAssetAuditService {

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z0-9_]+");
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "assetId",
            "versionId",
            "workspaceId",
            "revision",
            "fileCount",
            "fileHash",
            "oldHash",
            "newHash",
            "artifactSha256",
            "policyVersion",
            "riskAssessmentId",
            "validationRunId",
            "riskLevel",
            "reviewDisposition",
            "decisionOrigin",
            "scannerVersion",
            "riskPolicyVersion",
            "findingCount",
            "reasonCode"
    );
    private static final Set<String> FORBIDDEN_KEY_PARTS = Set.of(
            "content",
            "source",
            "storage",
            "object",
            "bucket",
            "url",
            "token",
            "secret"
    );

    private final CodeAssetAuditLogRepository repository;
    private final AuthContext authContext;
    private final ObjectMapper objectMapper;

    public CodeAssetAuditService(
            CodeAssetAuditLogRepository repository,
            AuthContext authContext,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.authContext = authContext;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void workspaceOpened(String assetId, String workspaceId, long revision) {
        append(assetId, null, workspaceId, "WORKSPACE_OPENED", metadata(
                "revision", revision
        ));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assetCreated(String assetId, long revision) {
        LinkedHashMap<String, Object> metadata = metadata("revision", revision);
        metadata.put("reasonCode", "ASSET_CREATED");
        append(assetId, null, null, "CREATE", metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assetUpdated(String assetId, long revision) {
        LinkedHashMap<String, Object> metadata = metadata("revision", revision);
        metadata.put("reasonCode", "ASSET_UPDATED");
        append(assetId, null, null, "UPDATE", metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assetDeleted(String assetId, long revision) {
        LinkedHashMap<String, Object> metadata = metadata("revision", revision);
        metadata.put("reasonCode", "ASSET_DELETED");
        append(assetId, null, null, "DELETE", metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void fileUpserted(
            String assetId,
            String workspaceId,
            long revision,
            String oldHash,
            String newHash
    ) {
        LinkedHashMap<String, Object> metadata = metadata("revision", revision);
        putIfNotNull(metadata, "oldHash", oldHash);
        putIfNotNull(metadata, "newHash", newHash);
        append(assetId, null, workspaceId, "FILE_UPSERTED", metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void fileMoved(
            String assetId,
            String workspaceId,
            long revision,
            String fileHash
    ) {
        LinkedHashMap<String, Object> metadata = metadata("revision", revision);
        metadata.put("fileCount", 2L);
        putIfNotNull(metadata, "fileHash", fileHash);
        append(assetId, null, workspaceId, "FILE_MOVED", metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void fileDeleted(
            String assetId,
            String workspaceId,
            long revision,
            String oldHash
    ) {
        LinkedHashMap<String, Object> metadata = metadata("revision", revision);
        putIfNotNull(metadata, "oldHash", oldHash);
        append(assetId, null, workspaceId, "FILE_DELETED", metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void workspaceAbandoned(String assetId, String workspaceId, long revision) {
        append(assetId, null, workspaceId, "WORKSPACE_ABANDONED", metadata(
                "revision", revision
        ));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void published(
            String assetId,
            String versionId,
            String workspaceId,
            long revision,
            long fileCount,
            String artifactSha256,
            String policyVersion
    ) {
        LinkedHashMap<String, Object> metadata = metadata("revision", revision);
        metadata.put("fileCount", fileCount);
        metadata.put("artifactSha256", artifactSha256);
        metadata.put("policyVersion", policyVersion);
        append(assetId, versionId, workspaceId, "PUBLISH", metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void imported(
            String assetId,
            String versionId,
            long fileCount,
            String artifactSha256,
            String policyVersion
    ) {
        LinkedHashMap<String, Object> metadata = metadata(
                "artifactSha256", artifactSha256
        );
        metadata.put("fileCount", fileCount);
        metadata.put("policyVersion", policyVersion);
        append(assetId, versionId, null, "IMPORT", metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void artifactUpgraded(
            String assetId,
            String versionId,
            String artifactSha256
    ) {
        LinkedHashMap<String, Object> metadata = metadata(
                "artifactSha256", artifactSha256
        );
        metadata.put("reasonCode", "LEGACY_ARTIFACT_UPGRADED");
        append(assetId, versionId, null, "ARTIFACT_UPGRADE", metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void validated(
            String assetId,
            String versionId,
            String artifactSha256,
            String policyVersion,
            String reasonCode,
            long fileCount
    ) {
        LinkedHashMap<String, Object> metadata = metadata(
                "artifactSha256", artifactSha256
        );
        metadata.put("policyVersion", policyVersion);
        metadata.put("fileCount", fileCount);
        putIfNotNull(metadata, "reasonCode", reasonCode);
        append(assetId, versionId, null, "VALIDATE", metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void workspaceValidated(
            String assetId,
            String workspaceId,
            long revision,
            String artifactSha256,
            String policyVersion,
            String reasonCode,
            long fileCount
    ) {
        LinkedHashMap<String, Object> metadata = metadata("revision", revision);
        metadata.put("artifactSha256", artifactSha256);
        metadata.put("policyVersion", policyVersion);
        metadata.put("fileCount", fileCount);
        putIfNotNull(metadata, "reasonCode", reasonCode);
        append(assetId, null, workspaceId, "VALIDATE", metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(
            String assetId,
            String versionId,
            String artifactSha256,
            String policyVersion
    ) {
        decision(assetId, versionId, "APPROVE", artifactSha256, policyVersion,
                "APPROVAL_APPROVED");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rejected(String assetId, String versionId) {
        decision(assetId, versionId, "REJECT", null, null, "APPROVAL_REJECTED");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revoked(String assetId, String versionId) {
        decision(assetId, versionId, "REVOKE", null, null, "APPROVAL_REVOKED");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deprecated(String assetId, String versionId) {
        append(assetId, versionId, null, "DEPRECATE", metadata(
                "reasonCode", "VERSION_DEPRECATED"
        ));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void archived(String assetId, String versionId) {
        append(assetId, versionId, null, "ARCHIVE", metadata(
                "reasonCode", "VERSION_ARCHIVED"
        ));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void riskAssessmentQueued(
            String assetId,
            String versionId,
            String assessmentId,
            String validationRunId,
            String artifactSha256,
            String riskPolicyVersion
    ) {
        LinkedHashMap<String, Object> metadata = metadata(
                "riskAssessmentId", assessmentId
        );
        metadata.put("validationRunId", validationRunId);
        metadata.put("artifactSha256", artifactSha256);
        metadata.put("riskPolicyVersion", riskPolicyVersion);
        appendSystem(assetId, versionId, "RISK_ASSESSMENT_QUEUED", metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void riskAssessmentCompleted(
            String assetId,
            String versionId,
            String assessmentId,
            String riskLevel,
            String reviewDisposition,
            String scannerVersion,
            long findingCount
    ) {
        LinkedHashMap<String, Object> metadata = metadata(
                "riskAssessmentId", assessmentId
        );
        metadata.put("riskLevel", riskLevel);
        metadata.put("reviewDisposition", reviewDisposition);
        metadata.put("scannerVersion", scannerVersion);
        metadata.put("findingCount", findingCount);
        appendSystem(assetId, versionId, "RISK_ASSESSMENT_COMPLETED", metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void riskAssessmentFailed(
            String assetId,
            String versionId,
            String assessmentId,
            String reasonCode
    ) {
        LinkedHashMap<String, Object> metadata = metadata(
                "riskAssessmentId", assessmentId
        );
        metadata.put("reasonCode", reasonCode);
        appendSystem(assetId, versionId, "RISK_ASSESSMENT_FAILED", metadata);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void automaticDecision(
            String assetId,
            String versionId,
            String assessmentId,
            String action,
            String artifactSha256,
            String riskPolicyVersion,
            String decisionOrigin
    ) {
        LinkedHashMap<String, Object> metadata = metadata(
                "riskAssessmentId", assessmentId
        );
        metadata.put("artifactSha256", artifactSha256);
        metadata.put("riskPolicyVersion", riskPolicyVersion);
        metadata.put("decisionOrigin", decisionOrigin);
        appendSystem(assetId, versionId, action, metadata);
    }

    static Map<String, Object> validateMetadata(Map<String, ?> metadata) {
        if (metadata == null) {
            throw invalidMetadata();
        }
        LinkedHashMap<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (key == null || !ALLOWED_KEYS.contains(key) || hasForbiddenPart(key)) {
                throw invalidMetadata();
            }
            Object value = entry.getValue();
            validateValue(key, value);
            safe.put(key, value);
        }
        return Collections.unmodifiableMap(safe);
    }

    private void append(
            String assetId,
            String versionId,
            String workspaceId,
            String action,
            Map<String, ?> metadata
    ) {
        Map<String, Object> safe = validateMetadata(metadata);
        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(safe);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize safe code audit metadata", exception);
        }

        CodeAssetAuditLog auditLog = new CodeAssetAuditLog();
        auditLog.setId("code-audit-" + UUID.randomUUID().toString().replace("-", ""));
        auditLog.setAssetId(assetId);
        auditLog.setVersionId(versionId);
        auditLog.setWorkspaceId(workspaceId);
        auditLog.setAction(action);
        auditLog.setActorType(authContext.isAdmin()
                ? CodeAuditActorType.ADMIN
                : CodeAuditActorType.USER);
        auditLog.setActorUserId(authContext.currentUserId());
        auditLog.setMetadataJson(metadataJson);
        auditLog.setCreatedAt(Instant.now());
        repository.save(auditLog);
    }

    private void appendSystem(
            String assetId,
            String versionId,
            String action,
            Map<String, ?> metadata
    ) {
        Map<String, Object> safe = validateMetadata(metadata);
        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(safe);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize safe code audit metadata", exception);
        }
        CodeAssetAuditLog auditLog = new CodeAssetAuditLog();
        auditLog.setId("code-audit-" + UUID.randomUUID().toString().replace("-", ""));
        auditLog.setAssetId(assetId);
        auditLog.setVersionId(versionId);
        auditLog.setAction(action);
        auditLog.setActorType(CodeAuditActorType.SYSTEM);
        auditLog.setActorUserId(null);
        auditLog.setMetadataJson(metadataJson);
        auditLog.setCreatedAt(Instant.now());
        repository.save(auditLog);
    }

    private void decision(
            String assetId,
            String versionId,
            String action,
            String artifactSha256,
            String policyVersion,
            String reasonCode
    ) {
        LinkedHashMap<String, Object> metadata = metadata("reasonCode", reasonCode);
        putIfNotNull(metadata, "artifactSha256", artifactSha256);
        putIfNotNull(metadata, "policyVersion", policyVersion);
        append(assetId, versionId, null, action, metadata);
    }

    private static LinkedHashMap<String, Object> metadata(String key, Object value) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(key, value);
        return metadata;
    }

    private static void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private static boolean hasForbiddenPart(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return FORBIDDEN_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    private static void validateValue(String key, Object value) {
        if (value == null
                || value instanceof byte[]
                || value instanceof Map<?, ?>
                || value instanceof Iterable<?>
                || value.getClass().isArray()) {
            throw invalidMetadata();
        }
        if ("revision".equals(key)
                || "fileCount".equals(key)
                || "findingCount".equals(key)) {
            if (!(value instanceof Number number)
                    || number.longValue() < 0
                    || number.doubleValue() != number.longValue()) {
                throw invalidMetadata();
            }
            return;
        }
        if (!(value instanceof String text) || text.length() > 256) {
            throw invalidMetadata();
        }
        if (key.endsWith("Hash") || "artifactSha256".equals(key)) {
            if (!HASH.matcher(text).matches()) {
                throw invalidMetadata();
            }
        } else if ("reasonCode".equals(key) && !REASON_CODE.matcher(text).matches()) {
            throw invalidMetadata();
        }
    }

    private static IllegalArgumentException invalidMetadata() {
        return new IllegalArgumentException("Audit metadata is not allowed");
    }
}
