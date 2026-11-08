package com.tss.labeling.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1")
public class ResultController {
    private final ResultService results;
    private final IdentityClient identity;
    private final ActorService actors;
    public ResultController(ResultService results,IdentityClient identity,ActorService actors){this.results=results;this.identity=identity;this.actors=actors;}
    private ActorService.ActorContext actor(String auth){return actors.resolve(identity.currentUser(auth));}
    private ApiResponse<JsonNode> response(JsonNode value,HttpServletRequest req){return ApiResponse.success(value,MeController.requestId(req));}
    @PutMapping("/assignments/{assignmentId}/drafts/{sampleId}")
    public ApiResponse<JsonNode> save(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID assignmentId,@PathVariable UUID sampleId,@Valid @RequestBody DraftInput body,HttpServletRequest req){
        return response(results.save(actor(auth),assignmentId,sampleId,body),req);
    }
    @GetMapping("/assignments/{assignmentId}/drafts/{sampleId}")
    public ApiResponse<JsonNode> draft(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID assignmentId,@PathVariable UUID sampleId,HttpServletRequest req){return response(results.getDraft(actor(auth),assignmentId,sampleId),req);}
    @PostMapping("/assignments/{assignmentId}/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JsonNode> submit(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID assignmentId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SubmitInput body,HttpServletRequest req){return response(results.submit(actor(auth),assignmentId,body,key),req);}
    @GetMapping("/submissions/{submissionId}")
    public ApiResponse<JsonNode> submission(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID submissionId,HttpServletRequest req){return response(results.submission(actor(auth),submissionId),req);}
    @GetMapping("/projects/{projectId}/submissions")
    public ApiResponse<JsonNode> list(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID projectId,@RequestParam(defaultValue="20") int limit,
            @RequestParam(required=false) String cursor,HttpServletRequest req){return response(results.list(actor(auth),projectId,limit,cursor),req);}
    public record DraftInput(@NotNull @Min(0) Long baseRevision,@NotNull Boolean explicitDone,
                             @NotBlank @Size(max=80) String resultFormat,@NotNull JsonNode result){}
    public record RevisionItem(@NotNull UUID sampleId,@NotNull @Min(1) Long revision){}
    public record SubmitInput(@NotEmpty @Size(max=10000) List<@NotNull @Valid RevisionItem> items){}
}
