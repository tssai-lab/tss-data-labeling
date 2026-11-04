package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.InferenceInputUploadDto;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.MinioService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Uploads one image as an owned MinIO object for a single-file inference task. */
@RestController
@RequestMapping("/api/inference/inputs")
public class InferenceInputController {

    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".bmp", ".webp"
    );

    private final AuthContext authContext;
    private final MinioService minioService;

    public InferenceInputController(AuthContext authContext, MinioService minioService) {
        this.authContext = authContext;
        this.minioService = minioService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<InferenceInputUploadDto> upload(@RequestParam("file") MultipartFile file) {
        try {
            validate(file);
            String fileName = safeFileName(file.getOriginalFilename());
            String objectName = authContext.userPrefix(authContext.currentUserId())
                    + "inference-inputs/" + UUID.randomUUID() + "-" + fileName;
            minioService.uploadFile(objectName, file);
            return ApiResponse.ok(InferenceInputUploadDto.builder()
                    .objectName(objectName)
                    .fileName(fileName)
                    .sizeBytes(file.getSize())
                    .build());
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        } catch (Exception exception) {
            return ApiResponse.fail("single-file inference upload failed");
        }
    }

    private static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("inference input file cannot be empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("inference input file exceeds 100 MiB");
        }
        String fileName = safeFileName(file.getOriginalFilename());
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (IMAGE_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
            throw new IllegalArgumentException("single-file inference supports jpg, jpeg, png, bmp and webp only");
        }
    }

    private static String safeFileName(String originalFileName) {
        String fileName = originalFileName == null ? "input" : originalFileName
                .replace('\\', '_')
                .replace('/', '_')
                .trim();
        if (fileName.isBlank() || fileName.length() > 200) {
            throw new IllegalArgumentException("inference input file name is invalid");
        }
        return fileName;
    }
}
