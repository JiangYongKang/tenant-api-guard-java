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
 * 运维只读查询口子：GET /ops/quota/{tenant}。
 * 不经过入口守卫（无需租户头）、不占用配额，读数与真实请求判定一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
        "tenant.guard.window-ms=60000", // 拉长窗口，避免测试执行期间翻篇
        "tenant.guard.default-quota=0",
        "tenant.guard.default-max-concurrent=0",
        "tenant.guard.global-max-concurrent=64",
        "tenant.guard.tenants.o1.quota=3",
        "tenant.guard.tenants.o1.max-concurrent=5",
        "tenant.guard.tenants.o3.quota=3",
        "tenant.guard.tenants.o3.max-concurrent=5",
        "tenant.guard.tenants.o2.quota=2",
        "tenant.guard.tenants.o2.max-concurrent=5",
        "tenant.guard.tenants.o2.window-mode=SLIDING"
})
class OpsEndpointTest {

    private static final Logger log = LoggerFactory.getLogger(OpsEndpointTest.class);

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("查询口子：无需租户头即可读，返回口径/区段/已用/剩余/判定依据")
    void usageEndpointIsReadOnlyAndExplains() throws Exception {
        log.info("TEST input=GET /ops/quota/o1 basis=no guard headers needed");
        mvc.perform(get("/ops/quota/o1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenant").value("o1"))
                .andExpect(jsonPath("$.data.mode").value("FIXED"))
                .andExpect(jsonPath("$.data.consumed").value(0))
                .andExpect(jsonPath("$.data.limit").value(3))
                .andExpect(jsonPath("$.data.remaining").value(3))
                .andExpect(jsonPath("$.data.wouldPermit").value(true))
                .andExpect(jsonPath("$.data.segmentStartMs").isNumber())
                .andExpect(jsonPath("$.data.segmentEndMs").isNumber());
    }

    @Test
    @DisplayName("查询不占额度：反复查询后真实请求仍放满全部配额；查询读数与真实消耗一致")
    void queryDoesNotConsumeAndMatchesRealTraffic() throws Exception {
        // 反复查询不得消耗任何配额
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/ops/quota/o3")).andExpect(status().isOk());
        }
        // 真实请求 2 个后，查询应显示 consumed=2 remaining=1
        mvc.perform(get("/api/hello/a").header("X-Tenant-Id", "o3")).andExpect(status().isOk());
        mvc.perform(get("/api/hello/b").header("X-Tenant-Id", "o3")).andExpect(status().isOk());
        log.info("TEST input=2 real requests to o3 basis=usage must reflect consumed 2/3");
        mvc.perform(get("/ops/quota/o3"))
                .andExpect(jsonPath("$.data.consumed").value(2))
                .andExpect(jsonPath("$.data.remaining").value(1))
                .andExpect(jsonPath("$.data.wouldPermit").value(true));

        // 第 3 个放行后查询说没余量，第 4 个真实请求确实被 429——两边结论对得上
        mvc.perform(get("/api/hello/c").header("X-Tenant-Id", "o3")).andExpect(status().isOk());
        mvc.perform(get("/ops/quota/o3"))
                .andExpect(jsonPath("$.data.remaining").value(0))
                .andExpect(jsonPath("$.data.wouldPermit").value(false))
                .andExpect(jsonPath("$.data.reason").value("QUOTA_EXCEEDED"));
        log.info("TEST input=4th real request to o3 basis=usage said full, request must be rejected");
        mvc.perform(get("/api/hello/d").header("X-Tenant-Id", "o3"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.reason").value("QUOTA_EXCEEDED"));
    }

    @Test
    @DisplayName("滑动口径租户：查询显示 mode=SLIDING；未配置租户给出 TENANT_NOT_CONFIGURED")
    void slidingModeAndUnknownTenantVisible() throws Exception {
        mvc.perform(get("/api/hello/x").header("X-Tenant-Id", "o2")).andExpect(status().isOk());
        log.info("TEST input=GET /ops/quota/o2 basis=sliding tenant usage view");
        mvc.perform(get("/ops/quota/o2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("SLIDING"))
                .andExpect(jsonPath("$.data.consumed").value(1))
                .andExpect(jsonPath("$.data.remaining").value(1));

        log.info("TEST input=GET /ops/quota/ghost basis=no config reason must be TENANT_NOT_CONFIGURED");
        mvc.perform(get("/ops/quota/ghost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wouldPermit").value(false))
                .andExpect(jsonPath("$.data.reason").value("TENANT_NOT_CONFIGURED"));
    }
}
