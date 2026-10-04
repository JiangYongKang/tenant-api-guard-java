package com.github.highcumontoa.tenantapiguardjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties.TenantConfig;
import com.github.highcumontoa.tenantapiguardjava.model.QuotaDecision;
import com.github.highcumontoa.tenantapiguardjava.model.RejectReason;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaMode;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaService;

/**
 * 滑动口径跨交界并发确定性、运行期口径/配额变更的租户隔离。
 */
class SlidingQuotaConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(SlidingQuotaConcurrencyTest.class);

    private GuardProperties slidingProps(int limit, long windowMs) {
        GuardProperties p = new GuardProperties();
        p.setWindowMs(windowMs);
        p.setDefaultQuota(0L);
        TenantConfig c = new TenantConfig();
        c.setQuota((long) limit);
        c.setQuotaMode(QuotaMode.SLIDING);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        tenants.put("s", c);
        p.setTenants(tenants);
        return p;
    }

    @RepeatedTest(20)
    @DisplayName("跨交界并发确定性：20 线程各压在 999 或 1000，滑动口径放行总数恒等于配额")
    void concurrentAcrossBoundaryAlwaysExactlyLimit() throws Exception {
        int threads = 20;
        int limit = 5;
        QuotaService service = new QuotaService(slidingProps(limit, 1000));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        long[] times = {999L, 1000L}; // 一半压在交界前一刻，一半压在交界后一刻

        for (int i = 0; i < threads; i++) {
            long now = times[i & 1];
            pool.submit(() -> {
                try {
                    start.await();
                    QuotaDecision d = service.tryAcquire("s", now);
                    if (d.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                        assertEquals(RejectReason.QUOTA_EXCEEDED, d.reason());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("TEST sliding concurrent boundary allowed:{} rejected:{} (expect allowed={})",
                allowed.get(), rejected.get(), limit);
        // 999 与 1000 全部落在同一个长度 1000ms 的滑动区间 (0,1000] 内：无论调度先后，
        // 这批请求放行总数必须恰好为 limit，不会因为固定窗口交界而多放
        assertEquals(limit, allowed.get(), "跨交界并发不得超额，放行总数必须确定");
        assertEquals(threads - limit, rejected.get());
    }

    @Test
    @DisplayName("运行期配额变更只影响本租户：调整一个租户配额不串到另一个租户")
    void runtimeQuotaChangeIsPerTenant() {
        GuardProperties p = slidingProps(2, 1000);
        TenantConfig b = new TenantConfig();
        b.setQuota(100L);
        b.setQuotaMode(QuotaMode.FIXED);
        p.getTenants().put("b", b);
        QuotaService service = new QuotaService(p);

        assertTrue(service.tryAcquire("s", 10).allowed());
        assertTrue(service.tryAcquire("s", 20).allowed());
        assertFalse(service.tryAcquire("s", 30).allowed());

        // 调大 s 的配额（滑动口径即时生效）
        p.getTenants().get("s").setQuota(4L);
        assertTrue(service.tryAcquire("s", 40).allowed());

        // b 完全不受 s 调整影响
        for (int i = 0; i < 100; i++) {
            assertTrue(service.tryAcquire("b", 50 + i).allowed(), "b 的配额保持自己的 100");
        }
        QuotaDecision bFull = service.tryAcquire("b", 200);
        assertFalse(bFull.allowed());
        assertEquals(RejectReason.QUOTA_EXCEEDED, bFull.reason());
        log.info("TEST runtime change basis=s resized to 4, b still capped at own 100");
    }

    @Test
    @DisplayName("运行期口径切换：该租户从空状态按新口径重计，其他租户继续各自口径")
    void runtimeModeSwitchSealsOldState() {
        GuardProperties p = new GuardProperties();
        p.setWindowMs(1000);
        p.setDefaultQuota(0L);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        TenantConfig a = new TenantConfig();
        a.setQuota(3L);
        a.setQuotaMode(QuotaMode.FIXED);
        tenants.put("a", a);
        TenantConfig b = new TenantConfig();
        b.setQuota(3L);
        b.setQuotaMode(QuotaMode.FIXED);
        tenants.put("b", b);
        p.setTenants(tenants);
        QuotaService service = new QuotaService(p);

        // a 在旧固定窗口 [0,1000) 用满 3
        for (long t : new long[]{600, 700, 900}) {
            assertTrue(service.tryAcquire("a", t).allowed());
        }
        // 切到滑动口径
        a.setQuotaMode(QuotaMode.SLIDING);
        // 切换后首笔在 1000：若沿用旧固定状态会按新窗口放行；新口径空状态也放行，
        // 但第二笔必须体现滑动口径——1100 时存活 1000 一枚，仍可放；关键是 1200 前的连续性。
        assertTrue(service.tryAcquire("a", 1000).allowed(), "口径切换后以空状态起算");
        assertTrue(service.tryAcquire("a", 1100).allowed());
        assertTrue(service.tryAcquire("a", 1200).allowed());
        QuotaDecision fourth = service.tryAcquire("a", 1300);
        assertFalse(fourth.allowed(), "滑动口径下连续 1000ms 内不得超过 3");
        assertEquals(QuotaMode.SLIDING, service.inspect("a", 1300).mode());

        // b 完全不受 a 口径切换影响，仍是固定口径
        assertEquals(QuotaMode.FIXED, service.inspect("b", 1000).mode());
        for (long t : new long[]{600, 700, 900}) {
            assertTrue(service.tryAcquire("b", t).allowed());
        }
        assertTrue(service.tryAcquire("b", 1000).allowed(), "b 仍按固定口径在新窗口重新计数");
        log.info("TEST mode switch basis=a switched FIXED->SLIDING and sealed old state, b unaffected");
    }
}
