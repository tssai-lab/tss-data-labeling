package com.tss.labeling.common;

// 成功响应包含业务数据及请求编号。
public record ApiResponse<T>(T data, String requestId) {

    public static <T> ApiResponse<T> success(
            T data,
            String requestId
    ) {
        return new ApiResponse<>(data, requestId);
    }
}