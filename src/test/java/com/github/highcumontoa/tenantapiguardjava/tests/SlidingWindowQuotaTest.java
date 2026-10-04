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
import com.github.highcumontoa.tenantapiguardjava.model.RejectReason;
import com.github.highcumontoa.tenantapiguardjava.model.WindowMode;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaService;

/**
 * 滑动口径：任意 windowMs 长度区间内的放行总量不超过配额；
 * 重点覆盖时间段交界处不再“卡两波”，以及时间戳恰好滑出边界的判定。
 */
class SlidingWindowQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowQuotaTest.class);

    private GuardProperties slidingProps(long quota) {
        GuardProperties p = new GuardProperties();
        p.setWindowMs(1000);
        TenantConfig cfg = new TenantConfig();
        cfg.setQuota(quota);
        cfg.setWindowMode(WindowMode.SLIDING);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        tenants.put("tS", cfg);
        p.setTenants(tenants);
        return p;
    }

    @Test
    @DisplayName("滑动口径：固定窗口交界处不放行双倍——前段末尾+后段开头合起来仍受配额约束")
    void noDoubleBurstAcrossFixedBoundary() {
        QuotaService service = new QuotaService(slidingProps(2));

        // 固定口径下 [0,1000) 末尾的两个请求都放行
        QuotaDecision d1 = service.tryAcquire("tS", 900);
        QuotaDecision d2 = service.tryAcquire("tS", 999);
        log.info("TEST input=tenant:tS now:900,999 basis=sliding quota 2 allowed:{},{}", d1.allowed(), d2.allowed());
        assertTrue(d1.allowed());
        assertTrue(d2.allowed());

        // 固定口径下 1000 已属新窗口会放行；滑动口径下 (0,1000] 内已有 900/999 两笔，必须拒绝
        QuotaDecision atBoundary = service.tryAcquire("tS", 1000);
        log.info("TEST input=tenant:tS now:1000 basis=(0,1000] contains 900,999 consumed:{} reason:{}",
                atBoundary.consumed(), atBoundary.reason());
        assertFalse(atBoundary.allowed(), "交界处不得再放行——任意 1000ms 内不能超过 2 个");
        assertEquals(RejectReason.QUOTA_EXCEEDED, atBoundary.reason());

        // 到 1900：900 滑出（900 <= 1900-1000），只剩 999，可再放行一个
        QuotaDecision later = service.tryAcquire("tS", 1900);
        log.info("TEST input=tenant:tS now:1900 basis=900 expired, (900,1900] contains 999 allowed:{} consumed:{}",
                later.allowed(), later.consumed());
        assertTrue(later.allowed());
        assertEquals(2, later.consumed());

        // 1901：(901,1901] 内有 999 和 1900，已满
        QuotaDecision full = service.tryAcquire("tS", 1901);
        log.info("TEST input=tenant:tS now:1901 basis=(901,1901] contains 999,1900 reason:{}", full.reason());
        assertFalse(full.allowed());
        assertEquals(RejectReason.QUOTA_EXCEEDED, full.reason());
    }

    @Test
    @DisplayName("滑动口径边界精确性：时间戳在 now-windowMs 恰好滑出，前一毫秒仍占额度")
    void slidingEdgeIsExact() {
        QuotaService service = new QuotaService(slidingProps(2));

        assertTrue(service.tryAcquire("tS", 500).allowed());
        assertTrue(service.tryAcquire("tS", 1400).allowed());

        // now=1499：区间 (499,1499] 含 500 与 1400，已满
        QuotaDecision before = service.tryAcquire("tS", 1499);
        log.info("TEST input=tenant:tS now:1499 basis=500 still in (499,1499] reason:{}", before.reason());
        assertFalse(before.allowed());

        // now=1500：阈值 500，500 <= 500 恰好滑出，只剩 1400，放行
        QuotaDecision at = service.tryAcquire("tS", 1500);
        log.info("TEST input=tenant:tS now:1500 basis=500 expired at threshold 500 allowed:{} consumed:{}",
                at.allowed(), at.consumed());
        assertTrue(at.allowed());
        assertEquals(2, at.consumed());
    }

    @Test
    @DisplayName("滑动口径：长空闲后时间戳全部失效，重新计数不残留")
    void slidingRecoversAfterIdle() {
        QuotaService service = new QuotaService(slidingProps(1));

        assertTrue(service.tryAcquire("tS", 100).allowed());
        QuotaDecision full = service.tryAcquire("tS", 200);
        assertFalse(full.allowed());
        log.info("TEST input=tenant:tS now:200 basis=quota 1 exhausted reason:{}", full.reason());

        // 空闲远超一个周期后，旧时间戳不再占额度
        QuotaDecision afterIdle = service.tryAcquire("tS", 5000);
        log.info("TEST input=tenant:tS now:5000 basis=all hits expired allowed:{} consumed:{}",
                afterIdle.allowed(), afterIdle.consumed());
        assertTrue(afterIdle.allowed());
        assertEquals(1, afterIdle.consumed());
    }
}
