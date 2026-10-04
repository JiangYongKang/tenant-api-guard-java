package com.github.highcumontoa.tenantapiguardjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.github.highcumontoa.tenantapiguardjava.model.WindowMode;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaService;

/**
 * 只读用量查询：已用/剩余/判定区段/判定依据与同一时刻真实请求的放行或拒绝结论一致；
 * 查询本身不占额度、不改状态。
 */
class QuotaUsageQueryTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaUsageQueryTest.class);

    private GuardProperties props() {
        GuardProperties p = new GuardProperties();
        p.setWindowMs(1000);
        TenantConfig fixed = new TenantConfig();
        fixed.setQuota(2L);
        TenantConfig sliding = new TenantConfig();
        sliding.setQuota(2L);
        sliding.setWindowMode(WindowMode.SLIDING);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        tenants.put("tF", fixed);
        tenants.put("tS", sliding);
        p.setTenants(tenants);
        return p;
    }

    @Test
    @DisplayName("FIXED：查询结果与真实判定一致，且查询不计数、不触发窗口切换")
    void fixedUsageMatchesRealDecision() {
        QuotaService service = new QuotaService(props());

        assertTrue(service.tryAcquire("tF", 100).allowed());

        QuotaUsage u1 = service.usage("tF", 500);
        log.info("TEST input=tenant:tF now:500 basis=1/2 consumed usage={}/{} remaining:{} wouldPermit:{}",
                u1.consumed(), u1.limit(), u1.remaining(), u1.wouldPermit());
        assertEquals(WindowMode.FIXED, u1.mode());
        assertEquals(1, u1.consumed());
        assertEquals(2, u1.limit());
        assertEquals(1, u1.remaining());
        assertTrue(u1.wouldPermit());
        assertNull(u1.reason());
        assertEquals(0, u1.segmentStartMs());
        assertEquals(1000, u1.segmentEndMs());

        // 查询不占额度：查多少次，下一个真实请求仍是第 2 个
        service.usage("tF", 500);
        service.usage("tF", 500);
        QuotaDecision second = service.tryAcquire("tF", 600);
        log.info("TEST input=tenant:tF now:600 basis=after 3 read-only queries consumed:{}",
                second.consumed());
        assertTrue(second.allowed());
        assertEquals(2, second.consumed(), "查询不得消耗配额");

        // 打满后：查询说没余量，真实请求也确实被拒——两边结论对得上
        QuotaUsage full = service.usage("tF", 700);
        assertEquals(0, full.remaining());
        assertFalse(full.wouldPermit());
        assertEquals(RejectReason.QUOTA_EXCEEDED, full.reason());
        QuotaDecision rejected = service.tryAcquire("tF", 700);
        log.info("TEST input=tenant:tF now:700 basis=usage said full, real request reason:{}",
                rejected.reason());
        assertFalse(rejected.allowed());
        assertEquals(RejectReason.QUOTA_EXCEEDED, rejected.reason());

        // 查询不触发窗口切换：在旧窗口查询新窗口时刻，不得把计数清零
        QuotaUsage futurePeek = service.usage("tF", 1500);
        log.info("TEST input=tenant:tF now:1500 basis=read-only must not roll window consumed:{}",
                futurePeek.consumed());
        assertEquals(0, futurePeek.consumed(), "新窗口尚无真实请求，读视图应为 0");
        QuotaUsage backAgain = service.usage("tF", 800);
        assertEquals(2, backAgain.consumed(), "查询未来时刻不得重置当前窗口计数");
        assertFalse(backAgain.wouldPermit());
    }

    @Test
    @DisplayName("SLIDING：查询的判定区段为 (now-windowMs, now]，结论与真实请求一致")
    void slidingUsageMatchesRealDecision() {
        QuotaService service = new QuotaService(props());

        assertTrue(service.tryAcquire("tS", 900).allowed());
        assertTrue(service.tryAcquire("tS", 999).allowed());

        // 固定口径在 1000 会翻篇；滑动口径查询应显示 (0,1000] 已满
        QuotaUsage atBoundary = service.usage("tS", 1000);
        log.info("TEST input=tenant:tS now:1000 basis=sliding segment [{},{}] consumed:{}/{} wouldPermit:{}",
                atBoundary.segmentStartMs(), atBoundary.segmentEndMs(),
                atBoundary.consumed(), atBoundary.limit(), atBoundary.wouldPermit());
        assertEquals(WindowMode.SLIDING, atBoundary.mode());
        assertEquals(0, atBoundary.segmentStartMs());
        assertEquals(1000, atBoundary.segmentEndMs());
        assertEquals(2, atBoundary.consumed());
        assertEquals(0, atBoundary.remaining());
        assertFalse(atBoundary.wouldPermit());
        assertEquals(RejectReason.QUOTA_EXCEEDED, atBoundary.reason());

        // 真实请求在同一时刻同样被拒
        assertFalse(service.tryAcquire("tS", 1000).allowed());

        // 900 滑出后查询显示有余量，真实请求确实放行
        QuotaUsage later = service.usage("tS", 1900);
        log.info("TEST input=tenant:tS now:1900 basis=900 expired remaining:{} wouldPermit:{}",
                later.remaining(), later.wouldPermit());
        assertEquals(1, later.consumed());
        assertEquals(1, later.remaining());
        assertTrue(later.wouldPermit());
        assertTrue(service.tryAcquire("tS", 1900).allowed());
    }

    @Test
    @DisplayName("未配置租户：查询给出 TENANT_NOT_CONFIGURED，与真实请求原因一致")
    void unknownTenantUsageMatches() {
        QuotaService service = new QuotaService(props());

        QuotaUsage u = service.usage("tX", 100);
        log.info("TEST input=tenant:tX now:100 basis=no config wouldPermit:{} reason:{}",
                u.wouldPermit(), u.reason());
        assertFalse(u.wouldPermit());
        assertEquals(RejectReason.TENANT_NOT_CONFIGURED, u.reason());
        assertEquals(0, u.remaining());

        QuotaDecision real = service.tryAcquire("tX", 100);
        assertFalse(real.allowed());
        assertEquals(RejectReason.TENANT_NOT_CONFIGURED, real.reason());
    }
}
