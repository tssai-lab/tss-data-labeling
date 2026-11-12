package com.tss.labeling.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.ActorService;
import com.tss.labeling.identity.IdentityClient;
import com.tss.labeling.identity.MeController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1")
public class OutcomeController {

    private final OutcomeService outcomes;
    private final IdentityClient identity;
    private final ActorService actors;

    public OutcomeController(OutcomeService outcomes, IdentityClient identity, ActorService actors) {
        this.outcomes = outcomes;
        this.identity = identity;
        this.actors = actors;
    }

    private ActorService.ActorContext actor(String auth) {
        return actors.resolve(identity.currentUser(auth));
    }

    private ApiResponse<JsonNode> response(JsonNode value, HttpServletRequest req) {
        return ApiResponse.success(value, MeController.requestId(req));
    }

    @PostMapping("/projects/{projectId}/sample-reviews")
    public ApiResponse<JsonNode> reviewSamples(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ReviewSamplesRequest body, HttpServletRequest req) {
        return response(outcomes.reviewSamples(actor(auth), projectId, body.items(), key), req);
    }

    @GetMapping("/projects/{projectId}/sample-reviews")
    public ApiResponse<JsonNode> sampleReviews(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor, HttpServletRequest req) {
        return response(outcomes.sampleReviews(actor(auth), projectId, limit, cursor), req);
    }

    @PostMapping("/projects/{projectId}/final-snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JsonNode> createFinalSnapshot(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody FinalSnapshotRequest body, HttpServletRequest req) {
        return response(outcomes.createFinalSnapshot(actor(auth), projectId,
                new OutcomeService.FinalInput(body.name(), body.specVersionId(), body.sampleIds()), key), req);
    }

    @GetMapping("/final-snapshots/{finalSnapshotId}")
    public ApiResponse<JsonNode> finalSnapshot(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID finalSnapshotId, HttpServletRequest req) {
        return response(outcomes.finalSnapshot(actor(auth), finalSnapshotId), req);
    }

    @GetMapping("/projects/{projectId}/final-snapshots")
    public ApiResponse<JsonNode> finalSnapshots(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor, HttpServletRequest req) {
        return response(outcomes.finalSnapshots(actor(auth), projectId, limit, cursor), req);
    }

    public record ReviewSamplesRequest(@NotEmpty @Size(max = 10000) List<OutcomeService.SampleReviewInput> items) {}

    public record FinalSnapshotRequest(@NotNull @Size(max = 120) String name, @NotNull UUID specVersionId,
            @NotEmpty List<UUID> sampleIds) {}
}
