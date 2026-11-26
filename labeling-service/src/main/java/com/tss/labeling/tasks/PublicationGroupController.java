package com.tss.labeling.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.ActorService;
import com.tss.labeling.identity.IdentityClient;
import com.tss.labeling.identity.MeController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1/projects/{projectId}/publication-groups")
public class PublicationGroupController {
    private final PublicationGroupService groups;
    private final IdentityClient identity;
    private final ActorService actors;
    public PublicationGroupController(PublicationGroupService groups, IdentityClient identity, ActorService actors) {
        this.groups = groups; this.identity = identity; this.actors = actors;
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JsonNode> publish(@RequestHeader(value="Authorization", required=false) String auth,
            @PathVariable UUID projectId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody PublicationGroupService.Input input, HttpServletRequest request) {
        return ApiResponse.success(groups.publish(actors.resolve(identity.currentUser(auth)), projectId, input, key), MeController.requestId(request));
    }
    @GetMapping("/{groupId}")
    public ApiResponse<JsonNode> get(@RequestHeader(value="Authorization", required=false) String auth,
            @PathVariable UUID projectId, @PathVariable UUID groupId, HttpServletRequest request) {
        return ApiResponse.success(groups.get(actors.resolve(identity.currentUser(auth)), projectId, groupId), MeController.requestId(request));
    }
    @GetMapping
    public ApiResponse<JsonNode> list(@RequestHeader(value="Authorization", required=false) String auth,
            @PathVariable UUID projectId, HttpServletRequest request) {
        return ApiResponse.success(groups.list(actors.resolve(identity.currentUser(auth)), projectId), MeController.requestId(request));
    }
}
