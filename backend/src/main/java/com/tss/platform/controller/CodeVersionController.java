package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.CodeVersionApprovalDto;
import com.tss.platform.dto.CodeVersionListItemDto;
import com.tss.platform.dto.CodeVersionTrainingCheckDto;
import com.tss.platform.service.CodeVersionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/code/version")
public class CodeVersionController {

    private final CodeVersionService codeVersionService;

    public CodeVersionController(CodeVersionService codeVersionService) {
        this.codeVersionService = codeVersionService;
    }

    @GetMapping("/list")
    public ApiResponse<List<CodeVersionListItemDto>> listApproved() {
        try {
            return ApiResponse.ok(codeVersionService.listApprovedForTraining());
        } catch (RuntimeException exception) {
            return ApiResponse.fail("代码版本列表加载失败");
        }
    }

    @PostMapping("/{codeVersionId}/approve")
    public ApiResponse<CodeVersionApprovalDto> approve(@PathVariable String codeVersionId) {
        try {
            return ApiResponse.ok(codeVersionService.approve(codeVersionId));
        } catch (RuntimeException exception) {
            return ApiResponse.fail("代码版本审批失败");
        }
    }

    @GetMapping("/{codeVersionId}/training-check")
    public ApiResponse<CodeVersionTrainingCheckDto> trainingCheck(
            @PathVariable String codeVersionId,
            @RequestParam("trainingProfile") String trainingProfile
    ) {
        try {
            return ApiResponse.ok(codeVersionService.trainingCheck(
                    codeVersionId, trainingProfile
            ));
        } catch (RuntimeException exception) {
            return ApiResponse.fail("代码版本校验失败");
        }
    }
}
