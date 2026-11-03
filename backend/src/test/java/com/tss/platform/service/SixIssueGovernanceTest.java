package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.repository.DatasetSampleDataRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SixIssueGovernanceTest {
    @Test
    void cyclicCauseChainDoesNotHangFailureSettlement() {
        var first = new IllegalArgumentException("first");
        var second = new IllegalStateException("second");
        first.initCause(second); second.initCause(first);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () ->
                assertEquals("IMPORT_FAILED", ImportFailureMapper.importFailure(first).code()));
    }

    @Test
    void malformedJsonHasAnExplicitCategoryEvenWhenMessageContainsClassificationKeywords() {
        var parser = new ManifestParser(new com.fasterxml.jackson.databind.ObjectMapper());
        var exception = assertThrows(ManifestValidationException.class,
                () -> parser.parse("{unsupported: invalid}", java.util.List.of(), "manifest.json"));
        assertEquals(ManifestFailureKind.INVALID_MANIFEST, exception.getFailureKind());
        assertEquals("INVALID_MANIFEST", ImportFailureMapper.importFailure(exception).code());
        assertNotNull(exception.getCause());
    }

    @Test
    void emptySingleModalPackageDoesNotNeedLegacyMessageClassification() {
        var builder = new SingleModalImportPlanBuilder();
        var exception = assertThrows(ManifestValidationException.class,
                () -> builder.build("CV", java.util.List.of(), 0));
        assertEquals(ManifestFailureKind.INVALID_MANIFEST, exception.getFailureKind());
    }

    @Test
    void explicitClassificationDoesNotDependOnWordingOrUserSuppliedPath() {
        for (ManifestFailureKind kind : ManifestFailureKind.values()) {
            for (String message : new String[]{"新的中文提示", "unsupported annotation not found duplicate external_id"}) {
                var exception = ManifestValidationException.classified(kind, message, Map.of("path", "unsupported.json"));
                assertEquals("INVALID_MANIFEST", exception.getErrorCode()); // 原解析异常契约不变
                var result = ImportFailureMapper.importFailure(new IllegalStateException("wrapper", exception));
                assertEquals(kind.name(), result.code());
                assertTrue(result.detailsJson().contains("unsupported.json"));
            }
        }
    }

    @Test
    void legacyExceptionAndExplicitBusinessCodeStayCompatible() {
        assertEquals("ANNOTATION_TARGET_AMBIGUOUS", ImportFailureMapper.importFailure(
                new ManifestValidationException("annotation data match is ambiguous")).code());
        var specific = new ManifestValidationException("CUSTOM_VALIDATION", "已有提示", Map.of("field", "samples"));
        var result = ImportFailureMapper.importFailure(specific);
        assertEquals("CUSTOM_VALIDATION", result.code());
        assertEquals("已有提示", result.message());
        assertEquals("IMPORT_FAILED", ImportFailureMapper.importFailure(new RuntimeException("unknown")).code());
    }

    @Test
    void commonRulesKeepEachEndpointErrorCode() {
        for (String code : new String[]{"INVALID_REQUEST", "INVALID_UPLOAD_REQUEST"}) {
            var policy = new DatasetWorkspaceResourcePolicy(code);
            var missing = assertThrows(V2BusinessException.class, () -> policy.requiredText(null, "缺少字段", 2));
            assertEquals(code, missing.getErrorCode()); assertEquals(HttpStatus.BAD_REQUEST, missing.getStatus());
            assertEquals("缺少字段", missing.getMessage());
            assertEquals("ab", policy.requiredText(" ab ", "required", 2));
            assertEquals(code, assertThrows(V2BusinessException.class, () -> policy.optionalText("abc", 2)).getErrorCode());
            assertNull(policy.optionalText("  ", 1));
            assertEquals(0, policy.nonNegative(null, "seq"));
            assertEquals(0, policy.nonNegative(0, "seq"));
            assertThrows(V2BusinessException.class, () -> policy.nonNegative(-1, "seq"));
            assertEquals("POINT_CLOUD", policy.dataType(" point_cloud "));
            assertThrows(V2BusinessException.class, () -> policy.dataType("new-unsupported-type"));
        }
    }

    @Test
    void annotationTargetMustBelongToSameWorkspaceAndLiveSample() {
        var repo = mock(DatasetSampleDataRepository.class);
        DatasetWorkspaceResourcePolicy.validateAnnotationTarget(repo, "w", "s", null);
        verifyNoInteractions(repo);
        when(repo.findByIdAndDatasetVersionId("d", "w")).thenReturn(Optional.empty());
        assertTargetRejected(repo);
        var data = new DatasetSampleData(); data.setSampleId("other"); data.setDeleted(false);
        when(repo.findByIdAndDatasetVersionId("d", "w")).thenReturn(Optional.of(data));
        assertTargetRejected(repo);
        data.setSampleId("s"); data.setDeleted(true); assertTargetRejected(repo);
        data.setDeleted(false);
        assertDoesNotThrow(() -> DatasetWorkspaceResourcePolicy.validateAnnotationTarget(repo, "w", "s", "d"));
        verify(repo, never()).save(any()); // 共用规则不包含隐式写入
    }

    private static void assertTargetRejected(DatasetSampleDataRepository repo) {
        var error = assertThrows(V2BusinessException.class,
                () -> DatasetWorkspaceResourcePolicy.validateAnnotationTarget(repo, "w", "s", "d"));
        assertEquals("ANNOTATION_TARGET_INVALID", error.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
    }

    @Test
    void copyMapKeepsOrderWithoutSharingTopLevelMutations() {
        var original = new LinkedHashMap<String, Object>(); original.put("a", 1); original.put("b", 2);
        var copied = DatasetWorkspaceResourcePolicy.copyMap(original); copied.put("c", 3);
        assertEquals(2, original.size()); assertEquals("[a, b, c]", copied.keySet().toString());
        assertNull(DatasetWorkspaceResourcePolicy.copyMap(null));
    }
}
