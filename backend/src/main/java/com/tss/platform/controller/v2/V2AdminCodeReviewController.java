package com.tss.platform.controller.v2;

import com.tss.platform.dto.v2.V2AdminCodeReviewTaskDetail;
import com.tss.platform.dto.v2.V2AdminCodeReviewTaskPage;
import com.tss.platform.dto.v2.V2AdminCodeRiskAssessment;
import com.tss.platform.dto.v2.V2AdminCodeRiskFinding;
import com.tss.platform.dto.v2.V2CodeFileContent;
import com.tss.platform.dto.v2.V2CodeFileNode;
import com.tss.platform.service.V2AdminCodeReviewService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v2/admin/code-review-tasks")
public class V2AdminCodeReviewController {

    private final V2AdminCodeReviewService service;

    public V2AdminCodeReviewController(V2AdminCodeReviewService service) {
        this.service = service;
    }

    @GetMapping
    public V2AdminCodeReviewTaskPage list(
            @RequestParam(defaultValue = "PENDING") String approvalStatus,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) Integer ownerUserId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant submittedFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant submittedTo,
            @RequestParam(defaultValue = "SUBMITTED_AT") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        return service.list(
                approvalStatus,
                riskLevel,
                ownerUserId,
                keyword,
                submittedFrom,
                submittedTo,
                sortBy,
                sortDirection,
                page,
                pageSize
        );
    }

    @GetMapping("/{versionId}")
    public V2AdminCodeReviewTaskDetail detail(@PathVariable String versionId) {
        return service.detail(versionId);
    }

    @GetMapping("/{versionId}/tree")
    public List<V2CodeFileNode> tree(
            @PathVariable String versionId,
            @RequestParam(required = false) String prefix
    ) {
        return service.tree(versionId, prefix);
    }

    @GetMapping("/{versionId}/files/content")
    public V2CodeFileContent content(
            @PathVariable String versionId,
            @RequestParam String path
    ) {
        return service.content(versionId, path);
    }

    @GetMapping("/{versionId}/findings")
    public List<V2AdminCodeRiskFinding> findings(@PathVariable String versionId) {
        return service.findings(versionId);
    }

    @PostMapping("/{versionId}/rescan")
    public V2AdminCodeRiskAssessment rescan(@PathVariable String versionId) {
        return service.rescan(versionId);
    }
}
