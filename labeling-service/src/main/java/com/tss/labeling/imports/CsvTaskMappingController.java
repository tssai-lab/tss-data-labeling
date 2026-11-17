package com.tss.labeling.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1/projects/{project}/input-resources")
public class CsvTaskMappingController {
    private final CsvTaskMappingService service;private final IdentityClient identity;private final ActorService actors;
    public CsvTaskMappingController(CsvTaskMappingService service,IdentityClient identity,ActorService actors){this.service=service;this.identity=identity;this.actors=actors;}
    @PostMapping("/{resource}/task-mapping")
    public ApiResponse<JsonNode> map(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID project,@PathVariable UUID resource,
            @RequestHeader("Idempotency-Key")String key,@RequestBody CsvTaskMappingService.Mapping body,HttpServletRequest req){
        return ApiResponse.success(service.map(actors.resolve(identity.currentUser(auth)),project,resource,body,key),MeController.requestId(req));
    }
}
