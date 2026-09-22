package com.github.highcumontoa.tenantapiguardjava.tests.quota;

import com.github.highcumontoa.tenantapiguardjava.common.TimeSource;
import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaDecision;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发超配额场景:同一输入必须得到稳定结论——恰好放行 limit 个,其余拒绝且原因一致。
 */
class QuotaServiceConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaServiceConcurrencyTest.class);

    private GuardProperties properties(int windowMillis, int buckets, int defaultLimit) {
        GuardProperties p = new GuardProperties();
        p.getQuota().setWindowMillis(windowMillis);
        p.getQuota().setBucketsPerWindow(buckets);
        p.getQuota().setDefaultLimit(defaultLimit);
        return p;
    }

    @Test
    void concurrentRequestsNeverExceedLimit() throws Exception {
        int limit = 20;
        int threads = 200;
        GuardProperties props = properties(60_000, 6, limit);
        QuotaService service = new QuotaService(props, new TimeSource.SystemTimeSource());

        ExecutorService pool = Executors.newFixedThreadPool(32);
        AtomicLong allowed = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        try {
            List<Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int seq = i;
                tasks.add(() -> {
                    QuotaDecision d = service.tryAcquire("tenant-concurrent");
                    log.info("input tenant=tenant-concurrent seq={} -> allowed={} used={} limit={} retryAfterMillis={}",
                            seq, d.allowed(), d.used(), d.limit(), d.retryAfterMillis());
                    if (d.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                        assertEquals("tenant-concurrent", d.tenant());
                        assertFalse(d.allowed());
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        log.info("summary total={} allowed={} rejected={} limit={}",
                threads, allowed.get(), rejected.get(), limit);
        assertEquals(limit, allowed.get(), "并发下不得超额放行");
        assertEquals(threads - limit, rejected.get(), "其余请求必须被拒绝");
    }

    @Test
    void rejectionReasonIsStableAndDistinguishable() {
        GuardProperties props = properties(60_000, 1, 2);
        QuotaService service = new QuotaService(props, new TimeSource.SystemTimeSource());

        QuotaDecision first = service.tryAcquire("tenant-small");
        QuotaDecision second = service.tryAcquire("tenant-small");
        QuotaDecision third = service.tryAcquire("tenant-small");

        log.info("input tenant=tenant-small requests=1/2/3 -> allowed={}/{}/{} used={}/{}/{}",
                first.allowed(), second.allowed(), third.allowed(),
                first.used(), second.used(), third.used());

        assertTrue(first.allowed());
        assertTrue(second.allowed());
        assertFalse(third.allowed());
        assertEquals(2, third.limit());
        assertTrue(third.retryAfterMillis() > 0, "拒绝时必须给出确定的重试建议时间");
    }
}
