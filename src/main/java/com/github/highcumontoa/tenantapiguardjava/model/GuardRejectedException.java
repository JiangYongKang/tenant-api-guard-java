package com.github.highcumontoa.tenantapiguardjava.model;

import java.util.Map;

/**
 * 守卫拒绝异常：携带稳定原因与可解释明细，由全局异常处理器统一转换。
 */
public class GuardRejectedException extends RuntimeException {

    private final RejectReason reason;
    private final transient Map<String, Object> detail;

    public GuardRejectedException(RejectReason reason, String message, Map<String, Object> detail) {
        super(message);
        this.reason = reason;
        this.detail = detail;
    }

    public RejectReason reason() {
        return reason;
    }

    public Map<String, Object> detail() {
        return detail;
    }
}
