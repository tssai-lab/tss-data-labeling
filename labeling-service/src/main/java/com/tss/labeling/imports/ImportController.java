package com.tss.labeling.imports;

import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.ActorService;
import com.tss.labeling.identity.IdentityClient;
import com.tss.labeling.identity.MeController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/api/labeling/v1")
public class ImportController {
    private final ImportService imports;
    private final IdentityClient identity;
    private final ActorService actors;

    public ImportController(ImportService imports, IdentityClient identity, ActorService actors) {
        this.imports = imports;
        this.identity = identity;
        this.actors = actors;
    }

    private ActorService.ActorContext actor(String authorization) {
        return actors.resolve(identity.currentUser(authorization));
    }

    @PostMapping("/projects/{projectId}/imports")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ImportService.BatchView> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String projectId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateImport body, HttpServletRequest request) {
        return ApiResponse.success(imports.create(actor(authorization), projectId, body, key), MeController.requestId(request));
    }

    @GetMapping("/imports/{batchId}")
    public ApiResponse<ImportService.BatchView> get(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String batchId, HttpServletRequest request) {
        return ApiResponse.success(imports.get(actor(authorization), batchId), MeController.requestId(request));
    }

    public record CreateImport(@NotBlank @Size(max = 255) String filename,
                               @NotNull @Min(1) Long sizeBytes,
                               @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String sha256) { }

    @PutMapping(value = "/imports/{batchId}/chunks", consumes = "application/octet-stream")
    public ApiResponse<Map<String, Long>> chunk(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String batchId, @RequestParam long offset,
            @RequestHeader("X-Chunk-SHA256") String checksum, HttpServletRequest request) throws IOException {
        var current = actor(authorization);
        return ApiResponse.success(Map.of("receivedBytes", imports.chunk(current, batchId, offset, checksum, request.getInputStream())), MeController.requestId(request));
    }

    @PostMapping("/imports/{batchId}/complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ImportService.BatchView> complete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String batchId, @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) throws IOException {
        var current = actor(authorization);
        requireEmptyBody(request);
        return ApiResponse.success(imports.finish(current, batchId, key, false), MeController.requestId(request));
    }

    @PostMapping("/imports/{batchId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ImportService.BatchView> retry(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String batchId, @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) throws IOException {
        var current = actor(authorization);
        requireEmptyBody(request);
        return ApiResponse.success(imports.finish(current, batchId, key, true), MeController.requestId(request));
    }

    private static void requireEmptyBody(HttpServletRequest request) throws IOException {
        if (request.getInputStream().read() != -1) throw ImportService.failure(400, "INVALID_REQUEST", "此接口不接受请求体");
    }

    @GetMapping("/imports/{batchId}/samples")
    public ApiResponse<ImportService.SamplePage> samples(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String batchId, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor, HttpServletRequest request) {
        return ApiResponse.success(imports.samples(actor(authorization), batchId, limit, cursor), MeController.requestId(request));
    }

    @GetMapping("/projects/{projectId}/samples/{sampleId}/file")
    public ResponseEntity<Resource> file(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String projectId, @PathVariable String sampleId) {
        var file = imports.file(actor(authorization), projectId, sampleId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).contentLength(file.sizeBytes())
                .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff").body(file.resource());
    }
}
