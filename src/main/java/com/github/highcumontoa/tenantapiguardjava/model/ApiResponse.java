package com.github.highcumontoa.tenantapiguardjava.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应封装：成功结果与被拒绝结论均通过稳定字段表达，绝不静默丢弃。
 *
 * @param success 是否成功（业务成功或幂等重放均为 true）
 * @param data    成功数据
 * @param error   拒绝/失败时的稳定错误结构
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse(boolean success, Object data, ErrorBody error) {

    public static ApiResponse ok(Object data) {
        return new ApiResponse(true, data, null);
    }

    public static ApiResponse error(ErrorBody error) {
        return new ApiResponse(false, null, error);
    }
}
