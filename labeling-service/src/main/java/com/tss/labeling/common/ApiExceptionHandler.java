package com.tss.labeling.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleException(
            Exception exception,
            HttpServletRequest request
    ) {
        String requestId = (String) request.getAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        );
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
            request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, requestId);
        }

        ErrorDetails error;
        HttpStatus status;

        if (exception instanceof BusinessException business) {
            status = business.status();
            error = new ErrorDetails(business.code(), business.getMessage());
        } else if (exception instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException) {
            status = HttpStatus.BAD_REQUEST;
            error = new ErrorDetails("INVALID_REQUEST", "请求参数类型不正确");
        } else if (exception instanceof org.springframework.web.HttpMediaTypeNotSupportedException) {
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            error = new ErrorDetails("UNSUPPORTED_MEDIA_TYPE", "请求媒体类型不受支持");
        } else if (exception instanceof HttpMessageNotReadableException) {
            status = HttpStatus.BAD_REQUEST;
            error = new ErrorDetails("INVALID_REQUEST", "请求内容无法解析");
        } else if (exception instanceof MethodArgumentNotValidException
                || exception instanceof MissingRequestHeaderException
                || exception instanceof MissingServletRequestParameterException) {
            status = HttpStatus.BAD_REQUEST;
            error = new ErrorDetails("INVALID_REQUEST", "请求参数不符合要求");
        } else if (exception instanceof HttpRequestMethodNotSupportedException) {
            status = HttpStatus.METHOD_NOT_ALLOWED;
            error = new ErrorDetails("METHOD_NOT_ALLOWED", "请求方法不受支持");
        } else if (exception instanceof NoResourceFoundException) {
            status = HttpStatus.NOT_FOUND;
            error = new ErrorDetails("RESOURCE_NOT_FOUND", "请求的资源不存在");
        } else if (exception instanceof ResponseStatusException responseStatus) {
            status = HttpStatus.valueOf(responseStatus.getStatusCode().value());
            error = clientError(status);
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            error = new ErrorDetails("INTERNAL_ERROR", "服务器暂时无法处理请求");
        }

        ApiErrorResponse body = new ApiErrorResponse(error, requestId);
        if (status.is5xxServerError()) {
            log.error("请求处理失败 requestId={} status={} code={}",
                    requestId, status.value(), error.code(), exception);
        } else {
            log.warn("请求被拒绝 requestId={} status={} code={}",
                    requestId, status.value(), error.code());
        }
        return ResponseEntity.status(status).body(body);
    }

    private ErrorDetails clientError(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> new ErrorDetails("UNAUTHENTICATED", "请先登录");
            case FORBIDDEN -> new ErrorDetails("FORBIDDEN", "当前账号已停用或无权执行此操作");
            case NOT_FOUND -> new ErrorDetails(
                    "RESOURCE_NOT_FOUND", "请求的资源不存在");
            case CONFLICT -> new ErrorDetails(
                    "RESOURCE_CONFLICT", "请求与当前资源状态冲突");
            case SERVICE_UNAVAILABLE -> new ErrorDetails(
                    "IDENTITY_UNAVAILABLE", "统一身份服务暂时不可用");
            case BAD_REQUEST -> new ErrorDetails(
                    "INVALID_REQUEST", "请求参数不符合要求");
            case TOO_MANY_REQUESTS -> new ErrorDetails(
                    "TOO_MANY_REQUESTS", "请求过于频繁，请稍后重试");
            default -> new ErrorDetails(
                    status.is4xxClientError() ? "REQUEST_REJECTED" : "SERVICE_ERROR",
                    status.is4xxClientError()
                            ? "请求无法处理"
                            : "服务暂时不可用");
        };
    }

    public record ErrorDetails(String code, String message) {
    }

    public record ApiErrorResponse(ErrorDetails error, String requestId) {
    }
}
