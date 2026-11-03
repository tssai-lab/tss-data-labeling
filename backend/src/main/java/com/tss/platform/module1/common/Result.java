package com.tss.platform.module1.common;

import lombok.Data;

@Data
public class Result<T> {
    public static final Integer SUCCESS_CODE = 200;
    public static final Integer FAIL_CODE = 400;
    public static final Integer NO_AUTH_CODE = 403;
    public static final Integer UNAUTHORIZED_CODE = 401;
    public static final Integer SERVER_ERROR_CODE = 500;
    public static final Integer TOO_MANY_REQUESTS_CODE = 429;
    public static final Integer SERVICE_UNAVAILABLE_CODE = 503;

    private Integer code;
    private String message;
    private T data;

    private Result() {}

    public static <T> Result<T> success() {
        return success(null, "操作成功");
    }

    public static <T> Result<T> success(String message) {
        return success(null, message);
    }

    public static <T> Result<T> success(T data) {
        return success(data, "操作成功");
    }

    public static <T> Result<T> success(T data, String message) {
        Result<T> result = new Result<>();
        result.setCode(SUCCESS_CODE);
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    public static <T> Result<T> fail(String message) {
        Result<T> result = new Result<>();
        result.setCode(FAIL_CODE);
        result.setMessage(message);
        result.setData(null);
        return result;
    }

    public static <T> Result<T> noAuth(String message) {
        Result<T> result = new Result<>();
        result.setCode(NO_AUTH_CODE);
        result.setMessage(message == null ? "暂无操作权限" : message);
        result.setData(null);
        return result;
    }

    public static <T> Result<T> unauthorized(String message) {
        Result<T> result = new Result<>();
        result.setCode(UNAUTHORIZED_CODE);
        result.setMessage(message == null ? "请先登录" : message);
        result.setData(null);
        return result;
    }

    public static <T> Result<T> serverError(String message) {
        Result<T> result = new Result<>();
        result.setCode(SERVER_ERROR_CODE);
        result.setMessage(message == null ? "服务器内部错误" : message);
        result.setData(null);
        return result;
    }

    public static <T> Result<T> tooManyRequests(String message) {
        Result<T> result = new Result<>();
        result.setCode(TOO_MANY_REQUESTS_CODE);
        result.setMessage(message == null ? "请求过于频繁，请稍后重试" : message);
        result.setData(null);
        return result;
    }

    public static <T> Result<T> serviceUnavailable(String message) {
        Result<T> result = new Result<>();
        result.setCode(SERVICE_UNAVAILABLE_CODE);
        result.setMessage(message == null ? "服务暂时不可用" : message);
        result.setData(null);
        return result;
    }
}
