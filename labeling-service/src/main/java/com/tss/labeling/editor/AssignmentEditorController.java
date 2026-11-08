package com.tss.labeling.editor;

import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1/assignments/{assignmentId}")
public class AssignmentEditorController {
    private final AssignmentEditorService editor;
    private final IdentityClient identity;
    private final ActorService actors;
    public AssignmentEditorController(AssignmentEditorService editor,IdentityClient identity,ActorService actors) {
        this.editor=editor;this.identity=identity;this.actors=actors;
    }
    @PostMapping("/editor-sessions")
    public ApiResponse<AssignmentEditorService.Session> open(
            @RequestHeader(value="Authorization",required=false) String auth,
            @RequestHeader("Idempotency-Key") String key,@PathVariable UUID assignmentId,
            @Valid @RequestBody Sample input,HttpServletRequest request) {
        var actor=actors.resolve(identity.currentUser(auth));
        return ApiResponse.success(editor.open(actor,assignmentId,input.sampleId(),key),MeController.requestId(request));
    }
    @PostMapping("/editor-sync")
    public ApiResponse<com.fasterxml.jackson.databind.JsonNode> sync(
            @RequestHeader(value="Authorization",required=false) String auth,
            @RequestHeader("Idempotency-Key") String key,
            @PathVariable UUID assignmentId,@Valid @RequestBody Sample input,HttpServletRequest request) {
        var actor=actors.resolve(identity.currentUser(auth));
        return ApiResponse.success(editor.sync(actor,assignmentId,input.sampleId(),key),MeController.requestId(request));
    }
    public record Sample(@NotNull UUID sampleId) {}
}
