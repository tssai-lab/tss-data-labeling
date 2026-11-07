package com.tss.labeling.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.ActorService;
import com.tss.labeling.identity.IdentityClient;
import com.tss.labeling.identity.MeController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1")
public class TaskController {
    private final TaskService tasks;
    private final IdentityClient identity;
    private final ActorService actors;
    public TaskController(TaskService tasks,IdentityClient identity,ActorService actors){this.tasks=tasks;this.identity=identity;this.actors=actors;}
    private ActorService.ActorContext actor(String auth){return actors.resolve(identity.currentUser(auth));}
    private ApiResponse<JsonNode> response(JsonNode value,HttpServletRequest request){return ApiResponse.success(value,MeController.requestId(request));}

    @PostMapping("/projects/{projectId}/package-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JsonNode> create(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID projectId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PlanRequest body,HttpServletRequest request){
        return response(tasks.createPlan(actor(auth),projectId,new TaskService.PlanInput(body.batchId(),body.specVersionId(),body.packageSize(),body.annotatorsPerSample()),key),request);
    }
    @PostMapping("/package-plans/{planId}/publish")
    public ApiResponse<JsonNode> publish(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID planId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PublishRequest body,HttpServletRequest request){
        return response(tasks.publish(actor(auth),planId,body.selectionHash(),key),request);
    }
    @GetMapping("/package-plans/{planId}")
    public ApiResponse<JsonNode> plan(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID planId,HttpServletRequest request){return response(tasks.getPlan(actor(auth),planId),request);}
    @GetMapping("/task-pool")
    public ApiResponse<JsonNode> pool(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="") String taskType,HttpServletRequest request){return response(tasks.pool(actor(auth),limit,cursor,taskType),request);}
    @PostMapping("/packages/{packageId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JsonNode> claim(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID packageId,@RequestHeader("Idempotency-Key") String key,
            @RequestBody(required=false) JsonNode body,HttpServletRequest request){
        var current=actor(auth);
        if(body!=null&&(!body.isObject()||!body.isEmpty()))throw TaskService.error(400,"INVALID_REQUEST","领取请求只接受空对象");
        return response(tasks.claim(current,packageId,key),request);
    }
    @GetMapping("/assignments")
    public ApiResponse<JsonNode> mine(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestParam(defaultValue="mine") String scope,@RequestParam(defaultValue="20") int limit,
            @RequestParam(required=false) String cursor,HttpServletRequest request){
        if(!"mine".equals(scope))throw TaskService.error(400,"INVALID_REQUEST","只支持本人领取列表");
        return response(tasks.mine(actor(auth),limit,cursor),request);
    }
    @GetMapping("/assignments/{assignmentId}/items")
    public ApiResponse<JsonNode> items(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID assignmentId,@RequestParam(defaultValue="20") int limit,
            @RequestParam(required=false) String cursor,HttpServletRequest request){return response(tasks.items(actor(auth),assignmentId,limit,cursor),request);}
    @GetMapping("/assignments/{assignmentId}/samples/{sampleId}/file")
    public ResponseEntity<Resource> file(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID assignmentId,@PathVariable UUID sampleId){
        var file=tasks.file(actor(auth),assignmentId,sampleId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).contentLength(file.sizeBytes())
                .header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").body(file.resource());
    }
    public record PlanRequest(@NotNull UUID batchId,@NotNull UUID specVersionId,Integer packageSize,Integer annotatorsPerSample){}
    public record PublishRequest(@NotNull @Pattern(regexp="[0-9a-f]{64}") String selectionHash){}
}
