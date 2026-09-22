package com.github.highcumontoa.tenantapiguardjava.quota;

import com.github.highcumontoa.tenantapiguardjava.common.TimeSource;
import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按租户维度的滑动窗口配额服务。
 *
 * <p>模型:窗口长度 W 被切分为 B 个等长子桶,桶长 d = floor(W/B),有效窗口为 d*B。
 * 每个租户独立持有一个以<b>完整桶序号</b>(floor(now/d))为键的计数器映射。
 * 判定时只统计与真实时间窗口 (now - d*B, now] 仍有重叠的桶,即序号满足
 * {@code currentBucket - B <= b <= currentBucket}(最多 B+1 个桶)。
 *
 * <p>该存活判定偏保守:在窗口边界处可能最多少拒绝一次(把一个正在滚出、仅余少量
 * 重叠的旧桶仍计入),但<b>永不超额放行</b>。判定与自增在租户各自的监视器锁内
 * 原子完成,租户之间互不阻塞;以完整序号为键不存在环形缓冲别名冲突,窗口切换
 * 不会重复计数或漏计数。
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final GuardProperties properties;
    private final TimeSource timeSource;
    private final int buckets;
    private final long bucketMillis;
    private final ConcurrentHashMap<String, SlidingWindow> windows = new ConcurrentHashMap<>();

    public QuotaService(GuardProperties properties, TimeSource timeSource) {
        this.properties = properties;
        this.timeSource = timeSource;
        int b = Math.max(1, properties.getQuota().getBucketsPerWindow());
        long w = Math.max(1L, properties.getQuota().getWindowMillis());
        this.buckets = b;
        this.bucketMillis = Math.max(1L, w / b);
    }

    /**
     * 尝试为租户消耗一个配额。判定与计数在同一原子步骤内完成。
     */
    public QuotaDecision tryAcquire(String tenant) {
        int limit = limitFor(tenant);
        SlidingWindow window = windows.computeIfAbsent(tenant, t -> new SlidingWindow());
        QuotaDecision decision = window.tryAcquire(timeSource.nowMillis(), limit, tenant);
        log.info("quota decision tenant={} allowed={} limit={} used={} windowMillis={} bucketMillis={} retryAfterMillis={}",
                tenant, decision.allowed(), decision.limit(), decision.used(),
                bucketMillis * buckets, bucketMillis, decision.retryAfterMillis());
        return decision;
    }

    private int limitFor(String tenant) {
        Integer overridden = properties.getQuota().getTenantLimits().get(tenant);
        return overridden != null ? overridden : properties.getQuota().getDefaultLimit();
    }

    /** 单个租户的滑动窗口;所有方法在自身监视器上串行,租户之间互不阻塞。 */
    final class SlidingWindow {

        private final TreeMap<Long, Long> bucketCounts = new TreeMap<>();

        synchronized QuotaDecision tryAcquire(long now, int limit, String tenant) {
            long d = bucketMillis;
            long currentBucket = Math.floorDiv(now, d);
            long oldestKept = currentBucket - buckets; // 严格小于它的桶与窗口不再重叠,丢弃

            bucketCounts.headMap(oldestKept, false).clear();

            int used = 0;
            for (long c : bucketCounts.tailMap(oldestKept, true).values()) {
                used += (int) Math.min((long) Integer.MAX_VALUE, c);
            }

            if (used >= limit) {
                long retryAfter = d - Math.floorMod(now, d);
                return QuotaDecision.reject(tenant, limit, used, retryAfter);
            }

            bucketCounts.merge(currentBucket, 1L, Long::sum);
            return QuotaDecision.allow(tenant, limit, used + 1);
        }
    }
}
