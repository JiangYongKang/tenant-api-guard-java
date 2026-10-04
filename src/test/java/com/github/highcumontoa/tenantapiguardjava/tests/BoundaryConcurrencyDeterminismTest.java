package com.github.highcumontoa.tenantapiguardjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
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
import com.github.highcumontoa.tenantapiguardjava.model.WindowMode;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaService;

/**
 * 跨时间段交界的并发确定性：无论线程如何交错，同一批请求放过去的总数是确定的。
 */
class BoundaryConcurrencyDeterminismTest {

    private static final Logger log = LoggerFactory.getLogger(BoundaryConcurrencyDeterminismTest.class);

    private GuardProperties props(String tenant, long quota, WindowMode mode) {
        GuardProperties p = new GuardProperties();
        p.setWindowMs(1000);
        TenantConfig cfg = new TenantConfig();
        cfg.setQuota(quota);
        cfg.setWindowMode(mode);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        tenants.put(tenant, cfg);
        p.setTenants(tenants);
        return p;
    }

    private int runConcurrent(QuotaService service, String tenant, long[] nowPerThread) throws Exception {
        int threads = nowPerThread.length;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final long now = nowPerThread[i];
            pool.submit(() -> {
                try {
                    start.await();
                    QuotaDecision d = service.tryAcquire(tenant, now);
                    if (d.allowed()) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        org.junit.jupiter.api.Assertions.assertTrue(done.await(30, TimeUnit.SECONDS), "all threads must finish");
        pool.shutdown();
        return allowed.get();
    }

    @Test
    @DisplayName("FIXED 跨交界并发：一半请求压 999、一半压 1000，总数确定 = 两个窗口各放满配额")
    void fixedBoundaryConcurrentTotalIsDeterministic() throws Exception {
        int limit = 3;
        int perSide = 20;
        QuotaService service = new QuotaService(props("tF", limit, WindowMode.FIXED));
        long[] nows = new long[perSide * 2];
        for (int i = 0; i < perSide; i++) {
            nows[i] = 999;          // 窗口 [0,1000) 最后一毫秒
            nows[perSide + i] = 1000; // 窗口 [1000,2000) 第一毫秒
        }
        log.info("TEST input=threads:{} half@999 half@1000 quota:{} basis=barrier concurrent tryAcquire",
                nows.length, limit);
        int allowed = runConcurrent(service, "tF", nows);
        log.info("TEST result allowed:{} expected:{} basis=3 in window0 + 3 in window1", allowed, limit * 2);
        assertEquals(limit * 2, allowed, "两个窗口各恰好放行配额数，与交错顺序无关");

        // 重复多轮，确认结论稳定
        for (int round = 0; round < 5; round++) {
            QuotaService again = new QuotaService(props("tF", limit, WindowMode.FIXED));
            int againAllowed = runConcurrent(again, "tF", nows);
            assertEquals(limit * 2, againAllowed, "第 " + round + " 轮重放结果必须一致");
        }
    }

    @Test
    @DisplayName("SLIDING 跨交界并发：请求压在 999/1000 上，任意 1000ms 内放行总数确定 = 配额")
    void slidingBoundaryConcurrentTotalIsDeterministic() throws Exception {
        int limit = 2;
        int perSide = 20;
        QuotaService service = new QuotaService(props("tS", limit, WindowMode.SLIDING));
        long[] nows = new long[perSide * 2];
        for (int i = 0; i < perSide; i++) {
            nows[i] = 999;
            nows[perSide + i] = 1000;
        }
        log.info("TEST input=threads:{} half@999 half@1000 quota:{} sliding basis=any 1000ms interval <= quota",
                nows.length, limit);
        int allowed = runConcurrent(service, "tS", nows);
        // 999 与 1000 落在同一滑动区间 (0,1000]（也同在 (-1,999] 的延展内），
        // 无论交错顺序如何，放行总数恰好等于配额——交界处不允许双倍
        log.info("TEST result allowed:{} expected:{} basis=999 and 1000 share sliding interval", allowed, limit);
        assertEquals(limit, allowed, "滑动口径下交界处不得放行双倍，总数确定");

        for (int round = 0; round < 5; round++) {
            QuotaService again = new QuotaService(props("tS", limit, WindowMode.SLIDING));
            assertEquals(limit, runConcurrent(again, "tS", nows), "第 " + round + " 轮重放结果必须一致");
        }
    }
}
