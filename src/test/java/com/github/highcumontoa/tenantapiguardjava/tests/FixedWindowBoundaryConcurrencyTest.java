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
 * 固定时间段口径（FIXED，默认口径）在时间交界并发交错下的确定性：
 * 交界前后两个时间段各算各的，每个时间段放行数都不超过该租户配额，
 * 同一批输入反复跑多轮，放行总数必须是同一个确定值，与线程调度先后无关。
 */
class FixedWindowBoundaryConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(FixedWindowBoundaryConcurrencyTest.class);

    private GuardProperties fixedProps(String tenant, long limit) {
        GuardProperties p = new GuardProperties();
        p.setWindowMs(1000);
        p.setDefaultQuota(0L);
        TenantConfig c = new TenantConfig();
        c.setQuota(limit);
        c.setQuotaMode(QuotaMode.FIXED);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        tenants.put(tenant, c);
        p.setTenants(tenants);
        return p;
    }

    /**
     * 单线程确定性复现：请求在 999/1000 之间来回交错（模拟两边请求谁先谁后都可能的排列）。
     * 旧实现每次“跨回”另一个窗口都会把该窗口计数清零，导致每段放行远超配额。
     */
    @Test
    @DisplayName("交界来回交错：每个时间段各计各的，两段各自不超过配额，总数确定")
    void alternatingAcrossBoundaryEachWindowCappedIndependently() {
        int limit = 3;
        int requests = 12;
        QuotaService service = new QuotaService(fixedProps("t", limit));
        Map<Long, AtomicInteger> allowedByWindow = new ConcurrentHashMap<>();
        int allowed = 0;

        for (int i = 0; i < requests; i++) {
            long now = (i & 1) == 0 ? 999L : 1000L;
            QuotaDecision d = service.tryAcquire("t", now);
            log.info("TEST fixed-alternate input=seq:{} now:{} window:{} allowed:{} consumed:{}",
                    i, now, d.windowIndex(), d.allowed(), d.consumed());
            if (d.allowed()) {
                allowed++;
                allowedByWindow.computeIfAbsent(d.windowIndex(), k -> new AtomicInteger()).incrementAndGet();
            } else {
                assertEquals(RejectReason.QUOTA_EXCEEDED, d.reason());
            }
        }

        log.info("TEST fixed-alternate result totalAllowed:{} allowedByWindow:{} (expect 6 and {{}}=3,{}=3)",
                allowed, allowedByWindow, 0L, 1L);
        assertEquals(2 * limit, allowed, "两段各放 limit，总数必须确定为 2*limit");
        assertEquals(limit, allowedByWindow.get(0L).get(), "交界前段 [0,1000) 不得超过配额");
        assertEquals(limit, allowedByWindow.get(1L).get(), "交界后段 [1000,2000) 不得超过配额");
    }

    /**
     * 并发复现（压测口径）：线程数远大于配额，一半压在 999、一半压在 1000，
     * 同一批输入重复多轮，放行总数恒为 2*limit，且每段恰好 limit。
     */
    @RepeatedTest(20)
    @DisplayName("并发压交界：40 线程 / 配额 5，20 个压 999、20 个压 1000，放行总数恒为 10")
    void concurrentAcrossBoundaryTotalIsDeterministic() throws Exception {
        int threads = 40;
        int limit = 5;
        QuotaService service = new QuotaService(fixedProps("t", limit));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ConcurrentHashMap<Long, AtomicInteger> allowedByWindow = new ConcurrentHashMap<>();

        log.info("TEST fixed-boundary input=threads:{} quota:{} split=20x999,20x1000 basis=barrier then concurrent",
                threads, limit);
        for (int i = 0; i < threads; i++) {
            long now = i < threads / 2 ? 999L : 1000L;
            pool.submit(() -> {
                try {
                    start.await();
                    QuotaDecision d = service.tryAcquire("t", now);
                    if (d.allowed()) {
                        allowed.incrementAndGet();
                        allowedByWindow.computeIfAbsent(d.windowIndex(), k -> new AtomicInteger()).incrementAndGet();
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
        assertTrue(done.await(30, TimeUnit.SECONDS), "all threads must finish");
        pool.shutdown();

        log.info("TEST fixed-boundary result allowed:{} rejected:{} allowedByWindow:{} (expect allowed=10, w0=5, w1=5)",
                allowed.get(), rejected.get(), allowedByWindow);
        assertEquals(2 * limit, allowed.get(), "交界并发放行总数必须确定为 2*limit，与调度先后无关");
        assertEquals(limit, allowedByWindow.get(0L).get(), "前段 [0,1000) 放行不得超过配额");
        assertEquals(limit, allowedByWindow.get(1L).get(), "后段 [1000,2000) 放行不得超过配额");
        assertEquals(threads - 2 * limit, rejected.get());
    }

    /**
     * 两种口径并存且同时压交界：FIXED 租户两段各放 limit（合计 2*limit），
     * SLIDING 租户 999/1000 同属一个连续区间（合计恰好 limit）；两租户互不影响。
     */
    @RepeatedTest(10)
    @DisplayName("两种口径并存并发压交界：FIXED=2*limit、SLIDING=limit，租户互不串味")
    void fixedAndSlidingTenantsConcurrentAtBoundary() throws Exception {
        int threadsPerTenant = 40;
        int limit = 5;
        GuardProperties p = new GuardProperties();
        p.setWindowMs(1000);
        p.setDefaultQuota(0L);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        TenantConfig f = new TenantConfig();
        f.setQuota((long) limit);
        f.setQuotaMode(QuotaMode.FIXED);
        tenants.put("f", f);
        TenantConfig s = new TenantConfig();
        s.setQuota((long) limit);
        s.setQuotaMode(QuotaMode.SLIDING);
        tenants.put("s", s);
        p.setTenants(tenants);
        QuotaService service = new QuotaService(p);

        int total = threadsPerTenant * 2;
        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        AtomicInteger fixedAllowed = new AtomicInteger();
        AtomicInteger slideAllowed = new AtomicInteger();

        for (int i = 0; i < total; i++) {
            String tenant = i < threadsPerTenant ? "f" : "s";
            long now = (i & 1) == 0 ? 999L : 1000L;
            pool.submit(() -> {
                try {
                    start.await();
                    if (service.tryAcquire(tenant, now).allowed()) {
                        if ("f".equals(tenant)) {
                            fixedAllowed.incrementAndGet();
                        } else {
                            slideAllowed.incrementAndGet();
                        }
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

        log.info("TEST coexist-boundary result fixedAllowed:{} slidingAllowed:{} (expect {} and {})",
                fixedAllowed.get(), slideAllowed.get(), 2 * limit, limit);
        assertEquals(2 * limit, fixedAllowed.get(), "FIXED 两段各不超配额，合计 2*limit");
        assertEquals(limit, slideAllowed.get(), "SLIDING 999/1000 同区间，合计恰好 limit");
    }

    @Test
    @DisplayName("运行期调配额：当前窗口不回溯，下一起按新配额判定，旧状态残留不多放不少放")
    void runtimeQuotaChangeAppliesAtNextWindowWithoutResidue() {
        TenantConfig c = new TenantConfig();
        c.setQuota(3L);
        c.setQuotaMode(QuotaMode.FIXED);
        GuardProperties p = new GuardProperties();
        p.setWindowMs(1000);
        p.setDefaultQuota(0L);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        tenants.put("t", c);
        p.setTenants(tenants);
        QuotaService service = new QuotaService(p);

        for (long t : new long[]{0, 100, 200}) {
            assertTrue(service.tryAcquire("t", t).allowed());
        }
        // 窗口中段调大到 6：当前窗口不回溯，999 仍然满
        c.setQuota(6L);
        QuotaDecision stillFull = service.tryAcquire("t", 999);
        log.info("TEST resize input=now:999 newLimit:6 basis=current window not retroactive allowed:{} consumed:{}",
                stillFull.allowed(), stillFull.consumed());
        assertFalse(stillFull.allowed(), "当前窗口已用配额不被配置变更回溯改写");

        // 新窗口 [1000,2000) 按新配额 6 判定：恰好 6 个放行，第 7 个拒绝
        for (int i = 0; i < 6; i++) {
            long t = 1000 + i * 10L;
            QuotaDecision d = service.tryAcquire("t", t);
            assertTrue(d.allowed(), "新窗口按调大后的配额 6 放行");
            assertEquals(i + 1, d.consumed());
        }
        QuotaDecision overSix = service.tryAcquire("t", 1900);
        assertFalse(overSix.allowed());
        assertEquals(6, overSix.consumed());

        // 调小到 2：下一个窗口 [2000,3000) 恰好 2 个放行，第 3 个拒绝——无旧窗口计数残留
        c.setQuota(2L);
        assertTrue(service.tryAcquire("t", 2000).allowed());
        assertTrue(service.tryAcquire("t", 2100).allowed());
        QuotaDecision third = service.tryAcquire("t", 2200);
        log.info("TEST resize-down input=now:2200 newLimit:2 allowed:{} consumed:{}",
                third.allowed(), third.consumed());
        assertFalse(third.allowed(), "调小后新窗口严格按 2 判定，旧状态不残留");
        assertEquals(2, third.consumed());
        assertEquals(RejectReason.QUOTA_EXCEEDED, third.reason());
    }

    @Test
    @DisplayName("老租户不显式配置口径：默认仍是 FIXED，交界两段各算各的")
    void unconfiguredModeDefaultsToFixed() {
        // 只给默认配额、不配置任何 quota-mode
        GuardProperties p = new GuardProperties();
        p.setWindowMs(1000);
        p.setDefaultQuota(2L);
        p.setTenants(new LinkedHashMap<>());
        QuotaService service = new QuotaService(p);

        assertEquals(QuotaMode.FIXED, service.inspect("legacy", 0).mode());
        Map<Long, AtomicInteger> allowedByWindow = new ConcurrentHashMap<>();
        for (long now : new long[]{999, 999, 999, 1000, 1000, 1000}) {
            QuotaDecision d = service.tryAcquire("legacy", now);
            if (d.allowed()) {
                allowedByWindow.computeIfAbsent(d.windowIndex(), k -> new AtomicInteger()).incrementAndGet();
            }
        }
        log.info("TEST legacy-default result allowedByWindow:{} (expect w0=2,w1=2)", allowedByWindow);
        assertEquals(2, allowedByWindow.get(0L).get());
        assertEquals(2, allowedByWindow.get(1L).get());
    }
}
