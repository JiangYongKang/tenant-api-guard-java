package com.github.highcumontoa.tenantapiguardjava.model;

/**
 * 配额口径（按租户可选）。
 *
 * <ul>
 *   <li>{@link #FIXED}：固定时间段口径（默认，保持既有行为）。按
 *       {@code floor(now / windowMs)} 切分窗口，每窗口独立计数；</li>
 *   <li>{@link #SLIDING}：滑动口径。任意一段长度为配额周期的连续时间内，
 *       放行的总量不超过配额（以请求时间戳集合判定）。</li>
 * </ul>
 */
public enum WindowMode {
    FIXED,
    SLIDING
}
