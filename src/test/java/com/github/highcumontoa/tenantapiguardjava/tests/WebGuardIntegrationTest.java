package com.github.highcumontoa.tenantapiguardjava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 端到端：请求头 → 入口守卫（配额/舱壁）→ 幂等写业务 → 稳定原因码 HTTP 响应。
 *
 * <p>测试内默认配额/并发为 0（未配置租户确定拒绝），并显式配置 t1 / t2 两个租户。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
        "tenant.guard.window-ms=1000",
        "tenant.guard.default-quota=0",
        "tenant.guard.default-max-concurrent=0",
        "tenant.guard.idempotency-ttl-ms=300000",
        "tenant.guard.in-progress-timeout-ms=10000",
        "tenant.guard.global-max-concurrent=64",
        "tenant.guard.tenants.t1.quota=5",
        "tenant.guard.tenants.t1.max-concurrent=3",
        "tenant.guard.tenants.t2.quota=100",
        "tenant.guard.tenants.t2.max-concurrent=10"
})
class WebGuardIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(WebGuardIntegrationTest.class);

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("缺少租户头：400 + MISSING_TENANT，不静默放行")
    void missingTenantRejected() throws Exception {
        log.info("TEST input=POST /api/orders without {} basis=tenant identification first",
                "X-Tenant-Id");
        mvc.perform(post("/api/orders")
                        .header("X-Idempotency-Key", "k0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"p\",\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.reason").value("MISSING_TENANT"));
    }

    @Test
    @DisplayName("未配置租户：403 + TENANT_NOT_CONFIGURED，原因与配额超限可区分")
    void unknownTenantRejected() throws Exception {
        log.info("TEST input=tenant:tX basis=no local quota/concurrency config");
        mvc.perform(get("/api/hello/world").header("X-Tenant-Id", "tX"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.reason").value("TENANT_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("正常请求通过守卫并返回租户信息")
    void happyPathPasses() throws Exception {
        log.info("TEST input=tenant:t2 GET /api/hello/bob basis=within quota and bulkhead");
        mvc.perform(get("/api/hello/bob").header("X-Tenant-Id", "t2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenant").value("t2"))
                .andExpect(jsonPath("$.data.name").value("bob"));
    }

    @Test
    @DisplayName("写操作幂等：同键同内容第二次 replay=true 且订单号一致；缺键 400")
    void idempotentWriteReplaysConsistently() throws Exception {
        String body = "{\"product\":\"widget\",\"quantity\":2}";

        MvcResult first = mvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "t2")
                        .header("X-Idempotency-Key", "key-dup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay").value(false))
                .andReturn();
        String firstJson = first.getResponse().getContentAsString();
        String orderId = extractOrderId(firstJson);
        log.info("TEST input=tenant:t2 key:key-dup-1 basis=first execution orderId:{}", orderId);

        mvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "t2")
                        .header("X-Idempotency-Key", "key-dup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay").value(true))
                .andExpect(jsonPath("$.data.order").value(org.hamcrest.Matchers.containsString(orderId)));
        log.info("TEST input=tenant:t2 key:key-dup-1 same content basis=replay identical orderId:{}", orderId);

        log.info("TEST input=tenant:t2 no idempotency key basis=write requires key");
        mvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "t2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reason").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    @DisplayName("同键不同内容：409 + IDEMPOTENT_CONTENT_CONFLICT，且原因与执行中超时可区分")
    void sameKeyDifferentContentConflicts() throws Exception {
        mvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "t2")
                        .header("X-Idempotency-Key", "key-conflict-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"A\",\"quantity\":1}"))
                .andExpect(status().isOk());
        log.info("TEST input=tenant:t2 key:key-conflict-1 basis=first content A/1 stored");

        mvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "t2")
                        .header("X-Idempotency-Key", "key-conflict-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"B\",\"quantity\":9}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.reason").value("IDEMPOTENT_CONTENT_CONFLICT"))
                .andExpect(jsonPath("$.error.detail.idempotencyKey").value("key-conflict-1"))
                .andExpect(jsonPath("$.error.detail.submittedHash").isNotEmpty())
                .andExpect(jsonPath("$.error.detail.firstHash").isNotEmpty());
        log.info("TEST input=tenant:t2 key:key-conflict-1 basis=different content hash decision=CONFLICT");
    }

    @Test
    @DisplayName("配额打满：t1 每窗 5 个，第 6 个请求 429 + QUOTA_EXCEEDED，明细可解释")
    void quotaExceededOverHttp() throws Exception {
        for (int i = 1; i <= 5; i++) {
            log.info("TEST input=tenant:t1 request#{} within window basis=quota 5/window", i);
            mvc.perform(get("/api/hello/n" + i).header("X-Tenant-Id", "t1"))
                    .andExpect(status().isOk());
        }
        log.info("TEST input=tenant:t1 request#6 basis=quota consumed 5/5, window about to roll? no");
        mvc.perform(get("/api/hello/n6").header("X-Tenant-Id", "t1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.reason").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.error.detail.consumed").value(5))
                .andExpect(jsonPath("$.error.detail.limit").value(5))
                .andExpect(jsonPath("$.error.detail.windowIndex").isNumber());
    }

    private static String extractOrderId(String json) {
        int i = json.indexOf("orderId=");
        int j = json.indexOf(',', i);
        return i >= 0 ? json.substring(i + "orderId=".length(), j) : "";
    }
}
