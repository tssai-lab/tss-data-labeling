package com.tss.labeling.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.*;
import com.tss.labeling.identity.*;
import com.tss.labeling.tasks.TaskInputService;
import com.tss.labeling.ls.LsSourceAdapter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1")
public class RecordValidationController {
    private final TaskInputService inputs; private final WorkflowStore s; private final LsSourceAdapter source;
    private final IdentityClient identity; private final ActorService actors;
    public RecordValidationController(TaskInputService inputs,WorkflowStore s,LsSourceAdapter source,IdentityClient identity,ActorService actors) {
        this.inputs=inputs;this.s=s;this.source=source;this.identity=identity;this.actors=actors;
    }
    @GetMapping("/projects/{project}/samples/{sample}/record-validation")
    public ApiResponse<JsonNode> owner(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID project,@PathVariable UUID sample,@RequestParam UUID specVersionId,HttpServletRequest request) {
        var input=inputs.owner(actors.resolve(identity.currentUser(auth)),project,sample);
        var spec=s.one("SELECT editor_config::text AS config FROM spec_version WHERE id=? AND project_id=?",specVersionId,project);
        return response(input,spec.get("config").toString(),request);
    }
    @GetMapping("/assignments/{assignment}/samples/{sample}/record-validation")
    public ApiResponse<JsonNode> assignment(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID assignment,@PathVariable UUID sample,HttpServletRequest request) {
        var input=inputs.assignment(actors.resolve(identity.currentUser(auth)),assignment,sample);
        var spec=s.one("SELECT v.editor_config::text AS config FROM assignment a JOIN publication_round r ON r.id=a.round_id JOIN spec_version v ON v.id=r.spec_version_id WHERE a.id=?",assignment);
        return response(input,spec.get("config").toString(),request);
    }
    private ApiResponse<JsonNode> response(JsonNode input,String config,HttpServletRequest request) {
        if(!input.has("record")) throw WorkflowStore.error(422,"TEMPLATE_MISMATCH","该样本不是记录插件输入");
        var fields=s.json.createObjectNode();fields.set("record",input.path("record"));fields.set("config",s.read(config));
        return ApiResponse.success(source.recordOperation("recordsIssues",fields),MeController.requestId(request));
    }
}
