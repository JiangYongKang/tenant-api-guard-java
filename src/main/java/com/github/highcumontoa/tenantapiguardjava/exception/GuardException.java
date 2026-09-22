package com.github.highcumontoa.tenantapiguardjava.exception;

import com.github.highcumontoa.tenantapiguardjava.common.RejectReason;

/**
 * 保护链路中产生的确定性拒绝异常,携带稳定的拒绝原因。
 */
public class GuardException extends RuntimeException {

    private final RejectReason reason;

    public GuardException(RejectReason reason) {
        super(reason.message());
        this.reason = reason;
    }

    public GuardException(RejectReason reason, String detail) {
        super(reason.message() + ": " + detail);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
