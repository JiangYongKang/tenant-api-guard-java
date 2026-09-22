package com.github.highcumontoa.tenantapiguardjava.model;

/**
 * 舱壁（按租户并发隔离）占用结论（不可变）。
 *
 * @param tenant     租户标识
 * @param acquired   是否成功占用一个并发槽
 * @param reason     未占用时的稳定原因
 * @param maxPermits 该租户的并发槽上限
 * @param inUse      当前占用槽位数
 */
public record BulkheadDecision(
        String tenant,
        boolean acquired,
        RejectReason reason,
        int maxPermits,
        int inUse) {

    public static BulkheadDecision acquired(String tenant, int maxPermits, int inUse) {
        return new BulkheadDecision(tenant, true, null, maxPermits, inUse);
    }

    public static BulkheadDecision rejected(String tenant, RejectReason reason,
                                            int maxPermits, int inUse) {
        return new BulkheadDecision(tenant, false, reason, maxPermits, inUse);
    }
}
