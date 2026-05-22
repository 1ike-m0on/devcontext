package com.devcontext.common.api;

public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        String errorCode
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, "ok", null);
    }

    public static <T> ApiResponse<T> fail(String errorCode, String message) {
        return new ApiResponse<>(false, null, message, errorCode);
    }
}

