package com.github.highcumontoa.tenantapiguardjava.model;

import com.github.highcumontoa.tenantapiguardjava.quota.QuotaMode;

/**
 * 配额用量只读快照（不可变）。
 *
 * <p>由只读查询口子产生，不占用任何配额。{@link #allowed()} 表示在该快照所依据的时刻
 * 再进来一个请求会不会被放行；它与同一时刻真实请求的 {@code tryAcquire} 结论严格一致，
 * 因为二者走同一段判定逻辑、基于同一份运行时状态。</p>
 *
 * @param tenant        租户标识
 * @param mode          配额口径（FIXED / SLIDING）
 * @param allowed       判定依据：该时刻是否还能再放行一个
 * @param reason        不能放行时的稳定原因；能放行时为 {@code null}
 * @param consumed      当前所算区间内已用量
 * @param remaining     当前所算区间内剩余量
 * @param limit         配额上限
 * @param windowIndex   固定窗口序号（epoch 起算）；滑动口径下为覆盖当前时刻的固定格序号，仅作解释
 * @param windowStartMs 所算区间起始（FIXED：固定窗口起点；SLIDING：当前滑动区间左端点，左闭）
 * @param windowEndMs   所算区间结束（FIXED：固定窗口终点；SLIDING：当前滑动区间右端点，右开）
 * @param observedAtMs  快照依据的时刻（epoch 毫秒）
 */
public record QuotaUsage(
        String tenant,
        QuotaMode mode,
        boolean allowed,
        RejectReason reason,
        long consumed,
        long remaining,
        long limit,
        long windowIndex,
        long windowStartMs,
        long windowEndMs,
        long observedAtMs) {

    public static QuotaUsage of(String tenant, QuotaMode mode, boolean allowed, RejectReason reason,
                                long consumed, long remaining, long limit,
                                long windowIndex, long start, long end, long observedAtMs) {
        return new QuotaUsage(tenant, mode, allowed, reason, consumed, remaining, limit,
                windowIndex, start, end, observedAtMs);
    }
}
