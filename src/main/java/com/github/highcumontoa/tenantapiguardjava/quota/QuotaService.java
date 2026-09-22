package com.github.highcumontoa.tenantapiguardjava.quota;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.model.QuotaDecision;
import com.github.highcumontoa.tenantapiguardjava.model.RejectReason;

/**
 * 按租户维度的固定时间窗口配额服务。
 *
 * <p>确定性保证：</p>
 * <ul>
 *   <li>每个租户拥有独立状态与锁，计数自增与窗口切换在锁内原子完成，
 *       并发下不会超额放行，也不会漏计/重计；</li>
 *   <li>窗口切换采用惰性重置：首个落入新窗口的请求把计数清零并从配置重新读取配额，
 *       因此配置调整在当前窗口结束、下一个窗口开始时平滑生效，不回溯当前窗口；</li>
 *   <li>租户之间状态互不影响。</li>
 * </ul>
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final GuardProperties properties;
    private final ConcurrentHashMap<String, WindowState> states = new ConcurrentHashMap<>();

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
        long start = Math.multiplyExact(windowIndex, windowMs);
        long end = start + windowMs;
        long configuredLimit = properties.quotaOf(tenant);

        if (configuredLimit <= 0) {
            log.info("quota decision input={{}}, now={}}, window={}}, basis=tenant quota<=0, decision=REJECT reason=TENANT_NOT_CONFIGURED",
                    tenant, nowMillis, windowIndex);
            return QuotaDecision.reject(tenant, RejectReason.TENANT_NOT_CONFIGURED,
                    windowIndex, start, end, 0L, 0L);
        }

        WindowState state = states.computeIfAbsent(tenant, t -> new WindowState());
        QuotaDecision decision;
        synchronized (state.lock) {
            if (state.windowIndex != windowIndex) {
                // 窗口切换：旧窗口计数封存，新窗口从 0 开始，并重新读取配置（平滑生效）
                log.info("quota window rollover tenant={}}, oldWindow={}}, newWindow={}}, oldConsumed={}}",
                        tenant, state.windowIndex, windowIndex, state.consumed);
                state.windowIndex = windowIndex;
                state.consumed = 0L;
                state.limit = configuredLimit;
            }
            long limit = state.limit;
            if (state.consumed < limit) {
                state.consumed++;
                decision = QuotaDecision.permit(tenant, windowIndex, start, end,
                        state.consumed, limit);
            } else {
                decision = QuotaDecision.reject(tenant, RejectReason.QUOTA_EXCEEDED,
                        windowIndex, start, end, state.consumed, limit);
            }
        }
        log.info("quota decision input={{}}, now={}}, window={}}, basis=consumed {}/{}} in [{},{})",
                tenant, nowMillis, windowIndex, decision.consumed(), decision.limit(), start, end);
        return decision;
    }

    /**
     * 仅供测试/运维：查看某租户当前窗口的已用计数与是否还可放行，不产生计数，不触发窗口重置。
     */
    public QuotaDecision peek(String tenant, long nowMillis) {
        long windowMs = properties.getWindowMs();
        long windowIndex = Math.floorDiv(nowMillis, windowMs);
        long start = Math.multiplyExact(windowIndex, windowMs);
        long end = start + windowMs;
        long configuredLimit = properties.quotaOf(tenant);
        if (configuredLimit <= 0) {
            return QuotaDecision.reject(tenant, RejectReason.TENANT_NOT_CONFIGURED,
                    windowIndex, start, end, 0L, 0L);
        }
        WindowState state = states.get(tenant);
        long consumed = 0L;
        long limit = configuredLimit;
        if (state != null) {
            synchronized (state.lock) {
                if (state.windowIndex == windowIndex) {
                    consumed = state.consumed;
                    limit = state.limit;
                }
            }
        }
        return consumed < limit
                ? QuotaDecision.permit(tenant, windowIndex, start, end, consumed, limit)
                : QuotaDecision.reject(tenant, RejectReason.QUOTA_EXCEEDED,
                        windowIndex, start, end, consumed, limit);
    }

    /** 单个租户的窗口计数状态。 */
    private static final class WindowState {
        private final Object lock = new Object();
        private long windowIndex = Long.MIN_VALUE;
        private long consumed;
        private long limit;
    }
}
