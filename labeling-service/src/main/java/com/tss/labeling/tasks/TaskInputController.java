package com.tss.labeling.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1")
public class TaskInputController {
    private final TaskInputService inputs;
    private final IdentityClient identity;
    private final ActorService actors;

    public TaskInputController(
            TaskInputService inputs, IdentityClient identity, ActorService actors) {
        this.inputs = inputs;
        this.identity = identity;
        this.actors = actors;
    }

    private ActorService.ActorContext actor(String auth) {
        return actors.resolve(identity.currentUser(auth));
    }

    private ApiResponse<JsonNode> out(JsonNode value, HttpServletRequest req) {
        return ApiResponse.success(value, MeController.requestId(req));
    }

    @GetMapping("/task-templates")
    public ApiResponse<JsonNode> templates(
            @RequestHeader(value = "Authorization", required = false) String auth,
            HttpServletRequest req) {
        return out(inputs.templates(actor(auth)), req);
    }

    @GetMapping("/projects/{projectId}/samples/{sampleId}/data")
    public ApiResponse<JsonNode> owner(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId,
            @PathVariable UUID sampleId,
            HttpServletRequest req) {
        return out(inputs.owner(actor(auth), projectId, sampleId), req);
    }

    @GetMapping("/assignments/{assignmentId}/samples/{sampleId}/data")
    public ApiResponse<JsonNode> assignment(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID assignmentId,
            @PathVariable UUID sampleId,
            HttpServletRequest req) {
        return out(inputs.assignment(actor(auth), assignmentId, sampleId), req);
    }
}
