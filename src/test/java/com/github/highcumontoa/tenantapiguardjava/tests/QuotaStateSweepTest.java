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
import com.github.highcumontoa.tenantapiguardjava.model.WindowMode;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaService;

/**
 * 不活跃租户状态回收：长期不来的租户状态可被收回，
 * 回收不影响活跃租户的判定，也不改变被回收租户后续的判定结论。
 */
class QuotaStateSweepTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaStateSweepTest.class);

    private GuardProperties props(long idleMs) {
        GuardProperties p = new GuardProperties();
        p.setWindowMs(1000);
        p.setStateIdleMs(idleMs);
        TenantConfig a = new TenantConfig();
        a.setQuota(1L);
        TenantConfig b = new TenantConfig();
        b.setQuota(1L);
        b.setWindowMode(WindowMode.SLIDING);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        tenants.put("tA", a);
        tenants.put("tB", b);
        p.setTenants(tenants);
        return p;
    }

    @Test
    @DisplayName("回收：空闲超阈值的租户状态被收回，活跃租户状态保留且判定不受影响")
    void inactiveReclaimedActiveUntouched() {
        QuotaService service = new QuotaService(props(60_000));

        // tA 在 t=0 用掉唯一配额；tB 在 t=0 也用掉唯一配额
        assertTrue(service.tryAcquire("tA", 0).allowed());
        assertTrue(service.tryAcquire("tB", 0).allowed());
        assertEquals(2, service.trackedTenantCount());

        // tB 在 59_000 仍有活动（t=0 的记录早已滑出滑动区间，此次放行并刷新活跃度）
        QuotaDecision bActive = service.tryAcquire("tB", 59_000);
        assertTrue(bActive.allowed(), "t=0 的记录对 (58000,59000] 区间已失效，tB 应有额度");

        // t=61_000 回收：tA 空闲 61s >= 阈值被收；tB 空闲 2s 保留
        int removed = service.sweepInactive(61_000);
        log.info("TEST input=now:61000 basis=tA idle 61s>=60s, tB idle 2s removed:{} tracked:{}",
                removed, service.trackedTenantCount());
        assertEquals(1, removed);
        assertEquals(1, service.trackedTenantCount());

        // 活跃租户 tB 的判定不受回收影响：区间 (60000,61000] 内仍无新额度？——
        // t=0 的记录早已滑出，tB 此刻应有额度，且状态未被回收所以计数语义连续
        QuotaDecision bAfter = service.tryAcquire("tB", 61_000);
        log.info("TEST input=tenant:tB now:61000 basis=state retained, old hit expired allowed:{}",
                bAfter.allowed());
        assertTrue(bAfter.allowed());

        // 被回收的 tA 再来请求：状态重建，判定结论与从未回收一致（窗口 [61000,62000) 配额 1）
        QuotaDecision aAfter = service.tryAcquire("tA", 61_000);
        log.info("TEST input=tenant:tA now:61000 basis=state rebuilt after reclaim allowed:{} consumed:{}",
                aAfter.allowed(), aAfter.consumed());
        assertTrue(aAfter.allowed());
        assertEquals(1, aAfter.consumed());
        assertEquals(2, service.trackedTenantCount());
    }

    @Test
    @DisplayName("回收阈值：空闲不足阈值不回收；阈值生效为 max(stateIdleMs, windowMs)")
    void sweepRespectsIdleThreshold() {
        QuotaService service = new QuotaService(props(60_000));

        assertTrue(service.tryAcquire("tA", 0).allowed());
        assertEquals(1, service.trackedTenantCount());

        // 空闲 59_999 < 60_000：不回收
        int removed = service.sweepInactive(59_999);
        log.info("TEST input=now:59999 basis=idle 59999<60000 removed:{}", removed);
        assertEquals(0, removed);
        assertEquals(1, service.trackedTenantCount());

        // 空闲恰好 60_000：回收
        int removedAt = service.sweepInactive(60_000);
        log.info("TEST input=now:60000 basis=idle 60000>=60000 removed:{}", removedAt);
        assertEquals(1, removedAt);
        assertEquals(0, service.trackedTenantCount());
    }

    @Test
    @DisplayName("回收语义安全：回收只发生在状态语义过期后，回收前后同一时刻的判定结论一致")
    void reclaimNeverChangesJudgement() {
        // stateIdleMs 故意配小（小于 windowMs），验证实际阈值被提升到 windowMs
        GuardProperties p = props(10);
        QuotaService service = new QuotaService(p);

        // tA 固定口径：t=0 用掉配额 1，当前窗口 [0,1000) 已满
        assertTrue(service.tryAcquire("tA", 0).allowed());
        assertFalse(service.tryAcquire("tA", 500).allowed());

        // t=500 时空闲 0ms（刚有判定）< max(10,1000)：不得回收
        int removed = service.sweepInactive(500);
        log.info("TEST input=now:500 basis=effective threshold max(10,1000)=1000 removed:{}", removed);
        assertEquals(0, removed, "窗口未翻篇前绝不允许回收");

        // t=1500 空闲 1000 >= 1000 回收；回收后同一窗口的判定必须与新状态一致
        assertEquals(1, service.sweepInactive(1500));
        QuotaDecision afterReclaim = service.tryAcquire("tA", 1500);
        log.info("TEST input=tenant:tA now:1500 basis=window rolled anyway, reclaim transparent allowed:{}",
                afterReclaim.allowed());
        assertTrue(afterReclaim.allowed(), "窗口 [1000,2000) 本就有新额度，回收不改变结论");
        assertEquals(1, afterReclaim.consumed());
    }
}
