package com.tss.labeling.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.ActorService;
import com.tss.labeling.identity.IdentityClient;
import com.tss.labeling.identity.MeController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1")
public class AiController {

    private final AiService ai;
    private final IdentityClient identity;
    private final ActorService actors;

    public AiController(AiService ai, IdentityClient identity, ActorService actors) {
        this.ai = ai;
        this.identity = identity;
        this.actors = actors;
    }

    private ActorService.ActorContext actor(String auth) {
        return actors.resolve(identity.currentUser(auth));
    }

    private ApiResponse<JsonNode> response(JsonNode value, HttpServletRequest req) {
        return ApiResponse.success(value, MeController.requestId(req));
    }

    @GetMapping("/agents")
    public ApiResponse<JsonNode> agents(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "") String taskType, HttpServletRequest req) {
        return response(ai.agents(actor(auth), taskType), req);
    }

    @PostMapping("/admin/agents")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JsonNode> registerAgent(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody RegisterRequest body, HttpServletRequest req) {
        return response(ai.registerAgent(actor(auth),
                new AiService.RegisterInput(body.taskType(), body.displayName(), body.modelVersion(), body.capabilities(),
                        body.supportedLabels(), body.supportsReference(), body.confidenceDescription(), body.workerKey()), key), req);
    }

    @PostMapping("/projects/{projectId}/ai-executions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<JsonNode> create(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateRequest body, HttpServletRequest req) {
        return response(ai.createExecution(actor(auth), projectId,
                new AiService.CreateInput(body.specVersionId(), body.agentId(), body.samples(), body.instructions()), key), req);
    }

    @GetMapping("/ai-executions/{executionId}")
    public ApiResponse<JsonNode> execution(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID executionId, HttpServletRequest req) {
        return response(ai.execution(actor(auth), executionId), req);
    }

    @GetMapping("/ai-executions/{executionId}/results")
    public ApiResponse<JsonNode> results(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID executionId, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor, HttpServletRequest req) {
        return response(ai.results(actor(auth), executionId, limit, cursor), req);
    }

    @PostMapping("/ai-executions/{executionId}/cancel")
    public ApiResponse<JsonNode> cancel(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID executionId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CancelRequest body, HttpServletRequest req) {
        return response(ai.cancel(actor(auth), executionId, body.baseRevision(), body.reason(), key), req);
    }

    @PostMapping("/rounds/{roundId}/confidence-policies")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JsonNode> confidencePolicy(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID roundId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ConfidenceRequest body, HttpServletRequest req) {
        return response(ai.confidencePolicy(actor(auth), roundId,
                new AiService.ConfidenceInput(body.snapshotId(), body.lowerThreshold(), body.upperThreshold()), key), req);
    }

    @GetMapping("/rounds/{roundId}/confidence-policies")
    public ApiResponse<JsonNode> confidencePolicies(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID roundId, HttpServletRequest req) {
        return response(ai.confidencePolicies(actor(auth), roundId), req);
    }

    public record CreateRequest(@NotNull UUID specVersionId, @NotNull UUID agentId,
            @NotNull @Size(max = 10000) List<AiService.SelectionSample> samples, @Size(max = 500) String instructions) {}

    public record CancelRequest(@NotNull @Min(1) Long baseRevision, @Size(max = 1000) String reason) {}

    public record RegisterRequest(@NotNull String taskType, @NotNull String displayName, @NotNull String modelVersion,
            JsonNode capabilities, JsonNode supportedLabels, boolean supportsReference,
            String confidenceDescription, @NotNull String workerKey) {}

    public record ConfidenceRequest(@NotNull UUID snapshotId, @NotNull Double lowerThreshold, @NotNull Double upperThreshold) {}
}
