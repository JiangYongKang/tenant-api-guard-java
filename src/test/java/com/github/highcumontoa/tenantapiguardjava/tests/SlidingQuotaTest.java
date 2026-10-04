package com.github.highcumontoa.tenantapiguardjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import com.github.highcumontoa.tenantapiguardjava.model.RejectReason;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaMode;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaService;

/**
 * 滑动窗口口径边界、两种口径并存判定、交界确定性与只读查询一致性。
 */
class SlidingQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(SlidingQuotaTest.class);

    private GuardProperties props(long windowMs, Long defaultQuota, QuotaMode defaultMode,
                                  Map<String, TenantConfig> tenants) {
        GuardProperties p = new GuardProperties();
        p.setWindowMs(windowMs);
        p.setDefaultQuota(defaultQuota);
        p.setDefaultQuotaMode(defaultMode);
        p.setTenants(tenants == null ? new LinkedHashMap<>() : tenants);
        return p;
    }

    private TenantConfig cfg(Long quota, QuotaMode mode) {
        TenantConfig c = new TenantConfig();
        c.setQuota(quota);
        c.setQuotaMode(mode);
        return c;
    }

    @Test
    @DisplayName("滑动口径基本语义：同一连续窗口内恰好配额数个放行，之后拒绝")
    void slidingRejectsWithinSameInterval() {
        QuotaService service = new QuotaService(props(1000, 0L, QuotaMode.FIXED,
                Map.of("s", cfg(3L, QuotaMode.SLIDING))));

        for (long t : new long[]{100, 200, 900}) {
            QuotaDecision d = service.tryAcquire("s", t);
            log.info("TEST sliding input=now:{} allowed:{} consumed:{}", t, d.allowed(), d.consumed());
            assertTrue(d.allowed());
        }
        QuotaDecision full = service.tryAcquire("s", 999);
        log.info("TEST sliding input=now:999 basis=live stamps in (-1,999] full allowed:{} reason:{}",
                full.allowed(), full.reason());
        assertFalse(full.allowed());
        assertEquals(RejectReason.QUOTA_EXCEEDED, full.reason());
        assertEquals(3, full.consumed());
    }

    @Test
    @DisplayName("滑动口径消除交界叠加：旧固定口径在交界处会放两倍，滑动口径严格不超配额")
    void slidingNoDoubleBurstAtBoundary() {
        // 同一组输入，两种口径分别判定
        for (QuotaMode mode : QuotaMode.values()) {
            QuotaService service = new QuotaService(props(1000, 0L, QuotaMode.FIXED,
                    Map.of("t", cfg(3L, mode))));
            // 上一段末尾 3 个：600/700/900
            for (long t : new long[]{600, 700, 900}) {
                assertTrue(service.tryAcquire("t", t).allowed(),
                        mode + " 前段前 3 个应放行");
            }
            // 交界处紧接 3 个：1000/1100/1200
            int allowedAtEdge = 0;
            for (long t : new long[]{1000, 1100, 1200}) {
                if (service.tryAcquire("t", t).allowed()) {
                    allowedAtEdge++;
                }
            }
            int totalAllowedInContiguous = 6; // 600..1200 为 600ms 连续区间内的总数
            log.info("TEST boundary input=mode:{} edgeAllowed:{} contiguous600..1200 total:{}",
                    mode, allowedAtEdge, 3 + allowedAtEdge);
            if (mode == QuotaMode.FIXED) {
                // 固定口径在 [1000,2000) 重新计数，交界处的三个全部放行——演示“两段叠加”
                assertEquals(3, allowedAtEdge, "固定口径演示交界叠加");
            } else {
                // 滑动口径：1000/1100/1200 时存活集始终是 600/700/900（600 要到 1600 才滑出），
                // 三笔全部拒绝。任意连续 1000ms 区间内放行总数恒为 3，不出现两段叠加
                assertEquals(0, allowedAtEdge, "滑动口径不得在交界处叠加放量");
                assertEquals(3, countAllowed(service, mode), "交界前后连续区间不得超过配额");
            }
        }
    }

    private int countAllowed(QuotaService service, QuotaMode mode) {
        // 用一个新服务重放 600..1200 的完整序列，统计落在任意重叠区间的总量
        QuotaService replay = new QuotaService(props(1000, 0L, QuotaMode.FIXED,
                Map.of("t", cfg(3L, mode))));
        int n = 0;
        for (long t : new long[]{600, 700, 900, 1000, 1100, 1200}) {
            if (replay.tryAcquire("t", t).allowed()) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("滑动边界左闭右开：恰好间隔 window-ms 的时间戳滑出后，额度重新可用")
    void slidingStampExpiresAtExactlyWindowLength() {
        QuotaService service = new QuotaService(props(1000, 0L, QuotaMode.FIXED,
                Map.of("s", cfg(2L, QuotaMode.SLIDING))));

        assertTrue(service.tryAcquire("s", 0).allowed());
        assertTrue(service.tryAcquire("s", 100).allowed());
        // now=999：区间 (-1,999]，0、100 都存活 → 拒绝
        QuotaDecision at999 = service.tryAcquire("s", 999);
        assertFalse(at999.allowed());
        // now=1000：区间 (0,1000]，时间戳 0 恰好滑出（ts<=cutoff=0），仅剩 100 → 放行
        QuotaDecision at1000 = service.tryAcquire("s", 1000);
        log.info("TEST sliding edge input=now:1000 basis=stamp 0 slid out allowed:{} consumed:{}",
                at1000.allowed(), at1000.consumed());
        assertTrue(at1000.allowed());
        assertEquals(2, at1000.consumed());
        // 紧接着再来仍然满
        assertFalse(service.tryAcquire("s", 1001).allowed());
        // now=1100：100 滑出，存活 1000 → 放行
        assertTrue(service.tryAcquire("s", 1100).allowed());
    }

    @Test
    @DisplayName("两种口径并存：FIXED 与 SLIDING 租户各自独立判定、互不串味")
    void fixedAndSlidingTenantsCoexistIndependently() {
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        tenants.put("fixedT", cfg(3L, QuotaMode.FIXED));
        tenants.put("slideT", cfg(3L, QuotaMode.SLIDING));
        QuotaService service = new QuotaService(props(1000, 0L, QuotaMode.FIXED, tenants));

        // fixedT 在 [0,1000) 用满 3
        for (long t : new long[]{600, 700, 900}) {
            assertTrue(service.tryAcquire("fixedT", t).allowed());
        }
        // slideT 同样在 600/700/900 用满 3
        for (long t : new long[]{600, 700, 900}) {
            assertTrue(service.tryAcquire("slideT", t).allowed());
        }
        // now=1000：固定口径新窗口放行；滑动口径仍被前段占住
        QuotaDecision fixedEdge = service.tryAcquire("fixedT", 1000);
        QuotaDecision slideEdge = service.tryAcquire("slideT", 1000);
        log.info("TEST coexist input=now:1000 fixedAllowed:{} slidingAllowed:{}",
                fixedEdge.allowed(), slideEdge.allowed());
        assertTrue(fixedEdge.allowed(), "FIXED 租户交界后按自己的口径放行");
        assertFalse(slideEdge.allowed(), "SLIDING 租户按自己的口径继续受限");

        // 用量快照也各标各的口径
        assertEquals(QuotaMode.FIXED, service.inspect("fixedT", 1000).mode());
        assertEquals(QuotaMode.SLIDING, service.inspect("slideT", 1000).mode());
        assertNotEquals(service.inspect("fixedT", 1000).windowStartMs(),
                service.inspect("slideT", 1000).windowStartMs(),
                "同一时刻两口径所算区间不同：固定=格起点 1000，滑动=now-window=0");
    }

    @Test
    @DisplayName("只读查询与真实请求一致：查询不占额度，且 wouldAllow 与随后判定对得上")
    void inspectMatchesRealDecisionAndDoesNotConsume() {
        QuotaService service = new QuotaService(props(1000, 0L, QuotaMode.FIXED,
                Map.of("s", cfg(2L, QuotaMode.SLIDING))));

        assertTrue(service.tryAcquire("s", 100).allowed());
        // 连续多次查询，结论稳定且不计数
        for (int i = 0; i < 5; i++) {
            QuotaUsage u = service.inspect("s", 500);
            assertEquals(1, u.consumed(), "查询不得占用额度");
            assertEquals(1, u.remaining());
            assertTrue(u.allowed());
        }
        // 查询说还能放一个 → 真实请求确实放行；之后查询立即翻为拒绝，与真实请求一致
        assertTrue(service.inspect("s", 600).allowed());
        assertTrue(service.tryAcquire("s", 600).allowed());
        QuotaUsage full = service.inspect("s", 601);
        log.info("TEST inspect basis=full used:{} remaining:{} wouldAllow:{}",
                full.consumed(), full.remaining(), full.allowed());
        assertFalse(full.allowed());
        assertEquals(RejectReason.QUOTA_EXCEEDED, full.reason());
        assertEquals(0, full.remaining());
        QuotaDecision rejected = service.tryAcquire("s", 601);
        assertFalse(rejected.allowed());
        assertEquals(full.consumed(), rejected.consumed());

        // 查询不清理滑动结构：满之后即使推进到 stamp 恰好滑出的时刻，结论仍由真实状态给出
        QuotaUsage afterSlide = service.inspect("s", 1100);
        // 100 滑出，存活 600 一个
        assertEquals(1, afterSlide.consumed());
        assertTrue(afterSlide.allowed());
        // peek 与 inspect 一致
        assertTrue(service.peek("s", 1100).allowed());
    }
}
