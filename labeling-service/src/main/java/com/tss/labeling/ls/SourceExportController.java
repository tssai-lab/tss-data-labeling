package com.tss.labeling.ls;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/labeling/v1")
public class SourceExportController {
    private final SourceExportService service;private final IdentityClient identity;private final ActorService actors;
    public SourceExportController(SourceExportService service,IdentityClient identity,ActorService actors){this.service=service;this.identity=identity;this.actors=actors;}
    private ActorService.ActorContext actor(String auth){return actors.resolve(identity.currentUser(auth));}
    private ApiResponse<JsonNode> response(JsonNode result,HttpServletRequest req){return ApiResponse.success(result,MeController.requestId(req));}
    @GetMapping("/final-snapshots/{snapshot}/export-formats")
    public ApiResponse<JsonNode> formats(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID snapshot,HttpServletRequest req){return response(service.formats(actor(auth),snapshot),req);}
    @PostMapping("/final-snapshots/{snapshot}/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<JsonNode> create(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID snapshot,
            @RequestHeader("Idempotency-Key")String key,@Valid @RequestBody ExportRequest body,HttpServletRequest req){return response(service.create(actor(auth),snapshot,body.format(),key),req);}
    @GetMapping("/final-snapshots/{snapshot}/exports")
    public ApiResponse<JsonNode> list(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID snapshot,
            @RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String cursor,HttpServletRequest req){return response(service.list(actor(auth),snapshot,limit,cursor),req);}
    @GetMapping("/source-exports/{id}")
    public ApiResponse<JsonNode> get(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,HttpServletRequest req){return response(service.get(actor(auth),id),req);}
    @GetMapping("/source-exports/{id}/file")
    public ResponseEntity<Resource> file(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id){
        var file=service.file(actor(auth),id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(file.sizeBytes())
                .header("Content-Disposition","attachment; filename=\"annotations-"+id+".zip\"")
                .header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").body(file.resource());
    }
    public record ExportRequest(@NotBlank String format){}
}
