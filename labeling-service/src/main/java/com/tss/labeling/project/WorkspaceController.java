package com.tss.labeling.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.*;
import com.tss.labeling.review.AtomicReviewService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1")
public class WorkspaceController {
    private final WorkspaceService workspace;
    private final AtomicReviewService reviews;
    private final IdentityClient identity;
    private final ActorService actors;

    public WorkspaceController(
            WorkspaceService workspace,
            AtomicReviewService reviews,
            IdentityClient identity,
            ActorService actors) {
        this.workspace = workspace;
        this.reviews = reviews;
        this.identity = identity;
        this.actors = actors;
    }

    private ActorService.ActorContext actor(String auth) {
        return actors.resolve(identity.currentUser(auth));
    }

    private ApiResponse<JsonNode> out(JsonNode data, HttpServletRequest req) {
        return ApiResponse.success(data, MeController.requestId(req));
    }

    @PostMapping("/projects/{projectId}/{action:pause|resume|archive}")
    public ApiResponse<JsonNode> state(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId,
            @PathVariable String action,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody WorkspaceService.StateInput body,
            HttpServletRequest req) {
        return out(workspace.transition(actor(auth), projectId, action, body, key), req);
    }

    @DeleteMapping("/projects/{projectId}")
    public ApiResponse<JsonNode> delete(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody WorkspaceService.StateInput body,
            HttpServletRequest req) {
        return out(workspace.transition(actor(auth), projectId, "delete", body, key), req);
    }

    @GetMapping("/projects/{projectId}/{kind:imports|rounds|ai-executions|package-plans}")
    public ApiResponse<JsonNode> list(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId,
            @PathVariable String kind,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest req) {
        return out(workspace.list(actor(auth), projectId, kind, limit, cursor), req);
    }

    @GetMapping("/me/submissions")
    public ApiResponse<JsonNode> submissions(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest req) {
        return out(workspace.submissions(actor(auth), limit, cursor), req);
    }

    @GetMapping("/me/operation-receipts")
    public ApiResponse<JsonNode> receipts(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest req) {
        return out(workspace.receipts(actor(auth), limit, cursor), req);
    }

    @GetMapping("/admin/operation-receipts")
    public ApiResponse<JsonNode> allReceipts(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest req) {
        return out(workspace.adminReceipts(actor(auth), limit, cursor), req);
    }

    @PostMapping("/projects/{projectId}/reviews")
    public ApiResponse<JsonNode> reviews(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody AtomicReviewService.ReviewInput body,
            HttpServletRequest req) {
        return out(reviews.review(actor(auth), projectId, body, key), req);
    }

    @PostMapping("/projects/{projectId}/results/{resultId}/revisions")
    public ApiResponse<JsonNode> revise(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId,
            @PathVariable UUID resultId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody AtomicReviewService.RevisionInput body,
            HttpServletRequest req) {
        return out(reviews.revise(actor(auth), projectId, resultId, body, key), req);
    }
}
