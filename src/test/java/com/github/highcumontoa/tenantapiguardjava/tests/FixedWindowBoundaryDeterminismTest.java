package com.github.highcumontoa.tenantapiguardjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties.TenantConfig;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaMode;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaService;

/**
 * 业务：固定时间段口径（FIXED）在时间交界并发下的确定性回归。
 *
 * <p>背景缺陷：交界前后两批请求并发交错判定时，旧实现只有一个“当前窗口”计数槽，
 * 每来一笔不同窗口的请求就把计数清零重开，导致放行总数随线程调度飘忽、可远超
 * “每段各 L 个、合计 2L”的上限。本类用同一批输入反复多轮断言：</p>
 * <ul>
 *   <li>放行总数是一个确定值（每窗口配额 L、两窗口各 L ⇒ 恒为 2L），且每段各自不超过 L；</li>
 *   <li>结论与到达/判定顺序无关（串行打乱时间戳顺序结果相同）；</li>
 *   <li>FIXED 与 SLIDING 两种口径并存时各算各的、互不影响；</li>
 *   <li>运行期改配额后判定立即与新配置一致；不显式配口径的老租户默认仍是 FIXED。</li>
 * </ul>
 */
class FixedWindowBoundaryDeterminismTest {

    private static final Logger log = LoggerFactory.getLogger(FixedWindowBoundaryDeterminismTest.class);

    private static final long WINDOW_MS = 1000L;

    /** 构造：tenant -> (quota, mode)；default-quota-mode 保持 FIXED，默认配额为 0（默认拒绝）。 */
    private GuardProperties props(Map<String, long[]> quotas, Map<String, QuotaMode> modes) {
        GuardProperties p = new GuardProperties();
        p.setWindowMs(WINDOW_MS);
        p.setDefaultQuota(0L);
        p.setDefaultQuotaMode(QuotaMode.FIXED);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        quotas.forEach((t, qm) -> {
            TenantConfig c = new TenantConfig();
            c.setQuota(qm[0]);
            c.setQuotaMode(modes.get(t));
            tenants.put(t, c);
        });
        p.setTenants(tenants);
        return p;
    }

    /** 一批请求的判定汇总：总数、前窗（999）放行数、后窗（1000）放行数、拒绝数。 */
    private record BatchOutcome(int allowed, int allowedBefore, int allowedAfter, int rejected) {
    }

