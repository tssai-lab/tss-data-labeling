package com.tss.platform.service;

import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeRiskAssessment;
import com.tss.platform.entity.CodeRiskFinding;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeRiskAssessmentRepository;
import com.tss.platform.repository.CodeRiskFindingRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class V2AdminCodeReviewServiceTest {

    private static final String SHA = "a".repeat(64);

    private CodeVersionRepository versionRepository;
    private CodeAssetRepository assetRepository;
    private CodeRiskAssessmentRepository assessmentRepository;
    private CodeRiskFindingRepository findingRepository;
    private CodeVersionArchiveReader archiveReader;
    private CodeRiskAssessmentRescanService rescanService;
    private AuthContext authContext;
    private V2AdminCodeReviewService service;

    @BeforeEach
    void setUp() {
        versionRepository = mock(CodeVersionRepository.class);
        assetRepository = mock(CodeAssetRepository.class);
        assessmentRepository = mock(CodeRiskAssessmentRepository.class);
        findingRepository = mock(CodeRiskFindingRepository.class);
        archiveReader = mock(CodeVersionArchiveReader.class);
        rescanService = mock(CodeRiskAssessmentRescanService.class);
        authContext = mock(AuthContext.class);
        service = new V2AdminCodeReviewService(
                versionRepository,
                assetRepository,
                assessmentRepository,
                findingRepository,
                archiveReader,
                new CodePathPolicy(),
                new CodeFilePolicy(),
                rescanService,
                authContext
        );
    }

    @Test
    void nonAdministratorIsRejectedBeforeAnyResourceLookup() {
        when(authContext.isAdmin()).thenReturn(false);

        assertThrows(CodeApprovalForbiddenException.class,
                () -> service.detail("version-secret"));
        assertThrows(CodeApprovalForbiddenException.class,
                () -> service.list(
                        "PENDING", null, null, null,
                        null, null, "SUBMITTED_AT", "DESC", 0, 20
                ));

        verifyNoInteractions(
                versionRepository,
                assetRepository,
                assessmentRepository,
                findingRepository,
                archiveReader,
                rescanService
        );
    }

    @Test
    void queueDefaultsAreFilteredAndMappedWithoutStorageDetails() {
        when(authContext.isAdmin()).thenReturn(true);
        CodeVersion version = version();
        CodeAsset asset = asset();
        CodeRiskAssessment assessment = assessment();
        when(versionRepository.findAll(
                any(Specification.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(version), PageRequest.of(0, 10), 1
        ));
        when(assetRepository.findAllById(List.of("asset-1"))).thenReturn(List.of(asset));
        when(assessmentRepository.findById("risk-1")).thenReturn(Optional.of(assessment));

        var result = service.list(
                "pending", "high", 7, " unsafe ",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                "submittedAt", "desc", 0, 10
        );

        assertEquals(1, result.totalElements());
        assertEquals("asset name", result.items().get(0).assetName());
        assertEquals("risk-1", result.items().get(0).riskAssessmentId());
        assertEquals(2, result.items().get(0).findingCount());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(versionRepository).findAll(
                any(Specification.class), pageable.capture()
        );
        assertEquals(10, pageable.getValue().getPageSize());
        assertEquals("createdAt: DESC,id: DESC", pageable.getValue().getSort().toString());
    }

    @Test
    void absentFiltersBuildDynamicSpecificationWithoutNullableQueryParameters() {
        when(authContext.isAdmin()).thenReturn(true);
        when(versionRepository.findAll(
                any(Specification.class), any(Pageable.class)
        )).thenReturn(org.springframework.data.domain.Page.empty());

        var result = service.list(
                "PENDING", null, null, null,
                null, null, "SUBMITTED_AT", "DESC", 0, 20
        );

        assertTrue(result.items().isEmpty());
        assertEquals(0, result.totalElements());
        verify(versionRepository).findAll(
                any(Specification.class), any(Pageable.class)
        );
    }

    @Test
    void missingVersionReturnsHiddenNotFoundAfterAdminCheck() {
        when(authContext.isAdmin()).thenReturn(true);
        when(versionRepository.findByIdAndDeletedFalse("missing"))
                .thenReturn(Optional.empty());

        assertThrows(CodeAssetAccessException.class, () -> service.detail("missing"));

        verify(assetRepository, never()).findByIdAndDeletedFalse(any());
    }

    @Test
    void treeAndContentAreReadOnlyAndBoundedToOneMiB() {
        adminScope();
        CodeVersion version = version();
        CodeArchiveEntry file = entry("src/train.py", 12);
        CodeArchiveEntry nested = entry("src/lib/io.py", 9);
        when(archiveReader.list(any(CodeVersion.class), eq(7)))
                .thenReturn(List.of(file, nested));
        byte[] bytes = "print('ok')\n".getBytes(StandardCharsets.UTF_8);
        when(archiveReader.read(
                any(CodeVersion.class), eq(7), eq(file),
                eq(CodeFilePolicy.EDITABLE_LIMIT_BYTES)
        )).thenReturn(bytes);

        var tree = service.tree("version-1", "src");
        var content = service.content("version-1", "src/train.py");

        assertEquals(List.of("src/lib", "src/train.py"),
                tree.stream().map(node -> node.path()).toList());
        assertFalse(tree.get(1).editable());
        assertTrue(content.readOnly());
        assertFalse(content.editable());
        assertEquals("python", content.languageId());
        assertEquals("print('ok')\n", content.content());
        assertNull(content.workspaceRevision());

        CodeArchiveEntry large = entry(
                "large.txt", CodeFilePolicy.EDITABLE_LIMIT_BYTES + 1
        );
        when(archiveReader.list(any(CodeVersion.class), eq(7)))
                .thenReturn(List.of(large));
        assertThrows(CodeContentTooLargeException.class,
                () -> service.content("version-1", "large.txt"));
        verify(archiveReader, never()).read(
                any(CodeVersion.class), eq(7), eq(large), anyLong()
        );
    }

    @Test
    void findingsReturnOnlySanitizedRuleMetadata() {
        adminScope();
        CodeRiskAssessment assessment = assessment();
        when(assessmentRepository.findById("risk-1")).thenReturn(Optional.of(assessment));
        CodeRiskFinding finding = new CodeRiskFinding();
        finding.setId("finding-1");
        finding.setRiskAssessmentId("risk-1");
        finding.setRuleId("PY_SUBPROCESS");
        finding.setSeverity("HIGH");
        finding.setCategory("PROCESS");
        finding.setFilePath("src/train.py");
        finding.setLineStart(3);
        finding.setLineEnd(3);
        finding.setDescription("Subprocess call detected\u0000");
        when(findingRepository
                .findByRiskAssessmentIdOrderByFilePathAscLineStartAscIdAsc("risk-1"))
                .thenReturn(List.of(finding));

        var result = service.findings("version-1");

        assertEquals(1, result.size());
        assertEquals("Subprocess call detected", result.get(0).description());
        assertEquals("src/train.py", result.get(0).filePath());
    }

    @Test
    void rescanDelegatesOnlyAfterScopeLookupAndRejectsStaleEvidence() {
        adminScope();
        CodeRiskAssessment assessment = assessment();
        when(rescanService.rescan("version-1")).thenReturn(assessment);

        var result = service.rescan("version-1");

        assertEquals("risk-1", result.id());
        verify(rescanService).rescan("version-1");

        assessment.setArtifactSha256("b".repeat(64));
        assertThrows(CodeValidationException.class,
                () -> service.rescan("version-1"));
    }

    @Test
    void invalidFiltersFailBeforeQueueQuery() {
        when(authContext.isAdmin()).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.list(
                "PENDING", "CRITICAL", null, null,
                null, null, "SUBMITTED_AT", "DESC", 0, 20
        ));
        assertThrows(IllegalArgumentException.class, () -> service.list(
                "PENDING", null, null, null,
                Instant.EPOCH.plusSeconds(1), Instant.EPOCH,
                "SUBMITTED_AT", "DESC", 0, 20
        ));
        assertThrows(IllegalArgumentException.class, () -> service.list(
                "PENDING", null, null, null,
                null, null, "SUBMITTED_AT", "DESC", 0, 101
        ));
        assertThrows(IllegalArgumentException.class, () -> service.list(
                "PENDING", null, null, null,
                null, null, "ASSET_NAME", "DESC", 0, 20
        ));

        verify(versionRepository, never()).findAll(
                any(Specification.class), any(Pageable.class)
        );
    }

    private void adminScope() {
        when(authContext.isAdmin()).thenReturn(true);
        when(versionRepository.findByIdAndDeletedFalse("version-1"))
                .thenReturn(Optional.of(version()));
        when(assetRepository.findByIdAndDeletedFalse("asset-1"))
                .thenReturn(Optional.of(asset()));
    }

    private static CodeAsset asset() {
        CodeAsset asset = new CodeAsset();
        asset.setId("asset-1");
        asset.setName("asset name");
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
        version.setApprovalStatus("PENDING");
        version.setArtifactSha256(SHA);
        version.setValidationStatus("PASSED");
        version.setValidationPolicyVersion("code-asset-policy-v1");
        version.setLatestRiskAssessmentId("risk-1");
        version.setRiskStatus("COMPLETED");
        version.setRiskLevel("HIGH");
        version.setReviewDisposition("MANUAL_REVIEW");
        version.setRiskPolicyVersion("risk-v1");
        version.setOwnerUserId(7);
        version.setCreatedAt(Instant.EPOCH);
        version.setUpdatedAt(Instant.EPOCH);
        version.setDeleted(false);
        return version;
    }

    private static CodeRiskAssessment assessment() {
        CodeRiskAssessment assessment = new CodeRiskAssessment();
        assessment.setId("risk-1");
        assessment.setVersionId("version-1");
        assessment.setValidationRunId("validation-1");
        assessment.setArtifactSha256(SHA);
        assessment.setRiskPolicyVersion("risk-v1");
        assessment.setScannerVersion("scanner-v1");
        assessment.setStatus("COMPLETED");
        assessment.setRiskLevel("HIGH");
        assessment.setDisposition("MANUAL_REVIEW");
        assessment.setFindingCount(2);
        assessment.setCreatedAt(Instant.EPOCH);
        assessment.setCompletedAt(Instant.EPOCH.plusSeconds(1));
        return assessment;
    }

    private static CodeArchiveEntry entry(String path, long size) {
        return new CodeArchiveEntry(path, 8, Math.max(1, size / 2), size, 0, 50);
    }
}
