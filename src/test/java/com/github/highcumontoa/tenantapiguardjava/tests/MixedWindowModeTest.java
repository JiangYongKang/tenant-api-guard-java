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
 * 两种口径并存：同一服务内 FIXED 与 SLIDING 租户各自判定、互不惊扰；
 * 运行期修改某租户配置不影响其他租户。
 */
class MixedWindowModeTest {

    private static final Logger log = LoggerFactory.getLogger(MixedWindowModeTest.class);

    private GuardProperties mixedProps() {
        GuardProperties p = new GuardProperties();
        p.setWindowMs(1000);
        TenantConfig fixed = new TenantConfig();
        fixed.setQuota(2L); // 未设 windowMode → 默认 FIXED
        TenantConfig sliding = new TenantConfig();
        sliding.setQuota(2L);
        sliding.setWindowMode(WindowMode.SLIDING);
        Map<String, TenantConfig> tenants = new LinkedHashMap<>();
        tenants.put("tFixed", fixed);
        tenants.put("tSliding", sliding);
        p.setTenants(tenants);
        return p;
    }

    @Test
    @DisplayName("并存：交界时刻 FIXED 租户翻篇放行，SLIDING 租户仍被压住，各按各的口径")
    void fixedAndSlidingCoexist() {
        QuotaService service = new QuotaService(mixedProps());

        // 两个租户都在窗口 [0,1000) 末尾打满各自配额
        for (String tenant : new String[]{"tFixed", "tSliding"}) {
            assertTrue(service.tryAcquire(tenant, 900).allowed());
            assertTrue(service.tryAcquire(tenant, 999).allowed());
            QuotaDecision full = service.tryAcquire(tenant, 999);
            log.info("TEST input=tenant:{} now:999 basis=quota 2 exhausted reason:{}", tenant, full.reason());
            assertFalse(full.allowed());
        }

        // 交界 1000：FIXED 进入新窗口放行；SLIDING 的 (0,1000] 仍含 900/999，拒绝
        QuotaDecision fixedNext = service.tryAcquire("tFixed", 1000);
        QuotaDecision slidingNext = service.tryAcquire("tSliding", 1000);
        log.info("TEST input=now:1000 basis=fixed rolls over / sliding holds fixed:{} sliding:{}",
                fixedNext.allowed(), slidingNext.allowed());
        assertTrue(fixedNext.allowed(), "FIXED 租户在交界应翻篇放行");
        assertFalse(slidingNext.allowed(), "SLIDING 租户在交界仍受 (0,1000] 约束");

        // 互不惊扰：tSliding 的拒绝不消耗 tFixed 新窗口的计数
        QuotaDecision fixedSecond = service.tryAcquire("tFixed", 1001);
        log.info("TEST input=tenant:tFixed now:1001 basis=new window second slot consumed:{}",
                fixedSecond.consumed());
        assertTrue(fixedSecond.allowed());
        assertEquals(2, fixedSecond.consumed());
    }

    @Test
    @DisplayName("运行期配置变更：改 tSliding 配额立即生效，tFixed 当前窗口计数与上限不回溯")
    void runtimeConfigChangeIsolated() {
        GuardProperties props = mixedProps();
        QuotaService service = new QuotaService(props);

        // tFixed 在当前窗口用掉 1/2
        assertTrue(service.tryAcquire("tFixed", 100).allowed());
        // tSliding 打满 2/2
        assertTrue(service.tryAcquire("tSliding", 100).allowed());
        assertTrue(service.tryAcquire("tSliding", 200).allowed());
        assertFalse(service.tryAcquire("tSliding", 300).allowed());

        // 运行期把 tSliding 配额调到 3：滑动口径无窗口边界，对后续请求立即生效
        props.getTenants().get("tSliding").setQuota(3L);
        QuotaDecision widened = service.tryAcquire("tSliding", 400);
        log.info("TEST input=tenant:tSliding now:400 basis=quota raised 2->3 at runtime allowed:{} consumed:{}/{}",
                widened.allowed(), widened.consumed(), widened.limit());
        assertTrue(widened.allowed(), "滑动口径下配置调整立即生效");
        assertEquals(3, widened.limit());

        // tFixed 不受牵连：当前窗口上限仍是进入窗口时的 2，已用 1，还能再放 1 个
        QuotaDecision fixedSecond = service.tryAcquire("tFixed", 500);
        log.info("TEST input=tenant:tFixed now:500 basis=window limit stays 2 consumed:{}/{}",
                fixedSecond.consumed(), fixedSecond.limit());
        assertTrue(fixedSecond.allowed());
        assertEquals(2, fixedSecond.consumed());
        assertEquals(2, fixedSecond.limit(), "tFixed 当前窗口上限不得被别人的配置变更改写");
        assertFalse(service.tryAcquire("tFixed", 600).allowed());
    }
}
