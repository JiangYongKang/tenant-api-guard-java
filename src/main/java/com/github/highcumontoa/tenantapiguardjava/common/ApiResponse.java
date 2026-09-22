package com.github.highcumontoa.tenantapiguardjava.common;

/**
 * 统一响应体。错误与业务成功均使用同一结构,保证结论可解释。
 */
public record ApiResponse(boolean success, Object data, String errorCode, String errorMessage) {

    public static ApiResponse ok(Object data) {
        return new ApiResponse(true, data, null, null);
    }

    public static ApiResponse error(RejectReason reason) {
        return new ApiResponse(false, null, reason.code(), reason.message());
    }
}
