package com.tss.labeling.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1/projects")
public class ProjectCompletionController {
    private final ProjectCompletionService service;private final IdentityClient identity;private final ActorService actors;
    public ProjectCompletionController(ProjectCompletionService service,IdentityClient identity,ActorService actors){this.service=service;this.identity=identity;this.actors=actors;}
    @PostMapping("/{project}/complete")
    public ApiResponse<JsonNode> complete(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID project,
            @Valid @RequestBody CompleteRequest body,HttpServletRequest req){
        return ApiResponse.success(service.complete(actors.resolve(identity.currentUser(auth)),project,body.finalSnapshotId(),body.baseRevision()),MeController.requestId(req));
    }
    public record CompleteRequest(@NotNull UUID finalSnapshotId,@Min(1) long baseRevision){}
}
