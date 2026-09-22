package com.github.highcumontoa.tenantapiguardjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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
import com.github.highcumontoa.tenantapiguardjava.bulkhead.TenantBulkheadService;
import com.github.highcumontoa.tenantapiguardjava.model.BulkheadDecision;
import com.github.highcumontoa.tenantapiguardjava.model.RejectReason;

/**
 * 舱壁：按租户并发隔离、突发流量确定拒绝、释放回收与未配置租户处理。
 */
class BulkheadTest {

    private static final Logger log = LoggerFactory.getLogger(BulkheadTest.class);

    private GuardProperties props(Integer maxA, Integer maxB, int global) {
        GuardProperties p = new GuardProperties();
        p.setGlobalMaxConcurrent(global);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        if (maxA != null) {
            TenantConfig a = new TenantConfig();
            a.setMaxConcurrent(maxA);
            tenants.put("tA", a);
        }
        if (maxB != null) {
            TenantConfig b = new TenantConfig();
            b.setMaxConcurrent(maxB);
            tenants.put("tB", b);
        }
        p.setTenants(tenants);
        return p;
    }

    @Test
    @DisplayName("舱壁上限：占满 3 槽后第 4 个请求确定拒绝 BULKHEAD_FULL，释放后可再进")
    void fullBulkheadRejectsDeterministically() {
        TenantBulkheadService bh = new TenantBulkheadService(props(3, null, 100));
        for (int i = 1; i <= 3; i++) {
            BulkheadDecision d = bh.tryAcquire("tA");
            log.info("TEST input=tenant:tA acquire#{} basis=slots {}/{} acquired:{}",
                    i, d.inUse(), d.maxPermits(), d.acquired());
            assertTrue(d.acquired());
        }
        BulkheadDecision rejected = bh.tryAcquire("tA");
        log.info("TEST input=tenant:tA acquire#4 basis=slots {}/{} decision reason:{}",
                rejected.inUse(), rejected.maxPermits(), rejected.reason());
        assertFalse(rejected.acquired());
        assertEquals(RejectReason.BULKHEAD_FULL, rejected.reason());
        assertEquals(429, rejected.reason().httpStatus());
        assertEquals(3, rejected.inUse());

        bh.release("tA");
        BulkheadDecision again = bh.tryAcquire("tA");
        log.info("TEST input=tenant:tA after release basis=slots {}/{} acquired:{}",
                again.inUse(), again.maxPermits(), again.acquired());
        assertTrue(again.acquired(), "释放后槽位必须被回收");
        assertEquals(3, again.inUse());
    }

    @Test
    @DisplayName("租户隔离：tA 突发流量占满自己的舱壁，tB 的判定完全不受影响")
    void tenantBurstDoesNotAffectOthers() {
        TenantBulkheadService bh = new TenantBulkheadService(props(2, 5, 1000));
        assertTrue(bh.tryAcquire("tA").acquired());
        assertTrue(bh.tryAcquire("tA").acquired());
        BulkheadDecision aBlocked = bh.tryAcquire("tA");
        assertFalse(aBlocked.acquired());
        assertEquals(RejectReason.BULKHEAD_FULL, aBlocked.reason());

        for (int i = 1; i <= 5; i++) {
            BulkheadDecision d = bh.tryAcquire("tB");
            log.info("TEST input=tenant:tB acquire#{} while tA is full basis=independent bulkhead acquired:{} slots:{}/{}",
                    i, d.acquired(), d.inUse(), d.maxPermits());
            assertTrue(d.acquired(), "其他租户正常判定，不被 tA 突发连累");
        }
        BulkheadDecision bFull = bh.tryAcquire("tB");
        assertFalse(bFull.acquired());
        assertEquals(RejectReason.BULKHEAD_FULL, bFull.reason());

        BulkheadDecision unknown = bh.tryAcquire("tX");
        log.info("TEST input=tenant:tX basis=no local concurrency config reason:{}", unknown.reason());
        assertFalse(unknown.acquired());
        assertEquals(RejectReason.TENANT_NOT_CONFIGURED, unknown.reason());
    }

    @Test
    @DisplayName("并发占用：20 个并发请求 / 3 槽，恰好 3 个成功、17 个 BULKHEAD_FULL，且全部成对释放")
    void concurrentAcquireRespectsLimit() throws Exception {
        int threads = 20;
        TenantBulkheadService bh = new TenantBulkheadService(props(3, null, 1000));
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ConcurrentHashMap<RejectReason, AtomicInteger> reasons = new ConcurrentHashMap<>();
        AtomicInteger acquired = new AtomicInteger();

        log.info("TEST input=threads:{} tenant:tA maxSlots:3 basis=barrier then concurrent tryAcquire", threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    BulkheadDecision d = bh.tryAcquire("tA");
                    if (d.acquired()) {
                        acquired.incrementAndGet();
                        Thread.sleep(50);
                        bh.release("tA");
                    } else {
                        reasons.computeIfAbsent(d.reason(), r -> new AtomicInteger()).incrementAndGet();
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        log.info("TEST result acquired:{} rejected:{}", acquired.get(), reasons);
        assertEquals(3, acquired.get(), "同时占用不得超过舱壁上限");
        assertEquals(threads - 3,
                reasons.getOrDefault(RejectReason.BULKHEAD_FULL, new AtomicInteger()).get(),
                "未占用者必须得到稳定原因 BULKHEAD_FULL");

        // 全部释放后舱壁应恢复可用
        BulkheadDecision after = bh.tryAcquire("tA");
        assertTrue(after.acquired(), "成对释放后舱壁必须恢复可用");
        assertEquals(1, after.inUse());
    }
}
