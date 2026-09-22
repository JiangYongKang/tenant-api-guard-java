package com.github.highcumontoa.tenantapiguardjava.model;

/**
 * 配额判定结论（不可变）。
 *
 * @param tenant          租户标识
 * @param allowed         是否放行
 * @param reason          被拒绝时的稳定原因；放行时为 {@code null}
 * @param windowIndex     命中的时间窗口序号（从 epoch 起算），用于解释窗口切换
 * @param windowStartMs   窗口起始时间（epoch 毫秒）
 * @param windowEndMs     窗口结束时间（epoch 毫秒）
 * @param consumed        该窗口内本次判定后的已用计数
 * @param limit           该窗口配额上限
 */
public record QuotaDecision(
        String tenant,
        boolean allowed,
        RejectReason reason,
        long windowIndex,
        long windowStartMs,
        long windowEndMs,
        long consumed,
        long limit) {

    public static QuotaDecision permit(String tenant, long windowIndex, long start, long end,
                                       long consumed, long limit) {
        return new QuotaDecision(tenant, true, null, windowIndex, start, end, consumed, limit);
    }

    public static QuotaDecision reject(String tenant, RejectReason reason, long windowIndex,
                                       long start, long end, long consumed, long limit) {
        return new QuotaDecision(tenant, false, reason, windowIndex, start, end, consumed, limit);
    }
}
