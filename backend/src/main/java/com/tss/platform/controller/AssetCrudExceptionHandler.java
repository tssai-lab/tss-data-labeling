package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.service.AssetNameConflictException;
import com.tss.platform.service.AssetNameValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        ModelAssetCrudController.class,
        DatasetAssetCrudController.class,
        ModelUploadController.class,
        DatasetUploadController.class,
        CodeUploadController.class
})
public class AssetCrudExceptionHandler {

    @ExceptionHandler(AssetNameValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidName(
            AssetNameValidationException exception
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(exception.getMessage()));
    }

    @ExceptionHandler(AssetNameConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleNameConflict(
            AssetNameConflictException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(exception.getMessage()));
    }
}
