package com.tss.labeling.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.imports.ImportService.SampleFile;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 内部工作端接口：D 的模型适配器通过受限凭据调用，不经过公开前端代理。 */
@RestController
@RequestMapping("/internal/labeling/v1")
public class AiWorkerController {

    private final AiService ai;

    public AiWorkerController(AiService ai) {
        this.ai = ai;
    }

    @PostMapping("/executions/claim")
    public ResponseEntity<JsonNode> claim(@RequestHeader(value = "X-Worker-Key", required = false) String workerKey) {
        JsonNode result = ai.claim(workerKey);
        if (result == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/executions/{executionId}/heartbeat")
    public JsonNode heartbeat(@RequestHeader(value = "X-Worker-Key", required = false) String workerKey,
            @PathVariable UUID executionId, @RequestBody HeartbeatRequest body) {
        return ai.heartbeat(workerKey, executionId, body.generation());
    }

    @GetMapping("/executions/{executionId}/items")
    public JsonNode items(@RequestHeader(value = "X-Worker-Key", required = false) String workerKey,
            @PathVariable UUID executionId, @RequestParam long generation,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        return ai.items(workerKey, executionId, generation, limit, cursor);
    }

    @GetMapping("/executions/{executionId}/samples/{sampleId}/file")
    public ResponseEntity<Resource> file(@RequestHeader(value = "X-Worker-Key", required = false) String workerKey,
            @PathVariable UUID executionId, @PathVariable UUID sampleId, @RequestParam long generation) {
        SampleFile f = ai.file(workerKey, executionId, sampleId, generation);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.mediaType())).contentLength(f.sizeBytes())
                .header("Cache-Control", "private, no-store").body(f.resource());
    }

    @PutMapping("/executions/{executionId}/outputs/{sampleId}")
    public JsonNode output(@RequestHeader(value = "X-Worker-Key", required = false) String workerKey,
            @PathVariable UUID executionId, @PathVariable UUID sampleId, @RequestBody OutputRequest body) {
        return ai.output(workerKey, executionId, sampleId, body.generation(),
                new AiService.OutputInput(body.status(), body.result(), body.confidence(), body.errorCode(), body.errorMessage()));
    }

    @PostMapping("/executions/{executionId}/finish")
    public JsonNode finish(@RequestHeader(value = "X-Worker-Key", required = false) String workerKey,
            @PathVariable UUID executionId, @RequestHeader("Idempotency-Key") String key, @RequestBody FinishRequest body) {
        return ai.finish(workerKey, executionId, body.generation(), key);
    }

    @PostMapping("/executions/{executionId}/fail")
    public JsonNode fail(@RequestHeader(value = "X-Worker-Key", required = false) String workerKey,
            @PathVariable UUID executionId, @RequestHeader("Idempotency-Key") String key, @RequestBody FailRequest body) {
        return ai.fail(workerKey, executionId, body.generation(), body.errorCode(), body.errorMessage(), key);
    }

    public record HeartbeatRequest(long generation) {}
    public record FinishRequest(long generation) {}
    public record FailRequest(long generation, String errorCode, String errorMessage) {}
    public record OutputRequest(long generation, String status, JsonNode result, Double confidence, String errorCode, String errorMessage) {}
}
