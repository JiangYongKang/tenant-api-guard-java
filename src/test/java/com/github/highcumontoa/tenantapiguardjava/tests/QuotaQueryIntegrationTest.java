package com.github.highcumontoa.tenantapiguardjava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 端到端：只读配额查询口子 {@code GET /internal/quota}。
 *
 * <p>验证：查询路径不被 {@code /api/**} 守卫拦截（不占额度）；返回口径/已用/剩余/区间/
 * 判定依据；查询结论与随后真实请求的放行/拒绝一致；FIXED 与 SLIDING 租户并存各报各的。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
        "tenant.guard.window-ms=1000",
        "tenant.guard.default-quota=0",
        "tenant.guard.default-quota-mode=FIXED",
        "tenant.guard.default-max-concurrent=0",
        "tenant.guard.global-max-concurrent=64",
        "tenant.guard.quota-idle-evict-ms=0",
        "tenant.guard.tenants.fix.quota=3",
        "tenant.guard.tenants.fix.max-concurrent=10",
        "tenant.guard.tenants.fix.quota-mode=FIXED",
        "tenant.guard.tenants.sli.quota=3",
        "tenant.guard.tenants.sli.max-concurrent=10",
        "tenant.guard.tenants.sli.quota-mode=SLIDING"
})
class QuotaQueryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaQueryIntegrationTest.class);

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("查询不占额度：连续查询 used 不变；查询说有余量，随后真实请求确实放行并打满")
    void queryDoesNotConsumeAndMatchesDecision() throws Exception {
        // 初始 0/3
        queryExpect("fix", "FIXED", 0, 3, true);
        // 连发两次真实请求打掉 2 个
        mvc.perform(get("/api/hello/a").header("X-Tenant-Id", "fix")).andExpect(status().isOk());
        mvc.perform(get("/api/hello/b").header("X-Tenant-Id", "fix")).andExpect(status().isOk());
        // 连续多次查询：used 恒为 2，remaining=1，wouldAllow=true
        for (int i = 0; i < 3; i++) {
            queryExpect("fix", "FIXED", 2, 1, true);
        }
        // 查询与真实请求一致：还能放一个
        mvc.perform(get("/api/hello/c").header("X-Tenant-Id", "fix")).andExpect(status().isOk());
        // 打满后查询翻为 wouldAllow=false，真实请求随即 429
        queryExpect("fix", "FIXED", 3, 0, false);
        mvc.perform(get("/api/hello/d").header("X-Tenant-Id", "fix"))
                .andExpect(status().isTooManyRequests());
        log.info("TEST query consistency basis=inspect matched real PASS then REJECT");
    }

    @Test
    @DisplayName("滑动口径租户查询报 SLIDING 与滑动区间；未配置租户明确报 TENANT_NOT_CONFIGURED")
    void slidingTenantAndUnknownTenant() throws Exception {
        mvc.perform(get("/internal/quota").param("tenant", "sli"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenant").value("sli"))
                .andExpect(jsonPath("$.data.mode").value("SLIDING"))
                .andExpect(jsonPath("$.data.used").value(0))
                .andExpect(jsonPath("$.data.remaining").value(3))
                .andExpect(jsonPath("$.data.wouldAllow").value(true))
                .andExpect(jsonPath("$.data.interval.windowIndex").isNumber())
                .andExpect(jsonPath("$.data.observedAtMs").isNumber());
        log.info("TEST query input=tenant:sli basis=SLIDING snapshot returned");

        mvc.perform(get("/internal/quota").param("tenant", "nobody"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wouldAllow").value(false))
                .andExpect(jsonPath("$.data.reason").value("TENANT_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.data.used").value(0))
                .andExpect(jsonPath("$.data.limit").value(0));
    }

    private void queryExpect(String tenant, String mode, int used, int remaining,
                             boolean wouldAllow) throws Exception {
        mvc.perform(get("/internal/quota").param("tenant", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value(mode))
                .andExpect(jsonPath("$.data.used").value(used))
                .andExpect(jsonPath("$.data.remaining").value(remaining))
                .andExpect(jsonPath("$.data.wouldAllow").value(wouldAllow));
    }
}
