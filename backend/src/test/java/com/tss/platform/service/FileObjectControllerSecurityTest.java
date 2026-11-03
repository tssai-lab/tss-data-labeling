package com.tss.platform.service;

import com.tss.platform.controller.FileObjectController;
import com.tss.platform.dto.ApiResponse;
import com.tss.platform.security.AuthContext;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileObjectControllerSecurityTest {

    private final MinioService minioService = mock(MinioService.class);
    private final MinioDeleteTaskService deleteTaskService =
            mock(MinioDeleteTaskService.class);
    private final AuthContext authContext = mock(AuthContext.class);
    private final StatObjectResponse stat = mock(StatObjectResponse.class);

    private FileObjectController controller;
    private MockMultipartFile file;

    @BeforeEach
    void setUp() throws Exception {
        controller = new FileObjectController(minioService, deleteTaskService, authContext);
        file = new MockMultipartFile(
                "file", "artifact.zip", "application/zip", new byte[]{1, 2, 3}
        );
        when(authContext.currentUserId()).thenReturn(7);
        when(authContext.isAdmin()).thenReturn(false);
        when(minioService.stat(anyString())).thenReturn(stat);
        when(stat.size()).thenReturn(3L);
        when(stat.etag()).thenReturn("etag-1");
    }

    @Test
    void ordinaryRelativeUploadStillUsesCurrentUsersFilesNamespace() throws Exception {
        ApiResponse<Map<String, Object>> response = controller.upload(file, "notes/a.zip");

        assertTrue(response.isSuccess());
        assertEquals("users/7/files/notes/a.zip", response.getData().get("objectName"));
        verify(minioService).uploadFile("users/7/files/notes/a.zip", file);
        verify(minioService).stat("users/7/files/notes/a.zip");
    }

    @Test
    void ordinaryFilesDeleteStillQueuesExactObject() {
        ApiResponse<Map<String, Object>> response = controller.delete(
                "users/7/files/notes/a.zip"
        );

        assertTrue(response.isSuccess());
        verify(deleteTaskService).enqueueDefaultBucketDelete(
                "users/7/files/notes/a.zip",
                MinioDeleteTaskService.SOURCE_FILE_OBJECT,
                "users/7/files/notes/a.zip",
                7
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "users/7/codes",
            "users/7/codes/asset-1/versions/version-1/artifact.zip"
    })
    void ownerCannotUploadToManagedCodeNamespace(String objectName) throws Exception {
        ApiResponse<Map<String, Object>> response = controller.upload(file, objectName);

        assertFalse(response.isSuccess());
        verify(minioService, never()).uploadFile(anyString(), org.mockito.ArgumentMatchers.any());
        verify(minioService, never()).stat(anyString());
    }

    @Test
    void backslashesCannotBypassManagedCodeNamespaceProtection() throws Exception {
        ApiResponse<Map<String, Object>> response = controller.upload(
                file,
                "users\\7\\codes\\asset-1\\versions\\version-1\\artifact.zip"
        );

        assertFalse(response.isSuccess());
        verify(minioService, never()).uploadFile(anyString(), org.mockito.ArgumentMatchers.any());
        verify(minioService, never()).stat(anyString());
    }

    @Test
    void ownerCannotDeleteManagedCodeObject() {
        ApiResponse<Map<String, Object>> response = controller.delete(
                "users/7/codes/asset-1/versions/version-1/artifact.zip"
        );

        assertFalse(response.isSuccess());
        verify(deleteTaskService, never()).enqueueDefaultBucketDelete(
                anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void administratorCannotUploadOrDeleteManagedCodeObject() throws Exception {
        when(authContext.isAdmin()).thenReturn(true);
        String objectName = "users/99/codes/asset-1/versions/version-1/artifact.zip";

        ApiResponse<Map<String, Object>> upload = controller.upload(file, objectName);
        ApiResponse<Map<String, Object>> delete = controller.delete(objectName);

        assertFalse(upload.isSuccess());
        assertFalse(delete.isSuccess());
        verify(minioService, never()).uploadFile(anyString(), org.mockito.ArgumentMatchers.any());
        verify(minioService, never()).stat(anyString());
        verify(deleteTaskService, never()).enqueueDefaultBucketDelete(
                anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any()
        );
    }
}
