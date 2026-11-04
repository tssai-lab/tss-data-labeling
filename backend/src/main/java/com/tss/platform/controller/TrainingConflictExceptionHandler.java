package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 并发落库失败有明确回执，用户可刷新，Worker 可沿原协议重试。 */
@RestControllerAdvice(assignableTypes = {TrainingTaskController.class, TrainingExperimentController.class,
        InternalTrainingCallbackController.class, ResourceMonitorController.class})
public class TrainingConflictExceptionHandler {
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Object>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail("任务状态已被更新，请刷新后重试"));
    }
}
