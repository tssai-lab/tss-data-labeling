package com.tss.platform.dto;

import lombok.Data;

@Data
public class ApiResponse<T> {
    private boolean success = true;
    private T data;
    private String errorMessage;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setData(data);
        return r;
    }

    public static <T> ApiResponse<T> fail(String errorMessage) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setSuccess(false);
        r.setErrorMessage(errorMessage);
        return r;
    }
}
