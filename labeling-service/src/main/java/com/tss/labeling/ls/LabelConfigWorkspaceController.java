package com.tss.labeling.ls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.*;
import com.tss.labeling.imports.NativeTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;
import static com.tss.labeling.tasks.TaskService.error;

/** HTTP boundary used by the source ConfigPage; never writes drafts or specs. */
@RestController
@RequestMapping("/api/labeling/v1/label-configs")
public class LabelConfigWorkspaceController {
    private final IdentityClient identity; private final ActorService actors;
    private final NativeTaskService inputs; private final TaskRegistry registry;
    private final LsSourceAdapter source; private final ObjectMapper json; private final TemplateCatalog catalog;
    private final ProjectSummaryService summaries;
    private static final Set<String> GROUPS=Set.of("Computer Vision","Time Series Analysis");
    public LabelConfigWorkspaceController(IdentityClient identity,ActorService actors,NativeTaskService inputs,
            TaskRegistry registry,LsSourceAdapter source,ObjectMapper json,TemplateCatalog catalog,ProjectSummaryService summaries) {
        this.identity=identity;this.actors=actors;this.inputs=inputs;this.registry=registry;this.source=source;this.json=json;
        this.catalog=catalog;
        this.summaries=summaries;
    }
    private ActorService.ActorContext actor(String auth){return actors.resolve(identity.currentUser(auth));}
    private ApiResponse<JsonNode> response(JsonNode value,HttpServletRequest request){return ApiResponse.success(value,MeController.requestId(request));}

    @GetMapping("/templates")
    public ApiResponse<JsonNode> templates(@RequestHeader(value="Authorization",required=false)String auth,
            @RequestParam(required=false)String category,HttpServletRequest request){
        actor(auth);
        if(category!=null&&!GROUPS.contains(category))throw error(422,"TASK_TYPE_NOT_REGISTERED","该模板类别尚未开放");
        var out=json.createObjectNode();var groups=out.putArray("groups");var templates=out.putArray("templates");
        JsonNode data=catalog.all();
        for(JsonNode group:data.path("groups"))if(GROUPS.contains(group.asText())&&(category==null||category.equals(group.asText())))groups.add(group);
        for(JsonNode template:data.path("templates"))if("community".equals(template.path("type").asText())&&GROUPS.contains(template.path("group").asText())
                &&(category==null||category.equals(template.path("group").asText())))templates.add(template);
        out.put("sourceVersion","1.23.2");return response(out,request);
    }

    public record ConfigRequest(@NotBlank @Size(max=262144)String labelConfig,UUID projectId,UUID sampleId,String taskType){}
    private JsonNode checked(ActorService.ActorContext actor,ConfigRequest request){
        if(request.sampleId()!=null&&request.projectId()==null)throw error(400,"INVALID_REQUEST","预览样本必须指定项目");
        String type=request.projectId()==null?request.taskType():inputs.configurationTaskType(actor,request.projectId());
        if(type==null)throw error(400,"INVALID_REQUEST","请选择任务类别");
        return registry.validateForPreview(type,request.labelConfig());
    }
    @PostMapping("/validate")
    public ApiResponse<JsonNode> validate(@RequestHeader(value="Authorization",required=false)String auth,
            @Valid @RequestBody ConfigRequest body,HttpServletRequest request){
        var config=checked(actor(auth),body);var out=json.createObjectNode().put("valid",true);
        out.set("parsedConfig",config.path("parsedConfig"));return response(out,request);
    }
    @PostMapping("/sample-task")
    public ApiResponse<JsonNode> preview(@RequestHeader(value="Authorization",required=false)String auth,
            @Valid @RequestBody ConfigRequest body,HttpServletRequest request){
        var actor=actor(auth);var config=checked(actor,body);
        return response(body.sampleId()!=null?inputs.configurationPreview(actor,body.projectId(),body.sampleId(),config):source.sample(body.labelConfig()),request);
    }
    @GetMapping("/data-summary")
    public ApiResponse<JsonNode> summary(@RequestHeader(value="Authorization",required=false)String auth,
            @RequestParam(required=false)UUID projectId,@RequestParam(required=false)UUID sampleId,HttpServletRequest request){
        var actor=actor(auth);
        if(projectId==null&&sampleId!=null)throw error(400,"INVALID_REQUEST","预览样本必须指定项目");
        var out=projectId==null?json.createObjectNode().set("common_data_columns",json.createArrayNode()):summaries.summary(actor,projectId,sampleId);
        return response(out,request);
    }
    @GetMapping("/preview-image")
    public ResponseEntity<Resource> previewImage(@RequestHeader(value="Authorization",required=false)String auth){
        actor(auth);return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).header("Cache-Control","private, max-age=3600")
                .body(new ClassPathResource("ls-preview-sample.jpg"));
    }
}
