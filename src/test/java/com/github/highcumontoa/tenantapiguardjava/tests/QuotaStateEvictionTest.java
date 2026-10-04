package com.github.highcumontoa.tenantapiguardjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties.TenantConfig;
import com.github.highcumontoa.tenantapiguardjava.model.QuotaDecision;
import com.github.highcumontoa.tenantapiguardjava.model.QuotaUsage;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaMode;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaService;

/**
 * 不活跃租户运行时状态回收：回收条件、活跃租户不受影响、回收后状态可重建。
 */
class QuotaStateEvictionTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaStateEvictionTest.class);

    private GuardProperties props(QuotaMode mode, long limit, long windowMs) {
        GuardProperties p = new GuardProperties();
        p.setWindowMs(windowMs);
        p.setDefaultQuota(0L);
        TenantConfig c = new TenantConfig();
        c.setQuota(limit);
        c.setQuotaMode(mode);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        tenants.put("idle", c);
        TenantConfig active = new TenantConfig();
        active.setQuota(limit);
        active.setQuotaMode(mode);
        tenants.put("active", active);
        p.setTenants(tenants);
        return p;
    }

    @Test
    @DisplayName("固定口径：空闲且当前窗口已整体过去的状态可被回收；活跃窗口内的状态保留")
    void fixedWindowIdleStateEvictedActiveKept() {
        QuotaService service = new QuotaService(props(QuotaMode.FIXED, 3, 1000));

        // idle 在窗口 [0,1000) 用掉 2 笔
        assertTrue(service.tryAcquire("idle", 100).allowed());
        assertTrue(service.tryAcquire("idle", 200).allowed());
        // active 很早也有计数，但在回收扫描前一刻仍在活动
        assertTrue(service.tryAcquire("active", 100).allowed());
        assertTrue(service.tryAcquire("active", 99_999).allowed());

        // 时间推到很久以后：idle 空闲超阈值，且其计数所在窗口已整体过去 → 回收；
        // active 刚刚活跃（lastAccess=99999）必须保留
        long now = 100_000;
        int removed = service.evictIdle(now, 60_000);
        log.info("TEST evict fixed input=now:{} removed:{}", now, removed);
        assertEquals(1, removed, "只回收空闲租户，活跃租户保留");

        // 回收后 idle 以空状态重建，判定从 0 开始（旧计数所属窗口早已过去，语义安全）
        QuotaUsage u = service.inspect("idle", now);
        assertEquals(0, u.consumed());
        assertEquals(3, u.remaining());
        assertTrue(u.allowed());
        assertTrue(service.tryAcquire("idle", now).allowed());

        // active 状态完好：99999 落在窗口 [99000,100000)，该窗口已计 2；
        // 100000 属于新窗口，从 1 起算，说明它保留的是自己的真实状态而非受 idle 影响
        QuotaDecision activeNewWindow = service.tryAcquire("active", now);
        assertTrue(activeNewWindow.allowed());
        assertEquals(1, activeNewWindow.consumed());

        int removedAgain = service.evictIdle(now, 60_000);
        assertEquals(0, removedAgain, "刚刚活跃的租户不回收");
    }

    @Test
    @DisplayName("固定口径：当前窗口内有计数但长时间无请求的状态不回收，避免回收后放宽判定")
    void fixedWindowLiveCountNotEvictedEvenWhenIdle() {
        GuardProperties p = new GuardProperties();
        p.setWindowMs(1000);
        p.setDefaultQuota(0L);
        TenantConfig c = new TenantConfig();
        c.setQuota(5L);
        c.setQuotaMode(QuotaMode.FIXED);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        tenants.put("t", c);
        p.setTenants(tenants);
        QuotaService service = new QuotaService(p);

        // 在当前窗口 [100000,101000) 内用掉 2 笔
        assertTrue(service.tryAcquire("t", 100_000).allowed());
        assertTrue(service.tryAcquire("t", 100_100).allowed());
        // 空闲 60s 但时间仍处于同一窗口（窗口是 floor 对齐的，100_200 仍在 [100000,101000)）
        int removed = service.evictIdle(100_200, 100);
        assertEquals(0, removed, "当前窗口有存活计数，回收会放宽判定，必须保留");
        // 状态还在：再来请求应从 consumed=2 继续
        QuotaDecision d = service.tryAcquire("t", 100_300);
        assertTrue(d.allowed());
        assertEquals(3, d.consumed());
        log.info("TEST evict fixed live basis=count preserved consumed:{}", d.consumed());

        // 窗口过去后即可安全回收
        assertEquals(1, service.evictIdle(101_000, 100));
    }

    @Test
    @DisplayName("滑动口径：仍有存活时间戳的状态不回收；全部滑出且空闲后回收，回收不放宽判定")
    void slidingStateEvictedOnlyAfterStampsExpire() {
        QuotaService service = new QuotaService(props(QuotaMode.SLIDING, 3, 1000));

        assertTrue(service.tryAcquire("idle", 1_000).allowed());
        assertTrue(service.tryAcquire("idle", 1_100).allowed());

        // 空闲 5s：时间戳全部滑出任何连续窗口 → 可安全回收
        int removed = service.evictIdle(10_000, 5_000);
        log.info("TEST evict sliding input=now:10000 removed:{}", removed);
        assertEquals(1, removed);
        assertEquals(0, service.inspect("idle", 10_000).consumed());

        // 反例：空闲但时间戳仍存活，不得回收
        assertTrue(service.tryAcquire("idle", 20_000).allowed());
        assertTrue(service.tryAcquire("idle", 20_500).allowed());
        int kept = service.evictIdle(20_600, 100);
        assertEquals(0, kept, "滑动区间内仍有存活计数，回收会放宽判定");
        // 20000/20500 仍在 (19900,20900] 内：第 3 笔 20900 放行，第 4 笔 20901 拒绝
        assertTrue(service.tryAcquire("idle", 20_900).allowed());
        assertFalse(service.tryAcquire("idle", 20_901).allowed(), "未被回收，计数连续准确");
        // 等时间戳全部滑出后允许回收（最晚一枚 20900 在 21900 才滑出）
        assertEquals(0, service.evictIdle(21_600, 100), "21600 时 20900 仍存活，暂不回收");
        assertEquals(1, service.evictIdle(21_901, 100), "全部滑出后允许回收");
        assertTrue(service.tryAcquire("idle", 21_901).allowed(), "回收后从空状态重新计数");
    }

    @Test
    @DisplayName("回收不影响其他活跃租户的判定结果")
    void evictionDoesNotDisturbOtherTenants() {
        QuotaService service = new QuotaService(props(QuotaMode.FIXED, 2, 1000));
        // idle 用 1 笔后长期沉默
        assertTrue(service.tryAcquire("idle", 0).allowed());
        // active 在扫描前一刻仍在活动（落在窗口 [99000,100000)，配额 2 已用满）
        assertTrue(service.tryAcquire("active", 99_000).allowed());
        assertTrue(service.tryAcquire("active", 99_100).allowed());

        int removed = service.evictIdle(100_000, 5_000);
        assertEquals(1, removed);

        // active 状态完好：其所在窗口 [99000,100000) 已用 2，在该窗口内仍准确受限
        QuotaDecision full = service.tryAcquire("active", 99_200);
        assertFalse(full.allowed());
        assertEquals(2, full.consumed());
        log.info("TEST evict isolation basis=active decision intact consumed:{}", full.consumed());
    }
}
