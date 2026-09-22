package com.github.highcumontoa.tenantapiguardjava.common;

/**
 * 稳定且可区分的拒绝原因。code 一旦发布即保持稳定,作为客户端可依赖的判定依据。
 */
public enum RejectReason {

    TENANT_MISSING(400, "TENANT_MISSING", "请求缺少 X-Tenant-Id 头"),
    IDEMPOTENCY_KEY_MISSING(400, "IDEMPOTENCY_KEY_MISSING", "写操作缺少 Idempotency-Key 头"),

    QUOTA_EXCEEDED(429, "QUOTA_EXCEEDED", "租户在当前时间窗口内超出请求配额"),

    IDEMPOTENCY_CONFLICT(409, "IDEMPOTENCY_CONFLICT", "同一幂等标识对应的请求内容与首次提交不一致"),
    IDEMPOTENCY_INFLIGHT_TIMEOUT(504, "IDEMPOTENCY_INFLIGHT_TIMEOUT", "同一幂等标识的首次请求仍在处理,等待其结果超时"),

    TENANT_BURST_LIMITED(429, "TENANT_BURST_LIMITED", "租户并发数超过隔离上限"),
    GLOBAL_CAPACITY_LIMITED(503, "GLOBAL_CAPACITY_LIMITED", "本地整体资源紧张,触发全局保护上限"),
    IDEMPOTENCY_STORE_FULL(503, "IDEMPOTENCY_STORE_FULL", "本地幂等记录数达到保护上限");

    private final int httpStatus;
    private final String code;
    private final String message;

    RejectReason(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
