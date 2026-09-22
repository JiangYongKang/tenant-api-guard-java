package com.github.highcumontoa.tenantapiguardjava.tests.web;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端保护链路测试(真实 Spring MVC + 过滤器):
 * 并发超配额、并发重复提交、同标识不同内容、租户隔离与突发限制。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GuardFilterIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GuardFilterIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void missingTenantIsRejectedWithStableReason() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/ping"))
                .andExpect(status().isBadRequest())
                .andReturn();
        String body = r.getResponse().getContentAsString();
        log.info("input GET /api/ping without tenant -> status=400 body={}", body);
        assertTrue(body.contains("TENANT_MISSING"));
    }

    @Test
    void httpQuotaConcurrentOverLimit() throws Exception {
        String tenant = "t-q5";
        int threads = 25;
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<Integer> rejectionStatuses = new HashSet<>();
        Set<String> rejectionCodes = new HashSet<>();

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int seq = i;
                tasks.add(() -> {
                    MvcResult mr = mockMvc.perform(get("/api/ping").header("X-Tenant-Id", tenant))
                            .andReturn();
                    int sc = mr.getResponse().getStatus();
                    String content = mr.getResponse().getContentAsString();
                    if (sc == 200) {
                        ok.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                        rejectionStatuses.add(sc);
                        if (content.contains("QUOTA_EXCEEDED")) {
                            rejectionCodes.add("QUOTA_EXCEEDED");
                        }
                    }
                    log.info("input tenant={} seq={} -> status={} body={}", tenant, seq, sc, content);
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        log.info("summary tenant={} total={} ok={} rejected={} rejectionStatuses={} codes={}",
                tenant, threads, ok.get(), rejected.get(), rejectionStatuses, rejectionCodes);
        assertEquals(5, ok.get(), "t-q5 配额为 5,不得超额放行");
        assertEquals(threads - 5, rejected.get());
        assertEquals(Set.of(429), rejectionStatuses, "所有拒绝必须稳定为 429");
        assertEquals(Set.of("QUOTA_EXCEEDED"), rejectionCodes, "拒绝原因必须稳定可区分");
    }

    @Test
    void tenantBurstIsolationDoesNotAffectOtherTenants() throws Exception {
        // t-burst 单租户并发上限为 2;制造 6 个并发慢请求(配额内),部分应被突发限制拒绝。
        String burstTenant = "t-burst";
        int threads = 6;
        AtomicInteger burstRejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int seq = i;
                tasks.add(() -> {
                    MvcResult mr = mockMvc.perform(post("/api/orders")
                                    .header("X-Tenant-Id", burstTenant)
                                    .header("Idempotency-Key", "burst-" + seq)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"v\":" + seq + "}"))
                            .andReturn();
                    int sc = mr.getResponse().getStatus();
                    if (sc == 429) {
                        burstRejected.incrementAndGet();
                    }
                    log.info("input burst tenant={} seq={} -> status={} body={}",
                            burstTenant, seq, sc, mr.getResponse().getContentAsString());
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // 在 t-burst 被突发限制的同时,另一个普通租户仍应获得正常判定(200)。
        MvcResult other = mockMvc.perform(get("/api/ping").header("X-Tenant-Id", "t-healthy"))
                .andExpect(status().isOk())
                .andReturn();
        log.info("input healthy tenant=t-q10 while t-burst is throttled -> status=200 body={}",
                other.getResponse().getContentAsString());

        log.info("summary burstRejected={} (t-burst limit=2, 6 concurrent)", burstRejected.get());
        assertTrue(burstRejected.get() >= 1, "突发租户必须被 TENANT_BURST_LIMITED 拒绝");
    }

    @Test
    void httpConcurrentIdempotentSubmitOneEffectSameResult() throws Exception {
        String tenant = "t-idem-http";
        String key = "http-key-" + System.nanoTime();
        String payload = "{\"item\":\"book\"}";
        int threads = 16;
        AtomicInteger twoXx = new AtomicInteger();
        Set<String> orderIds = new HashSet<>();
        AtomicInteger replayMarked = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    MvcResult mr = mockMvc.perform(post("/api/orders")
                                    .header("X-Tenant-Id", tenant)
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                            .andReturn();
                    int sc = mr.getResponse().getStatus();
                    String body = mr.getResponse().getContentAsString();
                    if ("true".equals(mr.getResponse().getHeader("X-Idempotent-Replay"))) {
                        replayMarked.incrementAndGet();
                    }
                    if (sc == 200) {
                        twoXx.incrementAndGet();
                        orderIds.add(extractOrderId(body));
                    }
                    log.info("input tenant={} key={} -> status={} replay={} body={}",
                            tenant, key, sc,
                            mr.getResponse().getHeader("X-Idempotent-Replay"), body);
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        log.info("summary key={} totalOk={} distinctOrderIds={} replayMarked={}",
                key, twoXx.get(), orderIds, replayMarked.get());
        assertEquals(threads, twoXx.get(), "所有并发请求都应得到确定的 200 结论");
        assertEquals(1, orderIds.size(), "同一幂等标识只能产生一个 orderId");
        assertEquals(threads - 1, replayMarked.get(), "除首次外都应是可识别的回放");
    }

    @Test
    void httpSameKeyDifferentContentConflicts() throws Exception {
        String tenant = "t-idem-http";
        String key = "conflict-key-" + System.nanoTime();

        MvcResult first = mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", tenant)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"book\"}"))
                .andExpect(status().isOk())
                .andReturn();
        log.info("input first key={} body=book -> status=200 resp={}",
                key, first.getResponse().getContentAsString());

        MvcResult conflict = mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", tenant)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"phone\"}"))
                .andExpect(status().isConflict())
                .andReturn();
        String body = conflict.getResponse().getContentAsString();
        log.info("input same key={} body=phone -> status=409 resp={}", key, body);
        assertTrue(body.contains("IDEMPOTENCY_CONFLICT"), "冲突必须给出稳定可区分原因");

        MvcResult replay = mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", tenant)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"book\"}"))
                .andExpect(status().isOk())
                .andReturn();
        log.info("input same key={} body=book again -> status=200 replay={} resp={}",
                key, replay.getResponse().getHeader("X-Idempotent-Replay"),
                replay.getResponse().getContentAsString());
        assertEquals("true", replay.getResponse().getHeader("X-Idempotent-Replay"));
    }

    @Test
    void mutatingWithoutIdempotencyKeyIsRejected() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/orders")
                        .header("X-Tenant-Id", "t-healthy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        log.info("input POST without Idempotency-Key -> status=400 body={}",
                r.getResponse().getContentAsString());
        assertTrue(r.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_MISSING"));
    }

    private static String extractOrderId(String body) {
        int i = body.indexOf("\"orderId\":");
        if (i < 0) {
            return "<none>";
        }
        int start = i + "\"orderId\":".length();
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == ' ')) {
            end++;
        }
        return body.substring(start, end).trim();
    }
}
