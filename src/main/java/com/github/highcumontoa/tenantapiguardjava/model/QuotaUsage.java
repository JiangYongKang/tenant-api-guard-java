package com.github.highcumontoa.tenantapiguardjava.model;

/**
 * 配额用量只读视图（不可变），供运维查询。
 *
 * <p>与 {@link QuotaDecision} 使用同一套判定逻辑、同一把租户锁产出，
 * 因此同一时刻的查询结果与真实请求的放行/拒绝结论一致；
 * 查询本身不计数、不触发窗口切换、不修改任何状态。</p>
 *
 * @param tenant         租户标识
 * @param mode           该租户当前生效的配额口径
 * @param windowIndex    固定口径下的窗口序号；滑动口径下为当前时刻对应的窗口序号（仅作参考）
 * @param segmentStartMs 判定所依据的时间区段起点（滑动口径为 {@code now - windowMs}）
 * @param segmentEndMs   判定所依据的时间区段终点（滑动口径为 {@code now}）
 * @param consumed       该区段内当前已用量
 * @param limit          该区段配额上限
 * @param remaining      剩余可放行量（limit - consumed，不为负）
 * @param wouldPermit    同一时刻若来一个真实请求，按当前状态会放行还是拒绝
 * @param reason         若会拒绝，对应的稳定原因；会放行时为 {@code null}
 */
public record QuotaUsage(
        String tenant,
        WindowMode mode,
        long windowIndex,
        long segmentStartMs,
        long segmentEndMs,
        long consumed,
        long limit,
        long remaining,
        boolean wouldPermit,
        RejectReason reason) {
}
