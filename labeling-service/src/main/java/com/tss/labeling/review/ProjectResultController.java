package com.tss.labeling.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.ActorService;
import com.tss.labeling.identity.IdentityClient;
import com.tss.labeling.identity.MeController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1")
public class ProjectResultController {
    private final ProjectResultQuery results;
    private final ResultSelectionService selections;
    private final IdentityClient identity;
    private final ActorService actors;

    public ProjectResultController(ProjectResultQuery results, ResultSelectionService selections,
            IdentityClient identity, ActorService actors) {
        this.results = results;
        this.selections = selections;
        this.identity = identity;
        this.actors = actors;
    }

    @GetMapping("/projects/{projectId}/results")
    public ApiResponse<JsonNode> results(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId, @RequestParam Map<String, String> params, HttpServletRequest request) {
        return respond(results.list(actor(auth), projectId, params), request);
    }

    @GetMapping("/projects/{projectId}/result-statistics")
    public ApiResponse<JsonNode> statistics(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId, @RequestParam Map<String, String> params, HttpServletRequest request) {
        return respond(results.statistics(actor(auth), projectId, params), request);
    }

    @GetMapping("/projects/{projectId}/samples/{sampleId}/results")
    public ApiResponse<JsonNode> history(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId, @PathVariable UUID sampleId,
            @RequestParam Map<String, String> params, HttpServletRequest request) {
        return respond(results.history(actor(auth), projectId, sampleId, params), request);
    }

    @PostMapping("/projects/{projectId}/result-selections")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JsonNode> createSelection(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ResultSelectionService.CreateRequest body, HttpServletRequest request) {
        return respond(selections.create(actor(auth), projectId, body, key), request);
    }

    @GetMapping("/result-selections/{selectionId}/items")
    public ApiResponse<JsonNode> selectionItems(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID selectionId, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor, HttpServletRequest request) {
        return respond(selections.items(actor(auth), selectionId, limit, cursor), request);
    }

    @GetMapping("/result-selections/{selectionId}/statistics")
    public ApiResponse<JsonNode> selectionStatistics(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID selectionId, HttpServletRequest request) {
        return respond(selections.statistics(actor(auth), selectionId), request);
    }

    private ActorService.ActorContext actor(String auth) {
        return actors.resolve(identity.currentUser(auth));
    }

    private ApiResponse<JsonNode> respond(JsonNode body, HttpServletRequest request) {
        return ApiResponse.success(body, MeController.requestId(request));
    }
}
