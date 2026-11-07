package com.tss.labeling.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1/assignments/{assignmentId}")
public class AssignmentEditorController {
    private final AssignmentEditorService editor;private final ActorService actors;private final IdentityClient identity;
    public AssignmentEditorController(AssignmentEditorService editor,ActorService actors,IdentityClient identity){this.editor=editor;this.actors=actors;this.identity=identity;}
    private ActorService.ActorContext actor(String auth){return actors.resolve(identity.currentUser(auth));}
    @PostMapping("/editor-sessions")
    public ApiResponse<AssignmentEditorService.EditorSession> open(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestHeader("Idempotency-Key") String key,@PathVariable UUID assignmentId,@Valid @RequestBody Sample body,HttpServletRequest request){
        return ApiResponse.success(editor.open(actor(auth),assignmentId,body.sampleId(),key),MeController.requestId(request));}
    @GetMapping("/drafts/{sampleId}")
    public ApiResponse<AssignmentEditorService.Draft> get(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID assignmentId,@PathVariable UUID sampleId,HttpServletRequest request){
        return ApiResponse.success(editor.get(actor(auth),assignmentId,sampleId),MeController.requestId(request));}
    @PutMapping("/drafts/{sampleId}")
    public ApiResponse<AssignmentEditorService.Draft> save(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID assignmentId,@PathVariable UUID sampleId,@Valid @RequestBody Save body,HttpServletRequest request){
        return ApiResponse.success(editor.save(actor(auth),assignmentId,sampleId,body.editorSessionId(),body.baseRevision(),body.explicitDone(),body.resultFormat(),body.result()),MeController.requestId(request));}
    @PostMapping("/editor-sync")
    public ApiResponse<AssignmentEditorService.Draft> sync(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestHeader("Idempotency-Key") String key,@PathVariable UUID assignmentId,@Valid @RequestBody Sample body,HttpServletRequest request){
        return ApiResponse.success(editor.sync(actor(auth),assignmentId,body.sampleId(),key),MeController.requestId(request));}
    public record Sample(@NotNull UUID sampleId) {}
    public record Save(@NotNull UUID editorSessionId,@NotNull @Min(0) Long baseRevision,
                       @NotNull Boolean explicitDone,@NotBlank String resultFormat,@NotNull JsonNode result) {}
}
