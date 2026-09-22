package com.github.highcumontoa.tenantapiguardjava.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 可解释的错误结构。{@code reason} 为稳定可区分原因码，{@code detail} 给出判定依据。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String reason, String message, String tenant, Map<String, Object> detail) {
}