    /**
     * 同一批输入：{@code threads} 个线程（远大于配额），一半时间戳 999（窗口 [0,1000)）、
     * 一半 1000（窗口 [1000,2000)），栅栏同时释放并发判定。
     */
    private BatchOutcome runBoundaryBatch(QuotaService service, String tenant, int threads)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger before = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            long now = (i & 1) == 0 ? 999L : 1000L;
            pool.submit(() -> {
                try {
                    start.await();
                    var d = service.tryAcquire(tenant, now);
                    if (d.allowed()) {
                        allowed.incrementAndGet();
                        if (now == 999L) {
                            before.incrementAndGet();
                        } else {
                            after.incrementAndGet();
                        }
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "所有线程必须在预算内完成");
        pool.shutdown();
        return new BatchOutcome(allowed.get(), before.get(), after.get(), rejected.get());
    }

    @RepeatedTest(25)
    @DisplayName("FIXED 交界并发确定性：80 线程（>>配额 5）一半压 999 一半压 1000，"
            + "25 轮每轮都恰好放行 2*5=10，且前后两段各 5")
    void fixedBoundaryConcurrentTotalIsDeterministic() throws Exception {
        int threads = 80;
        int limit = 5;
        GuardProperties p = props(Map.of("fix", new long[]{limit}),
                Map.of("fix", QuotaMode.FIXED));
        QuotaService service = new QuotaService(p);

        log.info("TEST business=FIXED交界并发 case=重复多轮 input=tenant:fix threads:{} "
                        + "stamps:50x999+50x1000 window:{}ms quota:{} basis=barrier release",
                threads, WINDOW_MS, limit);
        BatchOutcome o = runBoundaryBatch(service, "fix", threads);

        log.info("TEST business=FIXED交界并发 result allowed:{} (before999:{}, after1000:{}) "
                        + "rejected:{} expected total:{} perSegment:<={}",
                o.allowed(), o.allowedBefore(), o.allowedAfter(), o.rejected(), 2 * limit, limit);
        assertEquals(limit, o.allowedBefore(), "交界前一段放行数必须恰好为配额");
        assertEquals(limit, o.allowedAfter(), "交界后一段放行数必须恰好为配额");
        assertEquals(2 * limit, o.allowed(), "两段合计放行总数必须确定为 2*配额");
        assertEquals(threads - 2 * limit, o.rejected());
    }

    @Test
    @DisplayName("FIXED 确定性与调度无关：跨 25 轮收集到的放行总数集合只有一个元素；"
            + "串行打乱同一批时间戳的顺序，分段计数与并发完全一致")
    void fixedResultIndependentOfArrivalOrder() throws Exception {
        int threads = 60;
        int limit = 4;
        Set<Integer> totalsAcrossRounds = new HashSet<>();
        Set<Integer> beforeAcrossRounds = new HashSet<>();
        for (int round = 0; round < 25; round++) {
            GuardProperties p = props(Map.of("fix", new long[]{limit}),
                    Map.of("fix", QuotaMode.FIXED));
            QuotaService service = new QuotaService(p);
            BatchOutcome o = runBoundaryBatch(service, "fix", threads);
            totalsAcrossRounds.add(o.allowed());
            beforeAcrossRounds.add(o.allowedBefore());
        }
        log.info("TEST business=FIXED跨轮确定性 input=25 rounds x {} threads quota:{} "
                        + "result distinctTotals:{} distinctBeforeSegment:{}",
                threads, limit, totalsAcrossRounds, beforeAcrossRounds);
        assertEquals(Set.of(2 * limit), totalsAcrossRounds, "同一批输入反复跑，总数不得飘忽");
        assertEquals(Set.of(limit), beforeAcrossRounds, "交界前段放行数同样必须恒定");

        // 串行、但把时间戳顺序故意打乱：结果必须与并发批次逐段一致
        List<Long> stamps = new ArrayList<>();
        for (int i = 0; i < threads / 2; i++) {
            stamps.add(999L);
            stamps.add(1000L);
        }
        Collections.shuffle(stamps);
        GuardProperties p = props(Map.of("fix", new long[]{limit}),
                Map.of("fix", QuotaMode.FIXED));
        QuotaService service = new QuotaService(p);
        int allowed = 0;
        int before = 0;
        for (long now : stamps) {
            if (service.tryAcquire("fix", now).allowed()) {
                allowed++;
                if (now == 999L) {
                    before++;
                }
            }
        }
        log.info("TEST business=FIXED乱序到达 input=shuffled {} stamps(999/1000) quota:{} "
                        + "result allowed:{} before:{} expected:{}/{}",
                threads, limit, allowed, before, 2 * limit, limit);
        assertEquals(2 * limit, allowed, "乱序串行的总数必须与并发一致，不允许反复清零多放");
        assertEquals(limit, before);
    }

    @Test
    @DisplayName("两种口径并存：同一交界批次下 FIXED 租户放 2L、SLIDING 租户放 L，互不串味")
    void fixedAndSlidingCoexistAcrossBoundary() throws Exception {
        int threads = 40;
        int fixedLimit = 3;
        int slidingLimit = 3;
        Map<String, long[]> quotas = new LinkedHashMap<>();
        quotas.put("fix", new long[]{fixedLimit});
        quotas.put("sli", new long[]{slidingLimit});
        Map<String, QuotaMode> modes = new LinkedHashMap<>();
        modes.put("fix", QuotaMode.FIXED);
        modes.put("sli", QuotaMode.SLIDING);
        QuotaService service = new QuotaService(props(quotas, modes));

        log.info("TEST business=双口径并存交界并发 input=tenants:fix(FIXED),sli(SLIDING) "
                + "threads:{} per-tenant stamps=999/1000 quota:{}", threads, fixedLimit);
        BatchOutcome fix = runBoundaryBatch(service, "fix", threads);
        BatchOutcome sli = runBoundaryBatch(service, "sli", threads);

        log.info("TEST business=双口径并存 result FIXED allowed:{} (before:{},after:{}) | "
                        + "SLIDING allowed:{} (before:{},after:{})",
                fix.allowed(), fix.allowedBefore(), fix.allowedAfter(),
                sli.allowed(), sli.allowedBefore(), sli.allowedAfter());
        assertEquals(2 * fixedLimit, fix.allowed(), "FIXED：两段各算各的，合计 2L");
        assertEquals(fixedLimit, fix.allowedBefore());
        assertEquals(fixedLimit, fix.allowedAfter());
        assertEquals(slidingLimit, sli.allowed(), "SLIDING：999 与 1000 同属一个连续周期，合计 L");
        assertEquals(QuotaMode.FIXED, service.inspect("fix", 1000).mode());
        assertEquals(QuotaMode.SLIDING, service.inspect("sli", 1000).mode());
    }

    @Test
    @DisplayName("运行期改配额（FIXED）：调大后同一窗口后续判定立即按新配额放开，调小立即收紧，"
            + "无旧状态残留导致的多放/少放")
    void runtimeQuotaChangeTakesEffectWithoutStaleState() {
        GuardProperties p = props(Map.of("fix", new long[]{3L}), Map.of("fix", QuotaMode.FIXED));
        TenantConfig cfg = p.getTenants().get("fix");
        QuotaService service = new QuotaService(p);

        for (long t : new long[]{900, 950, 998}) {
            assertTrue(service.tryAcquire("fix", t).allowed(), "窗口 [0,1000) 前 3 笔放行");
        }
        assertFalse(service.tryAcquire("fix", 999).allowed(), "旧配额 3 已满，第 4 笔拒绝");

        // 同一窗口内调大到 5：判定必须立即跟新配置，不能因为旧的“窗口已满”状态而继续少放
        cfg.setQuota(5L);
        var fourth = service.tryAcquire("fix", 999);
        log.info("TEST business=FIXED运行期调大 input=999 after quota 3->5 result allowed:{} consumed:{}",
                fourth.allowed(), fourth.consumed());
        assertTrue(fourth.allowed(), "调大后同窗口应立即能放");
        assertEquals(4, fourth.consumed());
        var fifth = service.tryAcquire("fix", 999);
        assertTrue(fifth.allowed());
        assertEquals(5, fifth.consumed());
        assertFalse(service.tryAcquire("fix", 999).allowed(), "新配额 5 立即成为新的硬上限");

        // 交界后的新窗口同样按 5 计，不能残留旧窗口状态
        var nextWindow = service.tryAcquire("fix", 1000);
        assertTrue(nextWindow.allowed());
        assertEquals(1, nextWindow.consumed());

        // 调小到 1：同一窗口已用 1，下一笔立即拒绝（不允许旧配额残留多放）
        cfg.setQuota(1L);
        var tightened = service.tryAcquire("fix", 1001);
        log.info("TEST business=FIXED运行期调小 input=1001 after quota 5->1 result allowed:{} consumed:{}",
                tightened.allowed(), tightened.consumed());
        assertFalse(tightened.allowed(), "调小后判定立即收紧");
        assertEquals(1, tightened.consumed());
    }

    @Test
    @DisplayName("老租户默认行为不变：不显式配置 quota-mode 时默认 FIXED，交界并发同样确定为 2L")
    void implicitDefaultModeRemainsFixedAndDeterministic() throws Exception {
        int threads = 40;
        int limit = 6;
        GuardProperties p = new GuardProperties();
        p.setWindowMs(WINDOW_MS);
        p.setDefaultQuota(limit);                 // 不显式设 default-quota-mode
        TenantConfig legacy = new TenantConfig();
        legacy.setQuota((long) limit);            // 也不显式设 quotaMode
        p.setTenants(Map.of("legacy", legacy));
        QuotaService service = new QuotaService(p);

        assertEquals(QuotaMode.FIXED, service.inspect("legacy", 0).mode(),
                "老租户默认口径必须仍是 FIXED");
        log.info("TEST business=老租户默认口径 input=tenant:legacy(no mode configured) "
                + "threads:{} quota:{} basis=default must remain FIXED", threads, limit);
        BatchOutcome o = runBoundaryBatch(service, "legacy", threads);
        log.info("TEST business=老租户默认口径 result allowed:{} (before:{},after:{}) expected:{}",
                o.allowed(), o.allowedBefore(), o.allowedAfter(), 2 * limit);
        assertEquals(2 * limit, o.allowed());
        assertEquals(limit, o.allowedBefore());
        assertEquals(limit, o.allowedAfter());
    }

    @Test
    @DisplayName("单段准确性：交界前一整段、后一整段在不混入另一窗口请求时也各自精确受限")
    void eachSegmentAccurateOnItsOwn() {
        int limit = 4;
        GuardProperties p = props(Map.of("fix", new long[]{limit}),
                Map.of("fix", QuotaMode.FIXED));
        QuotaService service = new QuotaService(p);

        long passBefore = LongStream.range(0, 20)
                .filter(i -> service.tryAcquire("fix", 999L).allowed()).count();
        long passAfter = LongStream.range(0, 20)
                .filter(i -> service.tryAcquire("fix", 1000L).allowed()).count();
        log.info("TEST business=FIXED单段准确 input=20x999 then 20x1000 quota:{} "
                        + "result beforePass:{} afterPass:{}", limit, passBefore, passAfter);
        assertEquals(limit, passBefore, "交界前一整段跟单段时一样准确");
        assertEquals(limit, passAfter, "交界后一整段同样准确");
    }
}
