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
@RequestMapping("/api/labeling/v1/projects/{projectId}")
public class ProjectEditorController {
    private final ProjectEditorService editor;private final ActorService actors;private final IdentityClient identity;
    public ProjectEditorController(ProjectEditorService editor,ActorService actors,IdentityClient identity){this.editor=editor;this.actors=actors;this.identity=identity;}
    private ActorService.ActorContext actor(String auth){return actors.resolve(identity.currentUser(auth));}
    @PostMapping("/editor-sessions")
    public ApiResponse<ProjectEditorService.Session> open(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestHeader("Idempotency-Key") String key,@PathVariable UUID projectId,@Valid @RequestBody Sample body,HttpServletRequest request){
        return ApiResponse.success(editor.open(actor(auth),projectId,body.sampleId(),key),MeController.requestId(request));}
    @GetMapping("/samples/{sampleId}/annotation")
    public ApiResponse<ProjectEditorService.Result> get(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID projectId,@PathVariable UUID sampleId,HttpServletRequest request){
        return ApiResponse.success(editor.get(actor(auth),projectId,sampleId),MeController.requestId(request));}
    @PutMapping("/samples/{sampleId}/annotation")
    public ApiResponse<ProjectEditorService.Result> save(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID projectId,@PathVariable UUID sampleId,@Valid @RequestBody Save body,HttpServletRequest request){
        return ApiResponse.success(editor.save(actor(auth),projectId,sampleId,body.editorSessionId(),body.baseRevision(),body.result()),MeController.requestId(request));}
    public record Sample(@NotNull UUID sampleId){}
    public record Save(@NotNull UUID editorSessionId,@NotNull @Min(0) Long baseRevision,@NotNull JsonNode result){}
}
