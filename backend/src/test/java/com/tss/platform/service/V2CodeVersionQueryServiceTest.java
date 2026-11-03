package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.v2.V2CodeApprovalRequest;
import com.tss.platform.entity.CodeApprovalRecord;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V2CodeVersionQueryServiceTest {

    private static final String SHA = "a".repeat(64);

    private CodeVersionRepository versionRepository;
    private CodeAssetRepository assetRepository;
    private CodeVersionArchiveReader archiveReader;
    private CodeFilePolicy filePolicy;
    private CodePathPolicy pathPolicy;
    private CodeArtifactStorageService storageService;
    private CodeValidationService validationService;
    private CodeApprovalService approvalService;
    private CodeArtifactResolver resolver;
    private CodeAssetAuditService auditService;
    private AuthContext authContext;
    private V2CodeVersionQueryService service;

    @BeforeEach
    void setUp() {
        versionRepository = mock(CodeVersionRepository.class);
        assetRepository = mock(CodeAssetRepository.class);
        archiveReader = mock(CodeVersionArchiveReader.class);
        filePolicy = new CodeFilePolicy();
        pathPolicy = new CodePathPolicy();
        storageService = mock(CodeArtifactStorageService.class);
        validationService = mock(CodeValidationService.class);
        approvalService = mock(CodeApprovalService.class);
        resolver = mock(CodeArtifactResolver.class);
        auditService = mock(CodeAssetAuditService.class);
        authContext = mock(AuthContext.class);
        service = new V2CodeVersionQueryService(
                versionRepository,
                assetRepository,
                archiveReader,
                filePolicy,
                pathPolicy,
                storageService,
                validationService,
                approvalService,
                resolver,
                auditService,
                authContext,
                new CodeAccessPolicy(authContext)
        );
    }

    @Test
    void directVersionAccessRequiresExactOwnerEvenForAdministrator() {
        CodeVersion version = version();
        CodeAsset asset = asset();
        when(versionRepository.findAssetIdByIdAndDeletedFalse("version-1"))
                .thenReturn(Optional.of("asset-1"));
        when(assetRepository.findByIdAndDeletedFalse("asset-1"))
                .thenReturn(Optional.of(asset));
        when(authContext.currentUserId()).thenReturn(999);
        when(authContext.isAdmin()).thenReturn(true);

        assertThrows(CodeAssetAccessException.class, () -> service.get("version-1"));

        verify(versionRepository, never())
                .findByIdAndAssetIdAndDeletedFalse("version-1", "asset-1");
    }

    @Test
    void explicitAdministratorEntryPointReadsCrossOwnerVersion() {
        CodeVersion version = version();
        CodeAsset asset = asset();
        when(authContext.isAdmin()).thenReturn(true);
        when(authContext.currentUserId()).thenReturn(999);
        when(versionRepository.findAssetIdByIdAndDeletedFalse("version-1"))
                .thenReturn(Optional.of("asset-1"));
        when(assetRepository.findByIdAndDeletedFalse("asset-1"))
                .thenReturn(Optional.of(asset));
        when(versionRepository.findByIdAndAssetIdAndDeletedFalse(
                "version-1", "asset-1"
        )).thenReturn(Optional.of(version));

        var result = service.getAdmin("version-1");

        assertEquals("version-1", result.id());
        assertEquals(7, version.getOwnerUserId());
    }

    @Test
    void directOwnerCheckPrecedesValidationAndArchiveReads() {
        CodeVersion version = version();
        CodeAsset asset = asset();
        version.setValidationStatus("FAILED");
        ownerScope(version, asset, 999);

        assertThrows(CodeAssetAccessException.class,
                () -> service.tree("version-1", "src"));

        verify(archiveReader, never()).list(version, 7);
    }

    @Test
    void treeRequiresCurrentPassingValidationAndNeverReadsLegacyNotRunArchive() {
        CodeVersion version = version();
        CodeAsset asset = asset();
        version.setValidationStatus("NOT_RUN");
        ownerScope(version, asset, 7);

        CodeValidationException exception = assertThrows(
                CodeValidationException.class,
                () -> service.tree("version-1", null)
        );

        assertEquals("VALIDATION_NOT_CURRENT", exception.getReasonCode());
        verify(archiveReader, never()).list(version, 7);
    }

    @Test
    void treeReturnsSortedDirectChildrenWithReadOnlyFiles() {
        CodeVersion version = version();
        ownerScope(version, asset(), 7);
        when(authContext.canAccessObjectName(version.getStoragePath(), 7)).thenReturn(true);
        when(archiveReader.list(version, 7)).thenReturn(List.of(
                entry("src/utils/io.py", 8),
                entry("src/train.py", 12),
                entry("README.md", 20)
        ));

        var root = service.tree("version-1", null);
        var src = service.tree("version-1", "src");

        assertEquals(List.of("src", "README.md"),
                root.stream().map(node -> node.path()).toList());
        assertEquals(List.of("src/utils", "src/train.py"),
                src.stream().map(node -> node.path()).toList());
        assertFalse(src.get(1).editable());
        assertEquals("python", src.get(1).languageId());
    }

    @Test
    void contentPreservesRawBytesForHashButReturnsStrictUtf8Text() {
        CodeVersion version = version();
        ownerScope(version, asset(), 7);
        when(authContext.canAccessObjectName(version.getStoragePath(), 7)).thenReturn(true);
        byte[] raw = "print('ok')\r\n".getBytes(StandardCharsets.UTF_8);
        CodeArchiveEntry entry = entry("src/train.py", raw.length);
        when(archiveReader.list(version, 7)).thenReturn(List.of(entry));
        when(archiveReader.read(version, 7, entry, CodeFilePolicy.EDITABLE_LIMIT_BYTES))
                .thenReturn(raw);

        var content = service.content("version-1", "src/train.py");

        assertEquals("print('ok')\r\n", content.content());
        assertEquals(filePolicy.sha256(raw), content.contentHash());
        assertTrue(content.readOnly());
        assertFalse(content.editable());
        assertEquals(null, content.workspaceRevision());
    }

    @Test
    void oversizedContentFailsWhileSingleFileDownloadStillSucceeds() {
        CodeVersion version = version();
        ownerScope(version, asset(), 7);
        when(authContext.canAccessObjectName(version.getStoragePath(), 7)).thenReturn(true);
        CodeArchiveEntry large = entry("large.txt", CodeFilePolicy.EDITABLE_LIMIT_BYTES + 1);
        when(archiveReader.list(version, 7)).thenReturn(List.of(large));
        when(archiveReader.read(version, 7, large, CodeFilePolicy.EDITABLE_LIMIT_BYTES))
                .thenThrow(new CodeContentTooLargeException());
        byte[] raw = new byte[] {1, 2, 3};
        when(archiveReader.read(
                version,
                7,
                large,
                CodeVersionArchiveReader.MAX_CODE_UNCOMPRESSED_BYTES
        )).thenReturn(raw);

        assertThrows(CodeContentTooLargeException.class,
                () -> service.content("version-1", "large.txt"));
        assertArrayEquals(raw, service.downloadFile("version-1", "large.txt").bytes());
    }

    @Test
    void contentAtExactlyOneMiBRemainsPreviewable() {
        CodeVersion version = version();
        ownerScope(version, asset(), 7);
        when(authContext.canAccessObjectName(version.getStoragePath(), 7)).thenReturn(true);
        byte[] raw = new byte[(int) CodeFilePolicy.EDITABLE_LIMIT_BYTES];
        Arrays.fill(raw, (byte) 'a');
        CodeArchiveEntry boundary = entry("boundary.txt", raw.length);
        when(archiveReader.list(version, 7)).thenReturn(List.of(boundary));
        when(archiveReader.read(
                version,
                7,
                boundary,
                CodeFilePolicy.EDITABLE_LIMIT_BYTES
        )).thenReturn(raw);

        var result = service.content("version-1", "boundary.txt");

        assertEquals(CodeFilePolicy.EDITABLE_LIMIT_BYTES, result.sizeBytes());
        assertTrue(result.previewable());
        assertTrue(result.readOnly());
        assertFalse(result.editable());
    }

    @Test
    void completeArchiveRecoveryDoesNotRequirePassingValidation() {
        CodeVersion version = version();
        version.setStoragePath("users/7/codes/asset-1/v0/legacy.zip");
        version.setValidationStatus("NOT_RUN");
        version.setValidationPolicyVersion(null);
        version.setArtifactSha256(null);
        ownerScope(version, asset(), 7);
        when(authContext.canAccessObjectName(version.getStoragePath(), 7)).thenReturn(true);
        byte[] archive = new byte[] {1, 2, 3};
        when(storageService.read(version.getStoragePath())).thenReturn(
                new StoredCodeArtifact(version.getStoragePath(), archive, "b".repeat(64), 3)
        );

        var download = service.downloadArchive("version-1");

        assertArrayEquals(archive, download.bytes());
        assertEquals("application/zip", download.contentType());
    }

    @Test
    void downloadDefensivelyCopiesSourceAndReturnedBytes() {
        byte[] source = new byte[]{1, 2, 3};
        V2CodeVersionQueryService.Download download =
                new V2CodeVersionQueryService.Download(
                        "code.zip", "application/zip", source
                );
        source[0] = 9;
        byte[] exposed = download.bytes();
        exposed[1] = 8;

        assertArrayEquals(new byte[]{1, 2, 3}, download.bytes());
    }

    @Test
    void consumerManifestChecksOwnerBeforeResolverEligibility() {
        CodeVersion version = version();
        ownerScope(version, asset(), 999);

        assertThrows(CodeAssetAccessException.class,
                () -> service.consumerManifest("version-1"));

        verify(resolver, never()).resolve("version-1", 999);
    }

    @Test
    void consumerManifestDoesNotWrapResolverWriteLockInReadOnlyTransaction() throws Exception {
        Transactional transaction = V2CodeVersionQueryService.class
                .getMethod("consumerManifest", String.class)
                .getAnnotation(Transactional.class);

        assertTrue(transaction == null || !transaction.readOnly());
    }

    @Test
    void serializedVersionAndManifestNeverExposeInternalStoragePropertiesOrValues()
            throws Exception {
        CodeVersion version = version();
        String internalPath = version.getStoragePath();
        ownerScope(version, asset(), 7);
        when(resolver.resolve("version-1", 7)).thenReturn(new ResolvedCodeArtifact(
                "asset-1",
                "version-1",
                "training",
                "python:3.11",
                "src/train.py",
                "CUSTOM",
                "CUSTOM_PYTHON",
                SHA,
                "validation-1",
                CodeArtifactAssembler.POLICY_VERSION,
                "approval-1",
                internalPath
        ));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        String versionJson = mapper.writeValueAsString(service.get("version-1"));
        String manifestJson = mapper.writeValueAsString(
                service.consumerManifest("version-1")
        );
        String combined = versionJson + manifestJson;

        assertFalse(combined.contains("storagePath"));
        assertFalse(combined.contains("ownerUserId"));
        assertFalse(combined.contains("deleted"));
        assertFalse(combined.contains(internalPath));
        assertFalse(combined.contains("downloadUrl"));
    }

    @Test
    void approvalDelegatesWithoutOwnerPrelookupSoAuthorityIsCheckedFirst() {
        CodeApprovalRecord record = approvalRecord();
        when(approvalService.decide(
                eq("missing-version"), eq(CodeApprovalService.Decision.APPROVE),
                eq(null), any(CodeApprovalService.ApprovalExpectation.class)
        )).thenReturn(record);

        var result = service.approve(
                "missing-version", approvalRequest()
        );

        assertEquals("APPROVED", result.decision());
        verify(versionRepository, never()).findAssetIdByIdAndDeletedFalse("missing-version");
    }

    @Test
    void nonAdministratorIsForbiddenBeforeNullOrInvalidApprovalRequestValidation() {
        doThrow(new CodeApprovalForbiddenException())
                .when(approvalService).requireAdministratorAuthority();

        assertThrows(CodeApprovalForbiddenException.class,
                () -> service.approve("missing-version", null));
        assertThrows(CodeApprovalForbiddenException.class,
                () -> service.approve(
                        "missing-version",
                        new V2CodeApprovalRequest("invalid", null)
                ));

        verify(approvalService, times(2)).requireAdministratorAuthority();
        verify(approvalService, never()).decide(any(), any(), any());
        verify(approvalService, never()).decide(any(), any(), any(), any());
        verify(versionRepository, never()).findAssetIdByIdAndDeletedFalse("missing-version");
        verify(assetRepository, never()).findByIdAndDeletedFalse("asset-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "APPROVAL_TERMINAL",
            "APPROVAL_STATE_INVALID",
            "VERSION_NOT_READY"
    })
    void approvalStateFailuresBecomeConflicts(String reasonCode) {
        when(approvalService.decide(
                eq("version-1"), eq(CodeApprovalService.Decision.APPROVE),
                eq(null), any(CodeApprovalService.ApprovalExpectation.class)
        )).thenThrow(new CodeValidationException(reasonCode, "core state failure"));

        CodeWorkspaceConflictException exception = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.approve(
                        "version-1", approvalRequest()
                )
        );

        assertEquals(reasonCode, exception.getReasonCode());
    }

    @Test
    void approvalEvidenceFailureRemainsUnprocessableValidation() {
        when(approvalService.decide(
                eq("version-1"), eq(CodeApprovalService.Decision.APPROVE),
                eq(null), any(CodeApprovalService.ApprovalExpectation.class)
        )).thenThrow(new CodeValidationException(
                "VALIDATION_EVIDENCE_MISSING", "core evidence failure"
        ));

        CodeValidationException exception = assertThrows(
                CodeValidationException.class,
                () -> service.approve(
                        "version-1", approvalRequest()
                )
        );

        assertEquals("VALIDATION_EVIDENCE_MISSING", exception.getReasonCode());
    }

    @Test
    void validationStorageFailureIsReportedAsUnavailableInsteadOfUserValidation() {
        CodeVersion version = version();
        ownerScope(version, asset(), 7);
        when(validationService.validateVersion("version-1")).thenReturn(
                new CodeValidationResult(
                        CodeArtifactAssembler.POLICY_VERSION,
                        SHA,
                        "FAILED",
                        "STORAGE_READ_FAILED",
                        "Code artifact could not be read",
                        0
                )
        );

        assertThrows(CodeArtifactStorageException.class,
                () -> service.validate("version-1"));
    }

    @Test
    void validationResponseExposesReusedEvidenceWithoutChangingStatus() {
        CodeVersion version = version();
        ownerScope(version, asset(), 7);
        when(validationService.validateVersion("version-1")).thenReturn(
                new CodeValidationResult(
                        CodeArtifactAssembler.POLICY_VERSION,
                        SHA,
                        "PASSED",
                        null,
                        "Code artifact validation passed",
                        1,
                        true
                )
        );

        var result = service.validate("version-1");

        assertEquals("PASSED", result.status());
        assertTrue(result.reused());
    }

    @Test
    void lifecycleUsesAssetThenVersionLocksAndIsIdempotent() {
        CodeVersion version = version();
        CodeAsset asset = asset();
        when(authContext.currentUserId()).thenReturn(7);
        when(versionRepository.findAssetIdByIdAndDeletedFalse("version-1"))
                .thenReturn(Optional.of("asset-1"));
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(asset));
        when(versionRepository.findByIdAndDeletedFalseForUpdate("version-1"))
                .thenReturn(Optional.of(version));

        var deprecated = service.deprecate("version-1");
        var again = service.deprecate("version-1");

        assertEquals("DEPRECATED", deprecated.status());
        assertEquals("DEPRECATED", again.status());
        assertEquals(SHA, version.getArtifactSha256());
        verify(auditService).deprecated("asset-1", "version-1");
        verify(versionRepository, times(1)).saveAndFlush(version);
        var order = inOrder(versionRepository, assetRepository);
        order.verify(versionRepository).findAssetIdByIdAndDeletedFalse("version-1");
        order.verify(assetRepository).findByIdAndDeletedFalseForUpdate("asset-1");
        order.verify(versionRepository).findByIdAndDeletedFalseForUpdate("version-1");
    }

    @Test
    void explicitAdministratorLifecyclePreservesForeignOwner() {
        CodeVersion version = version();
        CodeAsset asset = asset();
        when(authContext.isAdmin()).thenReturn(true);
        when(authContext.currentUserId()).thenReturn(99);
        when(versionRepository.findAssetIdByIdAndDeletedFalse("version-1"))
                .thenReturn(Optional.of("asset-1"));
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(asset));
        when(versionRepository.findByIdAndDeletedFalseForUpdate("version-1"))
                .thenReturn(Optional.of(version));

        var deprecated = service.deprecateAdmin("version-1");

        assertEquals("DEPRECATED", deprecated.status());
        assertEquals(7, version.getOwnerUserId());
        verify(auditService).deprecated("asset-1", "version-1");
    }

    @Test
    void archivedVersionCannotBeRestoredOrDeprecated() {
        CodeVersion version = version();
        version.setStatus("ARCHIVED");
        ownerLockedScope(version, asset(), 7);

        CodeWorkspaceConflictException exception = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.deprecate("version-1")
        );

        assertEquals("VERSION_LIFECYCLE_CONFLICT", exception.getReasonCode());
        verify(versionRepository, never()).saveAndFlush(version);
    }

    private void ownerScope(CodeVersion version, CodeAsset asset, int currentUserId) {
        when(authContext.currentUserId()).thenReturn(currentUserId);
        when(versionRepository.findAssetIdByIdAndDeletedFalse("version-1"))
                .thenReturn(Optional.of("asset-1"));
        when(assetRepository.findByIdAndDeletedFalse("asset-1"))
                .thenReturn(Optional.of(asset));
        if (currentUserId == asset.getOwnerUserId()) {
            when(versionRepository.findByIdAndAssetIdAndDeletedFalse("version-1", "asset-1"))
                    .thenReturn(Optional.of(version));
        }
    }

    private void ownerLockedScope(CodeVersion version, CodeAsset asset, int currentUserId) {
        when(authContext.currentUserId()).thenReturn(currentUserId);
        when(versionRepository.findAssetIdByIdAndDeletedFalse("version-1"))
                .thenReturn(Optional.of("asset-1"));
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(asset));
        when(versionRepository.findByIdAndDeletedFalseForUpdate("version-1"))
                .thenReturn(Optional.of(version));
    }

    private static CodeAsset asset() {
        CodeAsset asset = new CodeAsset();
        asset.setId("asset-1");
        asset.setOwnerUserId(7);
        asset.setDeleted(false);
        return asset;
    }

    private static CodeVersion version() {
        CodeVersion version = new CodeVersion();
        version.setId("version-1");
        version.setAssetId("asset-1");
        version.setVersion("v1");
        version.setFileName("code.zip");
        version.setStoragePath("users/7/codes/asset-1/versions/version-1/archive.zip");
        version.setSizeBytes(123L);
        version.setStatus("READY");
        version.setArtifactSha256(SHA);
        version.setValidationStatus("PASSED");
        version.setValidationPolicyVersion(CodeArtifactAssembler.POLICY_VERSION);
        version.setApprovalStatus("PENDING");
        version.setOwnerUserId(7);
        version.setCreatedAt(Instant.EPOCH);
        version.setUpdatedAt(Instant.EPOCH);
        version.setDeleted(false);
        return version;
    }

    private static CodeArchiveEntry entry(String path, long size) {
        return new CodeArchiveEntry(path, 8, Math.max(1, size / 2), size, 0, 50);
    }

    private static CodeApprovalRecord approvalRecord() {
        CodeApprovalRecord record = new CodeApprovalRecord();
        record.setId("approval-1");
        record.setVersionId("version-1");
        record.setDecision("APPROVED");
        record.setArtifactSha256(SHA);
        record.setValidationRunId("validation-1");
        record.setPolicyVersion(CodeArtifactAssembler.POLICY_VERSION);
        record.setCreatedAt(Instant.EPOCH);
        return record;
    }

    private static V2CodeApprovalRequest approvalRequest() {
        return new V2CodeApprovalRequest(
                "APPROVE",
                null,
                "validation-1",
                "risk-1",
                SHA,
                CodeStaticRiskScanner.RISK_POLICY_VERSION
        );
    }
}
