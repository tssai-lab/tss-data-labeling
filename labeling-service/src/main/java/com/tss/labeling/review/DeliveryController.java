package com.tss.labeling.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.ApiResponse;
import com.tss.labeling.identity.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/labeling/v1")
public class DeliveryController {
    private final AggregationService aggregation;
    private final ExportService exports;
    private final IdentityClient identity;
    private final ActorService actors;

    public DeliveryController(
            AggregationService aggregation,
            ExportService exports,
            IdentityClient identity,
            ActorService actors) {
        this.aggregation = aggregation;
        this.exports = exports;
        this.identity = identity;
        this.actors = actors;
    }

    private ActorService.ActorContext actor(String auth) {
        return actors.resolve(identity.currentUser(auth));
    }

    private <T> ApiResponse<T> out(T value, HttpServletRequest req) {
        return ApiResponse.success(value, MeController.requestId(req));
    }

    @GetMapping("/aggregation-algorithms")
    public ApiResponse<?> algorithms(
            @RequestHeader(value = "Authorization", required = false) String auth,
            HttpServletRequest req) {
        actor(auth);
        return out(
                List.of(
                        Map.of(
                                "algorithmId",
                                "majority-vote-v1",
                                "taskType",
                                "IMAGE_CLASSIFICATION",
                                "minimumIndependentSources",
                                2,
                                "description",
                                "按独立参与者的原始答案严格过半；平票或不足不生成建议，建议须另行审核")),
                req);
    }

    @PostMapping("/projects/{projectId}/aggregations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JsonNode> aggregate(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody AggregationService.Input body,
            HttpServletRequest req) {
        return out(aggregation.create(actor(auth), projectId, body, key), req);
    }

    @GetMapping("/aggregations/{aggregationId}")
    public ApiResponse<JsonNode> aggregation(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID aggregationId,
            HttpServletRequest req) {
        return out(aggregation.view(actor(auth), aggregationId), req);
    }

    @GetMapping("/aggregations/{aggregationId}/items")
    public ApiResponse<JsonNode> items(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID aggregationId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest req) {
        return out(aggregation.items(actor(auth), aggregationId, limit, cursor), req);
    }

    @GetMapping("/final-snapshots/{snapshotId}/plugin-export-formats")
    public ApiResponse<JsonNode> pluginExportFormats(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID snapshotId,HttpServletRequest req) {
        return out(exports.formats(actor(auth),snapshotId),req);
    }
    @GetMapping("/projects/{projectId}/exports")
    public ApiResponse<JsonNode> exportList(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID projectId,@RequestParam(required=false)UUID snapshotId,
            @RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String cursor,HttpServletRequest req) {
        return out(exports.list(actor(auth),projectId,snapshotId,limit,cursor),req);
    }

    @PostMapping("/projects/{projectId}/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<JsonNode> createExport(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody ExportService.Input body,
            HttpServletRequest req) {
        return out(exports.create(actor(auth), projectId, body, key), req);
    }

    @GetMapping("/exports/{exportId}")
    public ApiResponse<JsonNode> export(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID exportId,
            HttpServletRequest req) {
        return out(exports.view(actor(auth), exportId), req);
    }

    @GetMapping("/exports/{exportId}/download")
    public ResponseEntity<byte[]> download(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable UUID exportId) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition", "attachment; filename=labeling-" + exportId + ".zip")
                .header("Cache-Control", "private, no-store")
                .body(exports.download(actor(auth), exportId));
    }
}
