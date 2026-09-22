package com.github.highcumontoa.tenantapiguardjava.model;

/**
 * 稳定且可区分的拒绝原因。
 *
 * <p>{@link #code()} 为对外稳定字符串码，{@link #httpStatus()} 为对应 HTTP 状态码。
 * 无论并发情况如何，同一输入只会得到下列原因之一，不会出现不确定结论。</p>
 */
public enum RejectReason {

    /** 缺少租户标识。 */
    MISSING_TENANT("MISSING_TENANT", 400),

    /** 租户未配置配额（不允许静默放行或丢弃，明确拒绝）。 */
    TENANT_NOT_CONFIGURED("TENANT_NOT_CONFIGURED", 403),

    /** 当前时间窗口内配额已用尽。 */
    QUOTA_EXCEEDED("QUOTA_EXCEEDED", 429),

    /** 该租户并发占用已达舱壁上限，为隔离突发流量而拒绝。 */
    BULKHEAD_FULL("BULKHEAD_FULL", 429),

    /** 本地全局资源紧张（如总信号量耗尽）触发的可控降级。 */
    RESOURCE_PRESSURE("RESOURCE_PRESSURE", 503),

    /** 写请求缺少幂等标识。 */
    MISSING_IDEMPOTENCY_KEY("MISSING_IDEMPOTENCY_KEY", 400),

    /** 同一幂等标识首次请求仍在执行中，并发重复请求的确定结论。 */
    IDEMPOTENT_IN_PROGRESS("IDEMPOTENT_IN_PROGRESS", 409),

    /** 同一幂等标识携带了与首次不一致的请求内容。 */
    IDEMPOTENT_CONTENT_CONFLICT("IDEMPOTENT_CONTENT_CONFLICT", 409);

    private final String code;
    private final int httpStatus;

    RejectReason(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
