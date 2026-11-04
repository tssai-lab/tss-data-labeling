package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.entity.CodeAssetAuditLog;
import com.tss.platform.repository.CodeAssetAuditLogRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeAssetAuditServiceTest {

    private final CodeAssetAuditLogRepository repository = mock(CodeAssetAuditLogRepository.class);
    private final AuthContext authContext = mock(AuthContext.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private CodeAssetAuditService service;

    @BeforeEach
    void setUp() {
        service = new CodeAssetAuditService(repository, authContext, objectMapper);
        when(authContext.currentUserId()).thenReturn(7);
        when(repository.save(any(CodeAssetAuditLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void stronglyTypedMethodsAreMandatoryAndPersistOnlySafeMetadata() throws Exception {
        assertMandatory("workspaceOpened", String.class, String.class, long.class);
        assertMandatory("fileUpserted", String.class, String.class, long.class,
                String.class, String.class);
        assertMandatory("fileMoved", String.class, String.class, long.class, String.class);
        assertMandatory("fileDeleted", String.class, String.class, long.class, String.class);
        assertMandatory("workspaceAbandoned", String.class, String.class, long.class);
        assertMandatory("imported", String.class, String.class, long.class,
                String.class, String.class);
        assertMandatory("artifactUpgraded", String.class, String.class, String.class);
        assertMandatory("assetDeleted", String.class, long.class);

        service.fileUpserted(
                "asset-1",
                "workspace-1",
                3L,
                "a".repeat(64),
                "b".repeat(64)
        );

        var captor = org.mockito.ArgumentCaptor.forClass(CodeAssetAuditLog.class);
        verify(repository).save(captor.capture());
        CodeAssetAuditLog saved = captor.getValue();
        assertNotNull(saved.getId());
        assertEquals("asset-1", saved.getAssetId());
        assertEquals("workspace-1", saved.getWorkspaceId());
        assertEquals("FILE_UPSERTED", saved.getAction());
        assertEquals("USER", saved.getActorType());
        assertEquals(7, saved.getActorUserId());
        assertTrue(saved.getMetadataJson().contains("\"revision\":3"));
        assertTrue(saved.getMetadataJson().contains("\"oldHash\""));
        assertTrue(saved.getMetadataJson().contains("\"newHash\""));
        assertFalse(saved.getMetadataJson().toLowerCase().contains("path"));
    }

    @Test
    void artifactUpgradeAuditContainsOnlyHashAndStableReasonCode() {
        service.artifactUpgraded("asset-1", "version-1", "a".repeat(64));

        var captor = org.mockito.ArgumentCaptor.forClass(CodeAssetAuditLog.class);
        verify(repository).save(captor.capture());
        CodeAssetAuditLog saved = captor.getValue();
        assertEquals("ARTIFACT_UPGRADE", saved.getAction());
        assertEquals("version-1", saved.getVersionId());
        assertTrue(saved.getMetadataJson().contains("\"artifactSha256\""));
        assertTrue(saved.getMetadataJson().contains("\"reasonCode\":\"LEGACY_ARTIFACT_UPGRADED\""));
        String metadata = saved.getMetadataJson().toLowerCase();
        assertFalse(metadata.contains("storage"));
        assertFalse(metadata.contains("object"));
        assertFalse(metadata.contains("bucket"));
        assertFalse(metadata.contains("url"));
    }

    @Test
    void assetDeleteAuditContainsOnlyRevisionAndStableReasonCode() {
        service.assetDeleted("asset-1", 4L);

        var captor = org.mockito.ArgumentCaptor.forClass(CodeAssetAuditLog.class);
        verify(repository).save(captor.capture());
        CodeAssetAuditLog saved = captor.getValue();
        assertEquals("DELETE", saved.getAction());
        assertTrue(saved.getMetadataJson().contains("\"revision\":4"));
        assertTrue(saved.getMetadataJson().contains("\"reasonCode\":\"ASSET_DELETED\""));
        assertFalse(saved.getMetadataJson().toLowerCase().contains("storage"));
    }

    @Test
    void administratorActionsUseExplicitAdminActorType() {
        when(authContext.isAdmin()).thenReturn(true);

        service.assetUpdated("asset-1", 5L);

        var captor = org.mockito.ArgumentCaptor.forClass(CodeAssetAuditLog.class);
        verify(repository).save(captor.capture());
        CodeAssetAuditLog saved = captor.getValue();
        assertEquals("ADMIN", saved.getActorType());
        assertEquals(7, saved.getActorUserId());
        assertEquals("UPDATE", saved.getAction());
    }

    @Test
    void rejectsForbiddenUnknownNestedByteAndOversizedMetadataWithoutEchoingValues() {
        List<Map<String, Object>> rejected = List.of(
                Map.of("fileContent", "do-not-echo-source"),
                Map.of("source_path", "do-not-echo-path"),
                Map.of("storagePath", "do-not-echo-storage"),
                Map.of("objectName", "do-not-echo-object"),
                Map.of("bucket", "do-not-echo-bucket"),
                Map.of("downloadURL", "do-not-echo-url"),
                Map.of("accessToken", "do-not-echo-token"),
                Map.of("client_secret", "do-not-echo-secret"),
                Map.of("unknownKey", "do-not-echo-unknown"),
                Map.of("reasonCode", Map.of("nested", "do-not-echo-nested")),
                Map.of("fileHash", new byte[]{1, 2, 3}),
                Map.of("policyVersion", "x".repeat(257))
        );

        for (Map<String, Object> metadata : rejected) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> CodeAssetAuditService.validateMetadata(metadata)
            );
            for (Object value : metadata.values()) {
                assertFalse(error.getMessage().contains(String.valueOf(value)));
            }
        }
        verify(repository, never()).save(any());
    }

    @Test
    void validatesHashesCountersAndReasonCodes() {
        assertThrows(IllegalArgumentException.class, () ->
                CodeAssetAuditService.validateMetadata(Map.of("fileHash", "A".repeat(64))));
        assertThrows(IllegalArgumentException.class, () ->
                CodeAssetAuditService.validateMetadata(Map.of("artifactSha256", "a".repeat(63))));
        assertThrows(IllegalArgumentException.class, () ->
                CodeAssetAuditService.validateMetadata(Map.of("revision", -1)));
        assertThrows(IllegalArgumentException.class, () ->
                CodeAssetAuditService.validateMetadata(Map.of("fileCount", -1L)));
        assertThrows(IllegalArgumentException.class, () ->
                CodeAssetAuditService.validateMetadata(Map.of("reasonCode", "bad-code")));

        Map<String, Object> safe = CodeAssetAuditService.validateMetadata(Map.of(
                "revision", 0L,
                "fileCount", 2,
                "artifactSha256", "a".repeat(64),
                "policyVersion", "code-policy-v1",
                "reasonCode", "VALIDATION_PASSED"
        ));
        assertEquals(5, safe.size());
        assertThrows(UnsupportedOperationException.class, () -> safe.put("revision", 1));
    }

    @Test
    void persistenceFailureIsFailClosed() {
        when(repository.save(any(CodeAssetAuditLog.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(
                IllegalStateException.class,
                () -> service.workspaceOpened("asset-1", "workspace-1", 0L)
        );
    }

    private static void assertMandatory(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = CodeAssetAuditService.class.getMethod(methodName, parameterTypes);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional, methodName);
        assertEquals(Propagation.MANDATORY, transactional.propagation(), methodName);
    }
}
