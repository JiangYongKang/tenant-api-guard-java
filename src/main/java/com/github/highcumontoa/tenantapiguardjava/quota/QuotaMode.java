package com.github.highcumontoa.tenantapiguardjava.quota;

/**
 * 配额口径（按租户可选）。
 *
 * <ul>
 *   <li>{@link #FIXED}：固定时间窗口（默认，历史口径）。窗口由 {@code floor(now/windowMs)}
 *       切分，每个网格窗口独立计数：交界前后的请求各记各窗口的账，每窗口均不超过配额，
 *       交界并发下放行总数确定（详见 {@link QuotaService}）；</li>
 *   <li>{@link #SLIDING}：滑动日志窗口。任意一段长度为 {@code windowMs} 的连续时间区间
 *       （左闭右开）内，放行总量不超过配额，窗口交界处不会出现两段叠加放量。</li>
 * </ul>
 */
public enum QuotaMode {
    /** 固定时间窗口口径（默认，兼容老行为）。 */
    FIXED,
    /** 滑动窗口口径：任意连续 windowMs 内不超过配额。 */
    SLIDING
}
