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
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties.TenantConfig;
import com.github.highcumontoa.tenantapiguardjava.model.QuotaDecision;
import com.github.highcumontoa.tenantapiguardjava.model.RejectReason;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaService;

/**
 * 配额：并发超配额、窗口边界、租户隔离与未配置租户的确定性判定。
 */
class QuotaServiceConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaServiceConcurrencyTest.class);

    private GuardProperties props(Long quotaA, Long quotaB) {
        GuardProperties p = new GuardProperties();
        p.setWindowMs(1000);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        if (quotaA != null) {
            TenantConfig a = new TenantConfig();
            a.setQuota(quotaA);
            tenants.put("tA", a);
        }
        if (quotaB != null) {
            TenantConfig b = new TenantConfig();
            b.setQuota(quotaB);
            tenants.put("tB", b);
        }
        p.setTenants(tenants);
        return p;
    }

    @Test
    @DisplayName("并发超配额：50 个并发请求 / 配额 5，恰好放行 5 个，其余全部 QUOTA_EXCEEDED")
    void concurrentOverQuotaExactlyLimitPass() throws Exception {
        int threads = 50;
        int limit = 5;
        QuotaService service = new QuotaService(props((long) limit, null));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentHashMap<RejectReason, AtomicInteger> reasons = new ConcurrentHashMap<>();
        AtomicInteger allowed = new AtomicInteger();
        long now = 500; // 所有请求落在窗口 [0,1000)

        log.info("TEST input=threads:{} quota:{} window:{}ms now:{} basis=barrier then concurrent tryAcquire",
                threads, limit, 1000, now);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    QuotaDecision d = service.tryAcquire("tA", now);
                    if (d.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        reasons.computeIfAbsent(d.reason(), r -> new AtomicInteger()).incrementAndGet();
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

        log.info("TEST result allowed:{} rejectedByReason:{}", allowed.get(), reasons);
        assertEquals(limit, allowed.get(), "不得超额放行");
        assertEquals(threads - limit,
                reasons.getOrDefault(RejectReason.QUOTA_EXCEEDED, new AtomicInteger()).get(),
                "被拒请求必须全部携带稳定原因 QUOTA_EXCEEDED");

        QuotaDecision peek = service.peek("tA", now);
        log.info("TEST peek basis=consumed must equal limit, consumed:{} limit:{}", peek.consumed(), peek.limit());
        assertEquals(limit, peek.consumed(), "计数不得错乱");
        assertFalse(peek.allowed(), "窗口已满时 peek 必须判定拒绝");
    }

    @Test
    @DisplayName("窗口边界：窗口用尽后在边界时刻切换，新窗口重新计数、旧窗口不重计不漏计")
    void windowBoundaryRollsOverExactlyOnce() {
        int limit = 3;
        QuotaService service = new QuotaService(props((long) limit, null));

        for (long now : new long[]{0, 1, 999}) {
            QuotaDecision d = service.tryAcquire("tA", now);
            log.info("TEST input=tenant:tA now:{} basis=window[0,1000) allowed:{} consumed:{}",
                    now, d.allowed(), d.consumed());
            assertTrue(d.allowed(), "窗口内前 3 个请求应放行");
        }
        QuotaDecision atEdge = service.tryAcquire("tA", 999);
        log.info("TEST input=tenant:tA now:999 basis=window full consumed:{} limit:{} reason:{}",
                atEdge.consumed(), atEdge.limit(), atEdge.reason());
        assertFalse(atEdge.allowed());
        assertEquals(RejectReason.QUOTA_EXCEEDED, atEdge.reason());

        // 边界时刻 1000 属于新窗口 [1000,2000)，计数必须从 1 重新开始
        QuotaDecision firstOfNew = service.tryAcquire("tA", 1000);
        log.info("TEST input=tenant:tA now:1000 basis=new window[1000,2000) windowIndex:{} consumed:{}",
                firstOfNew.windowIndex(), firstOfNew.consumed());
        assertTrue(firstOfNew.allowed());
        assertEquals(1, firstOfNew.consumed());
        assertEquals(1, firstOfNew.windowIndex());

        QuotaDecision secondOfNew = service.tryAcquire("tA", 1500);
        assertTrue(secondOfNew.allowed());
        assertEquals(2, secondOfNew.consumed());

        // 新窗口同样在达到上限时拒绝，两个窗口各恰好放行 limit 个——切换不重计不漏计
        QuotaDecision thirdOfNew = service.tryAcquire("tA", 1999);
        assertTrue(thirdOfNew.allowed());
        assertEquals(3, thirdOfNew.consumed());
        QuotaDecision newFull = service.tryAcquire("tA", 1999);
        log.info("TEST input=tenant:tA now:1999 basis=new window full consumed:{} reason:{}",
                newFull.consumed(), newFull.reason());
        assertFalse(newFull.allowed());
        assertEquals(RejectReason.QUOTA_EXCEEDED, newFull.reason());
    }

    @Test
    @DisplayName("租户隔离：tA 用尽不影响 tB；未配置租户得到 TENANT_NOT_CONFIGURED")
    void tenantIsolationAndUnknownTenant() {
        QuotaService service = new QuotaService(props(1L, 100L));

        assertTrue(service.tryAcquire("tA", 10).allowed());
        QuotaDecision aFull = service.tryAcquire("tA", 10);
        log.info("TEST input=tenant:tA basis=quota 1 exhausted reason:{}", aFull.reason());
        assertFalse(aFull.allowed());
        assertEquals(RejectReason.QUOTA_EXCEEDED, aFull.reason());

        QuotaDecision bOk = service.tryAcquire("tB", 10);
        log.info("TEST input=tenant:tB basis=independent counter allowed:{} consumed:{} limit:{}",
                bOk.allowed(), bOk.consumed(), bOk.limit());
        assertTrue(bOk.allowed(), "其他租户不得被连累");
        assertEquals(1, bOk.consumed());

        QuotaDecision unknown = service.tryAcquire("tX", 10);
        log.info("TEST input=tenant:tX basis=no local quota config reason:{}", unknown.reason());
        assertFalse(unknown.allowed());
        assertEquals(RejectReason.TENANT_NOT_CONFIGURED, unknown.reason(),
                "未配置租户必须明确拒绝，不得静默放行");
    }
}
