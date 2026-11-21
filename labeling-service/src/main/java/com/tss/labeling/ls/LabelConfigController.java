package com.tss.labeling.ls;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.ActorService;
import com.tss.labeling.identity.IdentityClient;
import com.tss.labeling.identity.MeController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/labeling/v1/label-configs")
public class LabelConfigController {
    private final LsSourceAdapter adapter;
    private final IdentityClient identity;
    private final ActorService actors;
    private final TaskRegistry registry;

    public LabelConfigController(LsSourceAdapter adapter, IdentityClient identity, ActorService actors,TaskRegistry registry) {
        this.adapter = adapter;
        this.identity = identity;
        this.actors = actors;
        this.registry=registry;
    }

    @PostMapping("/inspect")
    public ApiResponse<JsonNode> inspect(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody ConfigRequest body, HttpServletRequest request) {
        actors.resolve(identity.currentUser(auth));
        return ApiResponse.success(adapter.inspect(body.labelConfig()), MeController.requestId(request));
    }

    public record ConfigRequest(@NotBlank @Size(max = 262144) String labelConfig) {}

    @GetMapping("/task-types")
    public ApiResponse<?> types(@RequestHeader(value="Authorization", required=false) String auth,
                               HttpServletRequest request) {
        actors.resolve(identity.currentUser(auth));
        return ApiResponse.success(registry.list(),MeController.requestId(request));
    }
}
