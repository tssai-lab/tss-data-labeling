package com.tss.platform.controller.v2;

import com.tss.platform.service.ManifestValidationException;
import com.tss.platform.service.DatasetWorkspaceAuditService;
import com.tss.platform.service.ImportJobQueryService;
import com.tss.platform.service.SampleFileException;
import com.tss.platform.service.SampleService;
import com.tss.platform.service.CodeApprovalForbiddenException;
import com.tss.platform.service.CodeAssetImportException;
import com.tss.platform.service.CodeArtifactStorageException;
import com.tss.platform.service.CodeAssetAccessException;
import com.tss.platform.service.CodeContentTooLargeException;
import com.tss.platform.service.CodeValidationException;
import com.tss.platform.service.CodeWorkspaceConflictException;
import com.tss.platform.service.CodeWorkspacePublishException;
import com.tss.platform.service.AssetNameConflictException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@RestControllerAdvice(basePackages = "com.tss.platform.controller.v2")
public class V2ExceptionHandler {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final Pattern STABLE_REASON_CODE = Pattern.compile("[A-Z0-9_]+");

    @ExceptionHandler(AssetNameConflictException.class)
    public ResponseEntity<V2ErrorResponse> handleCodeAssetNameConflict(
            AssetNameConflictException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.CONFLICT,
                "CODE_ASSET_NAME_CONFLICT",
                "同一用户下已存在同名训练代码资产",
                Map.of(),
                request
        );
    }

    @ExceptionHandler(CodeAssetAccessException.class)
    public ResponseEntity<V2ErrorResponse> handleCodeAssetAccess(
            CodeAssetAccessException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.NOT_FOUND,
                "CODE_ASSET_NOT_FOUND",
                "代码资产不存在或无权访问",
                Map.of(),
                request
        );
    }

    @ExceptionHandler(CodeApprovalForbiddenException.class)
    public ResponseEntity<V2ErrorResponse> handleCodeApprovalForbidden(
            CodeApprovalForbiddenException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.FORBIDDEN,
                "CODE_APPROVAL_FORBIDDEN",
                "需要管理员审批权限",
                Map.of(),
                request
        );
    }

    @ExceptionHandler(CodeWorkspaceConflictException.class)
    public ResponseEntity<V2ErrorResponse> handleCodeWorkspaceConflict(
            CodeWorkspaceConflictException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.CONFLICT,
                "CODE_ASSET_CONFLICT",
                "代码资产已发生变更，请刷新后重试",
                Map.of("reasonCode", stableReasonCode(
                        exception.getReasonCode(), "CONFLICT"
                )),
                request
        );
    }

    @ExceptionHandler(CodeContentTooLargeException.class)
    public ResponseEntity<V2ErrorResponse> handleCodeContentTooLarge(
            CodeContentTooLargeException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "CODE_CONTENT_TOO_LARGE",
                "代码文件超过在线预览或编辑上限",
                Map.of("reasonCode", stableReasonCode(
                        exception.getReasonCode(), "FILE_TOO_LARGE"
                )),
                request
        );
    }

    @ExceptionHandler(CodeValidationException.class)
    public ResponseEntity<V2ErrorResponse> handleCodeValidation(
            CodeValidationException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "CODE_VALIDATION_FAILED",
                "代码资产校验失败",
                Map.of("reasonCode", stableReasonCode(
                        exception.getReasonCode(), "VALIDATION_FAILED"
                )),
                request
        );
    }

    @ExceptionHandler({
            CodeArtifactStorageException.class,
            CodeAssetImportException.class,
            CodeWorkspacePublishException.class
    })
    public ResponseEntity<V2ErrorResponse> handleCodeStorage(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CODE_STORAGE_UNAVAILABLE",
                "代码制品存储暂时不可用",
                Map.of("reasonCode", "STORAGE_UNAVAILABLE"),
                request
        );
    }

    @ExceptionHandler(V2BusinessException.class)
    public ResponseEntity<V2ErrorResponse> handleBusiness(
            V2BusinessException exception,
            HttpServletRequest request
    ) {
        return response(
                exception.getStatus(),
                exception.getErrorCode(),
                exception.getMessage(),
                exception.getDetails(),
                request
        );
    }

    @ExceptionHandler(ManifestValidationException.class)
    public ResponseEntity<V2ErrorResponse> handleManifest(
            ManifestValidationException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.UNPROCESSABLE_ENTITY,
                exception.getErrorCode(),
                exception.getMessage(),
                exception.getDetails(),
                request
        );
    }

    @ExceptionHandler(SampleFileException.class)
    public ResponseEntity<V2ErrorResponse> handleSampleFile(
            SampleFileException exception,
            HttpServletRequest request
    ) {
        Map<String, Object> details = exception.getRangeTotal() == null
                ? Map.of()
                : Map.of("rangeTotal", exception.getRangeTotal());
        ResponseEntity<V2ErrorResponse> response = response(
                exception.getStatus(),
                "SAMPLE_FILE_ERROR",
                exception.getMessage(),
                details,
                request
        );
        if (exception.getStatus() != HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE
                || exception.getRangeTotal() == null) {
            return response;
        }
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + exception.getRangeTotal())
                .body(response.getBody());
    }

    @ExceptionHandler(ImportJobQueryService.ImportJobAccessException.class)
    public ResponseEntity<V2ErrorResponse> handleImportJobAccess(
            ImportJobQueryService.ImportJobAccessException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.NOT_FOUND,
                "IMPORT_JOB_NOT_FOUND",
                "导入任务不存在或无权访问",
                Map.of(),
                request
        );
    }

    @ExceptionHandler(ImportJobQueryService.ImportJobRetryRejectedException.class)
    public ResponseEntity<V2ErrorResponse> handleImportJobRetryRejected(
            ImportJobQueryService.ImportJobRetryRejectedException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "IMPORT_JOB_NOT_RETRYABLE",
                "当前导入任务不可重试",
                reasonDetails(exception),
                request
        );
    }

    @ExceptionHandler(SampleService.DatasetVersionAccessException.class)
    public ResponseEntity<V2ErrorResponse> handleDatasetVersionAccess(
            SampleService.DatasetVersionAccessException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.NOT_FOUND,
                "DATASET_NOT_FOUND",
                "数据集版本不存在或无权访问",
                Map.of(),
                request
        );
    }

    @ExceptionHandler(DatasetWorkspaceAuditService.DatasetWorkspaceAuditAccessException.class)
    public ResponseEntity<V2ErrorResponse> handleWorkspaceAuditAccess(
            DatasetWorkspaceAuditService.DatasetWorkspaceAuditAccessException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.NOT_FOUND,
                "DATASET_AUDIT_NOT_FOUND",
                "数据集审计日志不存在或无权访问",
                Map.of(),
                request
        );
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<V2ErrorResponse> handleBadRequest(
            Exception exception,
            HttpServletRequest request
    ) {
        Map<String, Object> details = isCodeResourceRequest(request)
                ? Map.of("reasonCode", "INVALID_REQUEST")
                : badRequestDetails(exception);
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                safeBadRequestMessage(exception),
                details,
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<V2ErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        String traceId = traceId(request);
        log.error(
                "Unexpected V2 API failure: traceId={}, method={}, path={}",
                traceId,
                request.getMethod(),
                request.getRequestURI(),
                exception
        );
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "服务暂时不可用，请稍后重试",
                Map.of(),
                traceId
        );
    }

    private ResponseEntity<V2ErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            Map<String, Object> details,
            HttpServletRequest request
    ) {
        return response(status, code, message, details, traceId(request));
    }

    private ResponseEntity<V2ErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            Map<String, Object> details,
            String traceId
    ) {
        return ResponseEntity.status(status)
                .header(TRACE_HEADER, traceId)
                .body(V2ErrorResponse.failure(
                        code,
                        message,
                        V2ErrorDetailsSanitizer.sanitizeDetails(details, message),
                        traceId
                ));
    }

    private static String traceId(HttpServletRequest request) {
        String supplied = request.getHeader(TRACE_HEADER);
        if (supplied != null && !supplied.isBlank() && supplied.length() <= 128) {
            return supplied.trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String safeBadRequestMessage(Exception exception) {
        return exception instanceof IllegalArgumentException
                ? "请求参数不正确，请检查后重试"
                : "请求参数格式不正确";
    }

    private static Map<String, Object> badRequestDetails(Exception exception) {
        if (exception instanceof IllegalArgumentException illegalArgumentException) {
            return V2ErrorDetailsSanitizer.reasonDetails(
                    illegalArgumentException,
                    "请求参数不正确，请检查后重试"
            );
        }
        return Map.of();
    }

    private static Map<String, Object> reasonDetails(RuntimeException exception) {
        return V2ErrorDetailsSanitizer.reasonDetails(
                exception,
                "请求暂时无法完成，请稍后重试"
        );
    }

    private static boolean isCodeResourceRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath != null
                && !contextPath.isEmpty()
                && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
        return hasPathPrefix(path, "/api/v2/code-assets")
                || hasPathPrefix(path, "/api/v2/code-workspaces")
                || hasPathPrefix(path, "/api/v2/code-versions")
                || hasPathPrefix(path, "/api/v2/admin");
    }

    private static boolean hasPathPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static String stableReasonCode(String value, String fallback) {
        return value != null && STABLE_REASON_CODE.matcher(value).matches()
                ? value
                : fallback;
    }
}
