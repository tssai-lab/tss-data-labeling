package com.tss.labeling.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1")
public class NativeTaskController {
    private final NativeTaskService service;private final IdentityClient identity;private final ActorService actors;
    public NativeTaskController(NativeTaskService service,IdentityClient identity,ActorService actors) {
        this.service=service;this.identity=identity;this.actors=actors;
    }
    private ActorService.ActorContext actor(String auth){return actors.resolve(identity.currentUser(auth));}
    private ApiResponse<JsonNode> response(JsonNode value,HttpServletRequest req){return ApiResponse.success(value,MeController.requestId(req));}
    @GetMapping("/projects/{project}/input-resources")
    public ApiResponse<JsonNode> resources(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID project,@RequestParam(defaultValue="50")int limit,@RequestParam(required=false)String cursor,HttpServletRequest req){
        return response(service.resources(actor(auth),project,limit,cursor),req);
    }
    @GetMapping("/projects/{project}/input-resources/{resource}/file")
    public ResponseEntity<Resource> inputResource(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID project,@PathVariable UUID resource){
        return file(service.ownerInputResource(actor(auth),project,resource));
    }
    @PostMapping("/projects/{project}/imports/{batch}/tasks")
    public ApiResponse<JsonNode> prepare(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID project,@PathVariable UUID batch,@RequestHeader("Idempotency-Key")String key,
            @RequestBody JsonNode body,HttpServletRequest req){
        return response(service.prepare(actor(auth),project,batch,body.path("tasks"),key),req);
    }
    @GetMapping("/projects/{project}/input-samples")
    public ApiResponse<JsonNode> samples(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID project,@RequestParam(defaultValue="50")int limit,@RequestParam(required=false)String cursor,HttpServletRequest req){
        return response(service.list(actor(auth),project,limit,cursor),req);
    }
    @GetMapping("/projects/{project}/samples/{sample}/task")
    public ApiResponse<JsonNode> ownerTask(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID project,@PathVariable UUID sample,@RequestParam UUID specVersionId,HttpServletRequest req){
        return response(service.ownerTask(actor(auth),project,sample,specVersionId),req);
    }
    @GetMapping("/projects/{project}/samples/{sample}/native-input")
    public ApiResponse<JsonNode> input(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID project,@PathVariable UUID sample,HttpServletRequest req){
        return response(service.ownerInput(actor(auth),project,sample),req);
    }
    @GetMapping("/assignments/{assignment}/samples/{sample}/task")
    public ApiResponse<JsonNode> assignmentTask(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID assignment,@PathVariable UUID sample,HttpServletRequest req){
        return response(service.assignmentTask(actor(auth),assignment,sample),req);
    }
    @GetMapping("/assignments/{assignment}/samples/{sample}/reference-task")
    public ApiResponse<JsonNode> reference(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID assignment,@PathVariable UUID sample,HttpServletRequest req){return response(service.referenceTask(actor(auth),assignment,sample),req);}
    @GetMapping("/assignments/{assignment}/samples/{sample}/reference/native-resources/{resource}")
    public ResponseEntity<Resource> referenceResource(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID assignment,@PathVariable UUID sample,@PathVariable UUID resource){return file(service.referenceResource(actor(auth),assignment,sample,resource));}
    @GetMapping("/projects/{project}/samples/{sample}/native-resources/{resource}")
    public ResponseEntity<Resource> ownerResource(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID project,@PathVariable UUID sample,@PathVariable UUID resource,@RequestParam UUID specVersionId){
        return file(service.resource(actor(auth),project,sample,resource,specVersionId,false));
    }
    @GetMapping("/assignments/{assignment}/samples/{sample}/native-resources/{resource}")
    public ResponseEntity<Resource> assignmentResource(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID assignment,@PathVariable UUID sample,@PathVariable UUID resource){
        return file(service.resource(actor(auth),assignment,sample,resource,null,true));
    }
    private ResponseEntity<Resource> file(ImportService.SampleFile file){
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).contentLength(file.sizeBytes())
                .header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").body(file.resource());
    }
}
