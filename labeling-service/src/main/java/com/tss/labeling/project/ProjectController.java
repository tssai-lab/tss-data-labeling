package com.tss.labeling.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.ActorService;
import com.tss.labeling.identity.IdentityClient;
import com.tss.labeling.identity.IdentityUser;
import com.tss.labeling.identity.MeController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/labeling/v1/projects")
public class ProjectController {

    private final IdentityClient identityClient;
    private final ActorService actorService;
    private final ProjectService projectService;

    public ProjectController(
            IdentityClient identityClient,
            ActorService actorService,
            ProjectService projectService
    ) {
        this.identityClient = identityClient;
        this.actorService = actorService;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectService.ProjectView> create(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateProjectRequest body,
            HttpServletRequest request
    ) {
        ActorService.ActorContext actor = currentActor(authorization);
        ProjectService.ProjectView project = projectService.create(actor, body, idempotencyKey);
        return ApiResponse.success(project, MeController.requestId(request));
    }

    @GetMapping
    public ApiResponse<ProjectService.ProjectPage> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(defaultValue = "owned") String scope,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest request
    ) {
        if (!"owned".equals(scope) || limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        ActorService.ActorContext actor = currentActor(authorization);
        return ApiResponse.success(
                projectService.list(actor, limit, cursor),
                MeController.requestId(request)
        );
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectService.ProjectView> get(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String projectId,
            HttpServletRequest request
    ) {
        ActorService.ActorContext actor = currentActor(authorization);
        return ApiResponse.success(
                projectService.get(actor, projectId),
                MeController.requestId(request)
        );
    }

    @PostMapping("/{projectId}/spec-versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectService.SpecVersionView> createSpecVersion(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateSpecVersionRequest body,
            HttpServletRequest request
    ) {
        ActorService.ActorContext actor = currentActor(authorization);
        return ApiResponse.success(
                projectService.createSpecVersion(actor, projectId, body, idempotencyKey),
                MeController.requestId(request)
        );
    }

    private ActorService.ActorContext currentActor(String authorization) {
        IdentityUser user = identityClient.currentUser(authorization);
        return actorService.resolve(user);
    }

    public record CreateProjectRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 4000) String description,
            @NotBlank @Size(max = 80) String taskType
    ) {
    }

    public record CreateSpecVersionRequest(
            @NotBlank @Size(max = 80) String taskType,
            JsonNode labels,
            @Size(max = 10000) String instructions,
            JsonNode editorConfig,
            @NotBlank @Size(max = 80) String schemaVersion
    ) {
    }
}
