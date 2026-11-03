package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.MinioDeleteTaskService;
import com.tss.platform.service.MinioService;
import io.minio.StatObjectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileObjectController {

    private static final Logger log = LoggerFactory.getLogger(FileObjectController.class);

    private final MinioService minioService;
    private final MinioDeleteTaskService minioDeleteTaskService;
    private final AuthContext authContext;

    public FileObjectController(
            MinioService minioService,
            MinioDeleteTaskService minioDeleteTaskService,
            AuthContext authContext
    ) {
        this.minioService = minioService;
        this.minioDeleteTaskService = minioDeleteTaskService;
        this.authContext = authContext;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        try {
            minioService.assertConnected();
            Map<String, Object> data = new HashMap<>();
            data.put("minio", "ok");
            return ApiResponse.ok(data);
        } catch (Exception e) {
            return ApiResponse.fail("MinIO 连接失败: " + e.getMessage());
        }
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "objectName", required = false) String objectName
    ) {
        try {
            String name = (objectName == null || objectName.isBlank()) ? file.getOriginalFilename() : objectName;
            if (name == null || name.isBlank()) {
                return ApiResponse.fail("objectName 不能为空");
            }
            name = normalizeUserObjectName(name);
            requireGenericFileMutationAllowed(name);
            minioService.uploadFile(name, file);
            StatObjectResponse stat = minioService.stat(name);
            Map<String, Object> data = new HashMap<>();
            data.put("objectName", name);
            data.put("size", stat.size());
            data.put("etag", stat.etag());
            return ApiResponse.ok(data);
        } catch (Exception e) {
            return ApiResponse.fail("上传失败: " + e.getMessage());
        }
    }

    @GetMapping("/download")
    public ResponseEntity<?> download(@RequestParam("objectName") String objectName) {
        try {
            String cleanName = requireObjectAccess(objectName);
            StatObjectResponse stat = minioService.stat(cleanName);
            InputStream is = minioService.downloadStream(cleanName);
            String filename = cleanName.contains("/") ? cleanName.substring(cleanName.lastIndexOf('/') + 1) : cleanName;
            String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(stat.size())
                    .body(new InputStreamResource(is));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResponse.fail("文件不存在或下载失败: " + e.getMessage()));
        }
    }

    @DeleteMapping("/delete")
    @Transactional
    public ApiResponse<Map<String, Object>> delete(@RequestParam("objectName") String objectName) {
        try {
            String cleanName = requireObjectAccess(objectName);
            requireGenericFileMutationAllowed(cleanName);
            minioDeleteTaskService.enqueueDefaultBucketDelete(
                    cleanName,
                    MinioDeleteTaskService.SOURCE_FILE_OBJECT,
                    cleanName,
                    authContext.currentUserId()
            );
            log.info("File object delete queued: objectName={}, ownerUserId={}", cleanName, authContext.currentUserId());
            Map<String, Object> data = new HashMap<>();
            data.put("objectName", cleanName);
            data.put("minioDeleteQueued", true);
            return ApiResponse.ok(data);
        } catch (Exception e) {
            return ApiResponse.fail("删除失败: " + e.getMessage());
        }
    }
    private String normalizeUserObjectName(String objectName) {
        String cleanName = cleanObjectName(objectName);
        if (authContext.isAdmin()) {
            return cleanName;
        }
        String prefix = currentUserPrefix();
        if (cleanName.startsWith(prefix)) {
            return cleanName;
        }
        return prefix + "files/" + cleanName;
    }

    private String requireObjectAccess(String objectName) {
        String cleanName = cleanObjectName(objectName);
        if (authContext.isAdmin()) {
            return cleanName;
        }
        if (!cleanName.startsWith(currentUserPrefix())) {
            throw new IllegalArgumentException("object not found or no permission");
        }
        return cleanName;
    }

    private static void requireGenericFileMutationAllowed(String objectName) {
        String[] components = objectName.split("/", -1);
        if (components.length >= 3
                && "users".equals(components[0])
                && !components[1].isEmpty()
                && "codes".equals(components[2])) {
            throw new IllegalArgumentException("受管代码制品不能通过通用文件接口修改");
        }
    }

    private String currentUserPrefix() {
        return "users/" + authContext.currentUserId() + "/";
    }

    private String cleanObjectName(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("objectName 不能为空");
        }
        for (int i = 0; i < objectName.length(); i += 1) {
            if (Character.isISOControl(objectName.charAt(i))) {
                throw new IllegalArgumentException("objectName 非法");
            }
        }
        String normalized = objectName.replace('\\', '/').replaceAll("^/+", "");
        for (String part : normalized.split("/")) {
            if (".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("objectName 非法");
            }
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("objectName 不能为空");
        }
        return normalized;
    }
}

