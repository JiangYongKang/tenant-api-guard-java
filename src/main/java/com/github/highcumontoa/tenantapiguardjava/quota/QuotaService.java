package com.github.highcumontoa.tenantapiguardjava.quota;

import java.util.ArrayDeque;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties.TenantQuotaSetting;
import com.github.highcumontoa.tenantapiguardjava.model.QuotaDecision;
import com.github.highcumontoa.tenantapiguardjava.model.QuotaUsage;
import com.github.highcumontoa.tenantapiguardjava.model.RejectReason;

/**
 * 按租户维度的配额服务，支持两种可按租户选择的口径：
 *
 * <ul>
 *   <li><b>{@link QuotaMode#FIXED}（默认，历史口径）</b>：窗口由 {@code floor(now/windowMs)}
 *       切分，每个网格窗口拥有独立计数槽；新窗口的请求只为自己的窗口开账，从不清零其它
 *       窗口的计数。交界两侧的请求无论怎么交错，每段放行数都不超过该租户配额，整批总数
 *       严格确定（只保留相邻两个窗口的槽位，更早窗口的迟到请求保守拒绝）；</li>
 *   <li><b>{@link QuotaMode#SLIDING}</b>：滑动日志窗口。任意长度为 {@code windowMs} 的连续
 *       时间区间（左闭右开）内，放行总量不超过配额，消除固定窗口交界处的两段叠加放量。</li>
 * </ul>
 *
 * <p>确定性保证：</p>
 * <ul>
 *   <li>每个租户拥有独立状态与锁，计数自增、新窗口开账在锁内原子完成，无论请求如何并发交错
 *       （包括正好压在时间交界上、时间戳乱序到达），同一批请求的放行总数严格确定，与调度
 *       先后无关；</li>
 *   <li>口径/配额按租户快照隔离：不同租户各用各的口径与上限，互不相干；判定每次都重读该
 *       租户最新配额，运行期调配额后后续判定立即与新配置一致；运行期修改某租户口径时，
 *       该租户旧口径状态被原子封存、以新口径空状态重新计数，不影响其他租户；</li>
 *   <li>查询（{@link #inspect} / {@link #peek}）与真实放行（{@link #tryAcquire}）走同一段
 *       锁内判定逻辑，同一时刻对同一份状态的结论一致；查询不计数、不改结构；</li>
 *   <li>空闲租户状态可由 {@link #evictIdle} 安全回收；回收采用 tryLock，拿不到锁（租户活跃）
 *       立即跳过，且仅在“回收不会让后续判定变宽松”时才移除，活跃租户判定不受影响。</li>
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
        while (true) {
            TenantQuotaSetting setting = properties.quotaSettingOf(tenant);
            if (setting.limit() <= 0) {
                long[] grid = fixedGrid(nowMillis, windowMs);
                log.info("quota decision input={{{}}} mode={} now={} basis=tenant quota<=0 decision=REJECT reason=TENANT_NOT_CONFIGURED",
                        tenant, setting.mode(), nowMillis);
                return QuotaDecision.reject(tenant, setting.mode(), RejectReason.TENANT_NOT_CONFIGURED,
                        grid[0], grid[1], grid[2], 0L, 0L);
            }

            TenantState state = states.computeIfAbsent(tenant,
                    t -> createState(setting.mode(), nowMillis));
            if (state.mode != setting.mode()) {
                // 运行期口径变更：旧口径状态封存（丢弃），换成新口径的空状态；只影响该租户
                TenantState fresh = createState(setting.mode(), nowMillis);
                if (states.replace(tenant, state, fresh)) {
                    log.info("quota mode switched tenant={} oldMode={} newMode={} basis=runtime config change, old state sealed",
                            tenant, state.mode, setting.mode());
                }
                continue;
            }

            state.lock.lock();
            try {
                if (states.get(tenant) != state) {
                    continue; // 持锁期间状态已被回收/替换，按新状态重判
                }
                Eval eval = evaluate(state, setting.limit(), nowMillis, windowMs, true);
                state.lastAccessMs = nowMillis;
                QuotaDecision decision = eval.allowed
                        ? QuotaDecision.permit(tenant, state.mode, eval.windowIndex, eval.start,
                                eval.end, eval.consumed, eval.limit)
                        : QuotaDecision.reject(tenant, state.mode, RejectReason.QUOTA_EXCEEDED,
                                eval.windowIndex, eval.start, eval.end, eval.consumed, eval.limit);
                log.info("quota decision input={{{}}} mode={} now={} basis={} consumed={}/{} interval=[{},{}) decision={}",
                        tenant, state.mode, nowMillis, state.mode,
                        eval.consumed, eval.limit, eval.start, eval.end,
                        eval.allowed ? "PASS" : "REJECT/QUOTA_EXCEEDED");
                return decision;
            } finally {
                state.lock.unlock();
            }
        }
    }

    /**
     * 只读查看某租户当前配额用量：已用、剩余、所算区间及“此刻再来一个是否放行”的判定依据。
     *
     * <p>不占用配额、不改变任何运行时结构（滑动口径连过期时间戳都不在本调用中摘除）；
     * 结论与同一时刻 {@link #tryAcquire} 的放行/拒绝一致。</p>
     */
    public QuotaUsage inspect(String tenant, long nowMillis) {
        long windowMs = properties.getWindowMs();
        TenantQuotaSetting setting = properties.quotaSettingOf(tenant);
        if (setting.limit() <= 0) {
            long[] grid = fixedGrid(nowMillis, windowMs);
            QuotaUsage u = QuotaUsage.of(tenant, setting.mode(), false,
                    RejectReason.TENANT_NOT_CONFIGURED, 0L, 0L, 0L,
                    grid[0], grid[1], grid[2], nowMillis);
            log.info("quota inspect input={{{}}} mode={} now={} basis=tenant quota<=0 allowed=false reason=TENANT_NOT_CONFIGURED",
                    tenant, setting.mode(), nowMillis);
            return u;
        }

        while (true) {
            TenantState state = states.get(tenant);
            if (state == null) {
                QuotaUsage u = freshUsage(tenant, setting, nowMillis, windowMs);
                log.info("quota inspect input={{{}}} mode={} now={} basis=no runtime state yet consumed=0/{}} allowed=true",
                        tenant, setting.mode(), nowMillis, setting.limit());
                return u;
            }
            if (state.mode != setting.mode()) {
                // 口径已改但首个新口径请求尚未到达：真实请求会以新口径空状态重计，查询保持同一结论
                QuotaUsage u = freshUsage(tenant, setting, nowMillis, windowMs);
                log.info("quota inspect input={{{}}} mode={} now={} basis=mode switch pending, new state empty consumed=0/{} allowed=true",
                        tenant, setting.mode(), nowMillis, setting.limit());
                return u;
            }
            state.lock.lock();
            try {
                if (states.get(tenant) != state) {
                    continue;
                }
                Eval eval = evaluate(state, setting.limit(), nowMillis, windowMs, false);
                long remaining = Math.max(0L, eval.limit - eval.consumed);
                QuotaUsage u = QuotaUsage.of(tenant, state.mode, eval.allowed,
                        eval.allowed ? null : RejectReason.QUOTA_EXCEEDED,
                        eval.consumed, remaining, eval.limit,
                        eval.windowIndex, eval.start, eval.end, nowMillis);
                log.info("quota inspect input={{{}}} mode={} now={} basis={} consumed={}/{} remaining={} interval=[{},{}) allowed={}",
                        tenant, state.mode, nowMillis, state.mode,
                        eval.consumed, eval.limit, remaining, eval.start, eval.end, eval.allowed);
                return u;
            } finally {
                state.lock.unlock();
            }
        }
    }

    /**
     * 只读：查看某租户当前时刻能否再放行，不产生计数、不改变状态。
     */
    public QuotaDecision peek(String tenant, long nowMillis) {
        QuotaUsage u = inspect(tenant, nowMillis);
        return u.allowed()
                ? QuotaDecision.permit(tenant, u.mode(), u.windowIndex(), u.windowStartMs(),
                        u.windowEndMs(), u.consumed(), u.limit())
                : QuotaDecision.reject(tenant, u.mode(), u.reason(), u.windowIndex(),
                        u.windowStartMs(), u.windowEndMs(), u.consumed(), u.limit());
    }

    /**
     * 回收长时间无请求的租户状态。
     *
     * @param nowMillis 当前时间
     * @param idleMs    空闲阈值（最近一次 tryAcquire 距 now 超过该值才考虑回收）
     * @return 实际回收的租户数
     */
    public int evictIdle(long nowMillis, long idleMs) {
        if (idleMs <= 0) {
            return 0;
        }
        long windowMs = properties.getWindowMs();
        int removed = 0;
        for (var entry : states.entrySet()) {
            TenantState state = entry.getValue();
            // 活跃租户（恰好有请求在判定）直接跳过，绝不阻塞或干扰在线请求
            if (!state.lock.tryLock()) {
                continue;
            }
            try {
                if (states.get(entry.getKey()) != state) {
                    continue;
                }
                if (nowMillis - state.lastAccessMs < idleMs) {
                    continue;
                }
                if (!safeToEvict(state, nowMillis, windowMs)) {
                    continue;
                }
                if (states.remove(entry.getKey(), state)) {
                    removed++;
                    log.info("quota state evicted tenant={} mode={} basis=idle>{}ms and no live count, lastAccess={} now={}",
                            entry.getKey(), state.mode, idleMs, state.lastAccessMs, nowMillis);
                }
            } finally {
                state.lock.unlock();
            }
        }
        if (removed > 0) {
            log.info("quota idle eviction sweep now={} idleMs={} removed={} remaining={}",
                    nowMillis, idleMs, removed, states.size());
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // 内部：状态与判定
    // ------------------------------------------------------------------

    private TenantState createState(QuotaMode mode, long nowMillis) {
        return mode == QuotaMode.SLIDING
                ? new SlidingState(nowMillis)
                : new FixedState(nowMillis);
    }

    /**
     * 锁内执行唯一一段配额判定。{@code acquire=true} 时为真实占用（允许变更计数），
     * {@code false} 时为只读观测（不做任何结构变更）。查询与放行共用此方法，
     * 保证同一时刻同一状态结论一致。
     */
    private Eval evaluate(TenantState state, long configuredLimit, long now, long windowMs,
                          boolean acquire) {
        if (state.mode == QuotaMode.SLIDING) {
            return evaluateSliding((SlidingState) state, configuredLimit, now, windowMs, acquire);
        }
        return evaluateFixed((FixedState) state, configuredLimit, now, windowMs, acquire);
    }

    private Eval evaluateFixed(FixedState fx, long configuredLimit, long now, long windowMs,
                               boolean acquire) {
        long windowIndex = Math.floorDiv(now, windowMs);
        long start = Math.multiplyExact(windowIndex, windowMs);
        long end = start + windowMs;

        // 比“已观察到的最新窗口”还早整整一个窗口以上的迟到请求：其窗口计数早已不在保留的
        // 相邻两窗范围内。固定窗口语义下该窗口本就不应再被本次批次放行，保守判定为拒绝，
        // 结论同样与调度顺序无关（不会重建计数把旧窗口重新放开）。
        if (fx.latestWindowIndex != null && windowIndex < fx.latestWindowIndex - 1L) {
            if (acquire) {
                log.info("quota fixed stale-window reject basis=window {} older than retained latest {}",
                        windowIndex, fx.latestWindowIndex);
            }
            return new Eval(windowIndex, start, end, configuredLimit, configuredLimit, false);
        }

        long[] slot = fx.counts.get(windowIndex);
        long consumed = slot == null ? 0L : slot[0];
        boolean newSlot = slot == null && acquire;
        if (newSlot) {
            // 该窗口第一次被真实请求命中：为这个窗口开一个从 0 起算的独立计数；
            // 不清零、不覆盖任何其它窗口已有计数。只读查询不落任何槽位
            slot = new long[1];
            fx.counts.put(windowIndex, slot);
        }
        // 放行判定基于递增前的计数；判定始终采用此刻读到的最新配置上限，
        // 运行期改配额后同一窗口的后续判定立即与新配置一致，无旧 limit 残留。
        boolean allowed = consumed < configuredLimit;
        if (acquire && allowed) {
            slot[0]++;
            consumed = slot[0];
        }
        if (acquire && (fx.latestWindowIndex == null || windowIndex > fx.latestWindowIndex)) {
            fx.latestWindowIndex = windowIndex;
            // 只留最新两个窗口的槽位，内存占用为常量；更早窗口的判定已在上文覆盖
            while (fx.counts.size() > FixedState.RETAINED_WINDOWS) {
                fx.counts.pollFirstEntry();
            }
        }
        if (newSlot) {
            log.info("quota window rollover basis=fixed newWindow={} otherWindowSlotsKept={}",
                    windowIndex, fx.counts.size() - 1);
        }
        return new Eval(windowIndex, start, end, consumed, configuredLimit, allowed);
    }

    private Eval evaluateSliding(SlidingState sl, long configuredLimit, long now, long windowMs,
                                 boolean acquire) {
        sl.limit = configuredLimit; // 滑动口径无窗口交界，配额调整即时生效
        long cutoff = now - windowMs;
        long windowIndex = Math.floorDiv(now, windowMs);
        long start = now - windowMs;
        long end = now;
        if (acquire) {
            // 严格滑出左边界（ts <= cutoff）的记录失效；存活区间 (cutoff, now] 即 (now-windowMs, now]
            sl.stamps.removeIf(ts -> ts <= cutoff);
            long live = sl.stamps.size();
            boolean allowed = live < configuredLimit;
            if (allowed) {
                sl.stamps.addLast(now);
                live++;
            }
            return new Eval(windowIndex, start, end, live, configuredLimit, allowed);
        }
        // 只读：不摘除任何元素（队列长度由占用路径的清理上界为 max(limit)），纯计数
        long live = 0L;
        for (long ts : sl.stamps) {
            if (ts > cutoff) {
                live++;
            }
        }
        return new Eval(windowIndex, start, end, live, configuredLimit, live < configuredLimit);
    }

    /** 回收后不会让新请求的判定变宽松，才允许回收（调用方持锁）。 */
    private boolean safeToEvict(TenantState state, long now, long windowMs) {
        if (state instanceof FixedState fx) {
            long windowIndex = Math.floorDiv(now, windowMs);
            long[] current = fx.counts.get(windowIndex);
            // 墙钟当前窗口有非零计数时回收会让判定变宽松，必须保留。上一窗口的槽位只服务于
            // 交界批次里持着上一窗口时间戳的在途请求；能走到回收说明该租户已空闲超过回收
            // 阈值（默认数十分钟），这种在途请求不可能还存在，旧窗口槽位不再构成放宽风险。
            return current == null || current[0] == 0L;
        }
        SlidingState sl = (SlidingState) state;
        long cutoff = now - windowMs;
        for (long ts : sl.stamps) {
            if (ts > cutoff) {
                return false; // 还有存活计数，回收会让后续判定变宽松，本轮跳过
            }
        }
        return true;
    }

    private QuotaUsage freshUsage(String tenant, TenantQuotaSetting setting, long now,
                                  long windowMs) {
        long consumed = 0L;
        long windowIndex;
        long start;
        long end;
        if (setting.mode() == QuotaMode.SLIDING) {
            windowIndex = Math.floorDiv(now, windowMs);
            start = now - windowMs;
            end = now;
        } else {
            long[] grid = fixedGrid(now, windowMs);
            windowIndex = grid[0];
            start = grid[1];
            end = grid[2];
        }
        return QuotaUsage.of(tenant, setting.mode(), true, null,
                consumed, setting.limit(), setting.limit(),
                windowIndex, start, end, now);
    }

    /** @return [windowIndex, start, end] */
    private static long[] fixedGrid(long now, long windowMs) {
        long windowIndex = Math.floorDiv(now, windowMs);
        long start = Math.multiplyExact(windowIndex, windowMs);
        return new long[]{windowIndex, start, start + windowMs};
    }

    /** 锁内判定结果。 */
    private record Eval(long windowIndex, long start, long end, long consumed, long limit,
                        boolean allowed) {
    }

    /** 单租户配额状态基类；{@code lock} 保护该租户全部计数。 */
    private abstract static class TenantState {
        private final ReentrantLock lock = new ReentrantLock();
        private final QuotaMode mode;
        private volatile long lastAccessMs;

        TenantState(QuotaMode mode, long nowMillis) {
            this.mode = mode;
            this.lastAccessMs = nowMillis;
        }
    }

    /**
     * 固定窗口状态：每个网格窗口拥有自己独立的计数槽位。
     *
     * <p>关键点：窗口计数<b>绝不因为另一个窗口的请求到来而被清零</b>。交界两侧的请求无论以
     * 什么顺序交错判定，都各记各窗口的账，因此“每段不超配额”和放行总数与调度先后无关。
     * {@code counts} 的 key 为窗口序号，value 为该窗口已放行数；为把内存上界钉死为常量，
     * 只保留最新观察到的两个窗口（当前窗口与前一窗口，正好覆盖交界并发），更早的窗口计数
     * 不再可能影响判定。所有访问均在该租户自己的锁内进行。</p>
     */
    private static final class FixedState extends TenantState {
        /** 保留的窗口槽位数：当前窗口 + 相邻的前一窗口。 */
        private static final int RETAINED_WINDOWS = 2;

        private final TreeMap<Long, long[]> counts = new TreeMap<>();
        /** 已观察到的最大窗口序号；{@code null} 表示一笔请求都还没有。 */
        private Long latestWindowIndex;

        FixedState(long nowMillis) {
            super(QuotaMode.FIXED, nowMillis);
        }
    }

    /** 滑动窗口状态：{@code stamps} 为历次放行时刻（升序），存活数即当前区间已用量。 */
    private static final class SlidingState extends TenantState {
        private final ArrayDeque<Long> stamps = new ArrayDeque<>();
        private long limit;

        SlidingState(long nowMillis) {
            super(QuotaMode.SLIDING, nowMillis);
        }
    }
}
