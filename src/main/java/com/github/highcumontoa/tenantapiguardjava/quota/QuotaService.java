package com.github.highcumontoa.tenantapiguardjava.quota;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.model.QuotaDecision;
import com.github.highcumontoa.tenantapiguardjava.model.QuotaUsage;
import com.github.highcumontoa.tenantapiguardjava.model.RejectReason;
import com.github.highcumontoa.tenantapiguardjava.model.WindowMode;

/**
 * 按租户维度的配额服务，支持两种口径（按租户各自选择、可同时并存）：
 *
 * <ul>
 *   <li>{@link WindowMode#FIXED} 固定时间段（默认，既有行为）：按
 *       {@code floor(now / windowMs)} 切分窗口，窗口内计数不超过配额；</li>
 *   <li>{@link WindowMode#SLIDING} 滑动口径：任意一段长度为 {@code windowMs} 的
 *       连续时间内放行的总量不超过配额，以放行请求的时间戳集合判定，
 *       消除固定窗口交界处“卡两波”的问题。</li>
 * </ul>
 *
 * <p>确定性保证：</p>
 * <ul>
 *   <li>每个租户拥有独立状态与锁，计数/时间戳的读写与窗口切换都在锁内原子完成，
 *       并发下（包括压在窗口交界上）不会超额放行，也不会漏计/重计，
 *       同一批请求放过去的总数与线程交错顺序无关；</li>
 *   <li>固定口径按窗口独立计数：窗口向前推进时旧窗口计数封存（保留最近一个窗口，
 *       供压在交界上的并发请求结算），新窗口首次创建时重新读取配额配置，
 *       配置调整在下一个窗口平滑生效，不回溯当前窗口；
 *       滑动口径无窗口边界，配置调整对后续请求立即生效；</li>
 *   <li>租户之间状态互不影响，运行期配置变更按租户独立读取，互不惊扰；</li>
 *   <li>不活跃租户状态可被回收：仅当空闲时长达到 max(stateIdleMs, windowMs) 才回收，
 *       此时状态语义已过期（固定窗口已翻篇/滑动时间戳全部失效），
 *       回收不改变任何活跃租户的判定结果。</li>
 * </ul>
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final GuardProperties properties;
    private final ConcurrentHashMap<String, TenantState> states = new ConcurrentHashMap<>();

    public QuotaService(GuardProperties properties) {
        this.properties = properties;
    }

    /**
     * 尝试占用一个配额。
     *
     * @param tenant     租户标识
     * @param nowMillis  当前时间（注入便于测试窗口边界）
     */
    public QuotaDecision tryAcquire(String tenant, long nowMillis) {
        long windowMs = properties.getWindowMs();
        long windowIndex = Math.floorDiv(nowMillis, windowMs);
        long configuredLimit = properties.quotaOf(tenant);
        WindowMode mode = properties.windowModeOf(tenant);

        if (configuredLimit <= 0) {
            log.info("quota decision input={{}, now={}, mode={}} basis=tenant quota<=0, decision=REJECT reason=TENANT_NOT_CONFIGURED",
                    tenant, nowMillis, mode);
            return QuotaDecision.reject(tenant, RejectReason.TENANT_NOT_CONFIGURED,
                    windowIndex, segmentStart(mode, windowIndex, windowMs, nowMillis),
                    segmentEnd(mode, windowIndex, windowMs, nowMillis), 0L, 0L);
        }

        for (;;) {
            TenantState state = states.computeIfAbsent(tenant, t -> new TenantState());
            QuotaDecision decision;
            synchronized (state.lock) {
                if (states.get(tenant) != state) {
                    // 状态刚被回收器摘除，重新获取新状态再判定，避免计数丢失
                    continue;
                }
                decision = (mode == WindowMode.SLIDING)
                        ? acquireSliding(state, tenant, nowMillis, windowMs, windowIndex, configuredLimit)
                        : acquireFixed(state, tenant, windowMs, windowIndex, configuredLimit);
                state.lastAccessMs = nowMillis;
            }
            log.info("quota decision input={{}, now={}, mode={}} basis=consumed {}/{} in [{},{}), decision={}",
                    tenant, nowMillis, mode, decision.consumed(), decision.limit(),
                    decision.windowStartMs(), decision.windowEndMs(),
                    decision.allowed() ? "PERMIT" : "REJECT reason=" + decision.reason().code());
            return decision;
        }
    }

    /** 固定口径：窗口内计数判定（既有行为）。 */
    private QuotaDecision acquireFixed(TenantState state, String tenant,
                                       long windowMs, long windowIndex, long configuredLimit) {
        long start = Math.multiplyExact(windowIndex, windowMs);
        long end = start + windowMs;
        if (windowIndex > state.maxWindowIndex) {
            // 窗口向前推进：封存旧窗口计数（保留最近一个窗口供压在交界上的并发请求结算），
            // 更早的窗口计数不再可能被并发请求触及，直接淘汰
            log.info("quota window rollover tenant={}, oldWindow={}, newWindow={}",
                    tenant, state.maxWindowIndex, windowIndex);
            state.maxWindowIndex = windowIndex;
            state.windows.keySet().removeIf(w -> w < windowIndex - 1);
        }
        // 晚到的旧窗口请求计入旧窗口自己的计数，绝不触发状态回退清零；
        // 新窗口首次创建时重新读取配置（平滑生效，不回溯当前窗口）
        WindowCounter counter = state.windows.computeIfAbsent(
                windowIndex, w -> new WindowCounter(configuredLimit));
        if (counter.consumed < counter.limit) {
            counter.consumed++;
            return QuotaDecision.permit(tenant, windowIndex, start, end, counter.consumed, counter.limit);
        }
        return QuotaDecision.reject(tenant, RejectReason.QUOTA_EXCEEDED,
                windowIndex, start, end, counter.consumed, counter.limit);
    }

    /** 滑动口径：任意 windowMs 长度区间内的放行总量不超过配额。 */
    private QuotaDecision acquireSliding(TenantState state, String tenant, long nowMillis,
                                         long windowMs, long windowIndex, long configuredLimit) {
        long start = nowMillis - windowMs;
        evictExpired(state, nowMillis, windowMs);
        long consumed = state.hits.size();
        if (consumed < configuredLimit) {
            state.hits.addLast(nowMillis);
            return QuotaDecision.permit(tenant, windowIndex, start, nowMillis,
                    consumed + 1, configuredLimit);
        }
        return QuotaDecision.reject(tenant, RejectReason.QUOTA_EXCEEDED,
                windowIndex, start, nowMillis, consumed, configuredLimit);
    }

    /** 移除已滑出区间的时间戳（t <= now - windowMs 不再占用配额）。 */
    private static void evictExpired(TenantState state, long nowMillis, long windowMs) {
        long threshold = nowMillis - windowMs;
        while (!state.hits.isEmpty() && state.hits.peekFirst() <= threshold) {
            state.hits.pollFirst();
        }
    }

    /**
     * 只读查询某租户当前配额用量：已用、剩余、判定区段与判定依据。
     *
     * <p>与 {@link #tryAcquire} 共用同一套判定逻辑与同一把租户锁，但不计数、
     * 不触发窗口切换、不清理时间戳、不刷新活跃时间——纯读。
     * 同一时刻的查询结论与紧随其后的真实请求判定一致（中间无其他请求时）。</p>
     */
    public QuotaUsage usage(String tenant, long nowMillis) {
        long windowMs = properties.getWindowMs();
        long windowIndex = Math.floorDiv(nowMillis, windowMs);
        long configuredLimit = properties.quotaOf(tenant);
        WindowMode mode = properties.windowModeOf(tenant);
        long start = segmentStart(mode, windowIndex, windowMs, nowMillis);
        long end = segmentEnd(mode, windowIndex, windowMs, nowMillis);

        if (configuredLimit <= 0) {
            log.info("quota usage input={{}, now={}, mode={}} basis=tenant quota<=0, wouldPermit=false",
                    tenant, nowMillis, mode);
            return new QuotaUsage(tenant, mode, windowIndex, start, end, 0L, 0L, 0L,
                    false, RejectReason.TENANT_NOT_CONFIGURED);
        }

        TenantState state = states.get(tenant);
        long consumed = 0L;
        long limit = configuredLimit;
        if (state != null) {
            synchronized (state.lock) {
                if (mode == WindowMode.SLIDING) {
                    long threshold = nowMillis - windowMs;
                    long valid = 0L;
                    for (long t : state.hits) {
                        if (t > threshold) {
                            valid++;
                        }
                    }
                    consumed = valid;
                } else {
                    WindowCounter counter = state.windows.get(windowIndex);
                    if (counter != null) {
                        consumed = counter.consumed;
                        limit = counter.limit;
                    }
                }
            }
        }
        boolean wouldPermit = consumed < limit;
        QuotaUsage usage = new QuotaUsage(tenant, mode, windowIndex, start, end,
                consumed, limit, Math.max(0L, limit - consumed),
                wouldPermit, wouldPermit ? null : RejectReason.QUOTA_EXCEEDED);
        log.info("quota usage input={{}, now={}, mode={}} basis=consumed {}/{} in [{},{}), wouldPermit={}",
                tenant, nowMillis, mode, consumed, limit, start, end, wouldPermit);
        return usage;
    }

    /**
     * 仅供测试/运维：查看某租户当前是否还可放行，不产生计数，不触发窗口重置。
     *
     * @deprecated 运维查询请使用信息更全的 {@link #usage(String, long)}。
     */
    @Deprecated
    public QuotaDecision peek(String tenant, long nowMillis) {
        QuotaUsage u = usage(tenant, nowMillis);
        return u.wouldPermit()
                ? QuotaDecision.permit(tenant, u.windowIndex(), u.segmentStartMs(), u.segmentEndMs(),
                        u.consumed(), u.limit())
                : QuotaDecision.reject(tenant, u.reason(), u.windowIndex(),
                        u.segmentStartMs(), u.segmentEndMs(), u.consumed(), u.limit());
    }

    /**
     * 回收不活跃租户的配额运行时状态。
     *
     * <p>仅回收空闲时长达到 max(stateIdleMs, windowMs) 的状态：此时固定窗口早已翻篇、
     * 滑动时间戳全部失效，状态不再携带任何影响判定的信息，回收对活跃租户完全透明。
     * 检查与摘除在同一租户锁内完成，并发的 {@link #tryAcquire} 若拿到已摘除状态会
     * 自动重取新状态，不会丢计数。</p>
     *
     * @return 本次回收的租户状态数量
     */
    public int sweepInactive(long nowMillis) {
        long idleThreshold = Math.max(properties.getStateIdleMs(), properties.getWindowMs());
        int removed = 0;
        for (Map.Entry<String, TenantState> entry : states.entrySet()) {
            TenantState state = entry.getValue();
            synchronized (state.lock) {
                long idleFor = nowMillis - state.lastAccessMs;
                if (idleFor >= idleThreshold && states.remove(entry.getKey(), state)) {
                    removed++;
                    log.info("quota state reclaimed tenant={}, idleForMs={}, thresholdMs={}",
                            entry.getKey(), idleFor, idleThreshold);
                }
            }
        }
        if (removed > 0) {
            log.info("quota sweep reclaimed={} remaining={}", removed, states.size());
        }
        return removed;
    }

    /** 当前持有运行时状态的租户数（供测试与运维观察）。 */
    public int trackedTenantCount() {
        return states.size();
    }

    private static long segmentStart(WindowMode mode, long windowIndex, long windowMs, long nowMillis) {
        return mode == WindowMode.SLIDING
                ? nowMillis - windowMs
                : Math.multiplyExact(windowIndex, windowMs);
    }

    private static long segmentEnd(WindowMode mode, long windowIndex, long windowMs, long nowMillis) {
        return mode == WindowMode.SLIDING
                ? nowMillis
                : Math.multiplyExact(windowIndex, windowMs) + windowMs;
    }

    /** 单个租户的配额状态（两种口径共用一把锁）。 */
    private static final class TenantState {
        private final Object lock = new Object();
        // 固定口径：最近若干窗口的独立计数（只保留 maxWindowIndex-1 及之后，见 acquireFixed）
        private long maxWindowIndex = Long.MIN_VALUE;
        private final Map<Long, WindowCounter> windows = new HashMap<>();
        // 滑动口径：放行请求的时间戳（升序）
        private final ArrayDeque<Long> hits = new ArrayDeque<>();
        // 最近一次判定时间，用于不活跃回收
        private long lastAccessMs;
    }

    /** 固定口径单个窗口的计数。 */
    private static final class WindowCounter {
        private long consumed;
        private final long limit;

        private WindowCounter(long limit) {
            this.limit = limit;
        }
    }
}
