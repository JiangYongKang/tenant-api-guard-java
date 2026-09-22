package com.github.highcumontoa.tenantapiguardjava.tests.idempotency;

import com.github.highcumontoa.tenantapiguardjava.common.RejectReason;
import com.github.highcumontoa.tenantapiguardjava.common.TimeSource;
import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.exception.GuardException;
import com.github.highcumontoa.tenantapiguardjava.idempotency.IdempotencyOutcome;
import com.github.highcumontoa.tenantapiguardjava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantapiguardjava.idempotency.StoredResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等核心场景:并发重复提交仅一次实际效果;同标识不同内容必须冲突拒绝;
 * 过期后语义确定;容量打满给出稳定原因。
 */
class IdempotencyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceTest.class);

    private static final class MutableTimeSource implements TimeSource {
        private volatile long t;

        MutableTimeSource(long start) {
            this.t = start;
        }

        @Override
        public long nowMillis() {
            return t;
        }

        void set(long v) {
            t = v;
        }
    }

    private GuardProperties props(long ttl, long inflightTimeout, int maxRecords) {
        GuardProperties p = new GuardProperties();
        p.getIdempotency().setTtlMillis(ttl);
        p.getIdempotency().setInflightTimeoutMillis(inflightTimeout);
        p.getIdempotency().setMaxRecords(maxRecords);
        p.getIdempotency().setLockStripes(4);
        p.getIdempotency().setPurgeIntervalMillis(0L);
        return p;
    }

    @Test
    void concurrentDuplicatesProduceExactlyOneEffectAndIdenticalResult() throws Exception {
        MutableTimeSource clock = new MutableTimeSource(0);
        IdempotencyService service =
                new IdempotencyService(props(10_000, 2_000, 1_000), clock);

        String tenant = "t-idem";
        String key = "order-key-1";
        String hash = "hash-A";
        int threads = 32;
        CountDownLatch firstHolding = new CountDownLatch(1);
        CountDownLatch allStarted = new CountDownLatch(threads);
        AtomicInteger newCount = new AtomicInteger();
        AtomicInteger replayCount = new AtomicInteger();
        AtomicInteger sideEffects = new AtomicInteger();
        AtomicReference<String> firstResult = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                final int seq = i;
                pool.submit(() -> {
                    allStarted.countDown();
                    IdempotencyOutcome outcome = service.begin(tenant, key, hash);
                    log.info("input tenant={} key={} seq={} -> outcome={}",
                            tenant, key, seq, outcome.getClass().getSimpleName());
                    switch (outcome) {
                        case IdempotencyOutcome.New n when seq == 0 -> {
                            newCount.incrementAndGet();
                            // 模拟首次请求持锁执行业务,期间其他线程应等待
                            sideEffects.incrementAndGet();
                            StoredResponse resp = new StoredResponse(
                                    200, ("RESULT-42").getBytes(), "application/json");
                            firstResult.set("RESULT-42");
                            try {
                                firstHolding.await(200, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                            service.complete(tenant, key, resp);
                        }
                        case IdempotencyOutcome.New n -> {
                            // 非首个线程拿到 New 是错误的
                            newCount.incrementAndGet();
                            sideEffects.incrementAndGet();
                            service.complete(tenant, key,
                                    new StoredResponse(200, "WRONG".getBytes(), "text/plain"));
                        }
                        case IdempotencyOutcome.Replay r -> {
                            replayCount.incrementAndGet();
                            String body = r.response().bodyAsString();
                            log.info("replay seq={} status={} body={}", seq, r.response().status(), body);
                            assertEquals("RESULT-42", body, "重复请求必须返回与首次一致的结果");
                            assertEquals(200, r.response().status());
                        }
                        case IdempotencyOutcome.Conflict c ->
                                throw new AssertionError("相同内容不应冲突");
                    }
                });
            }
            // 等待其余线程进入 begin 等待,再释放首次请求
            allStarted.await();
            Thread.sleep(100);
            firstHolding.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        log.info("summary newCount={} replayCount={} sideEffects={}",
                newCount.get(), replayCount.get(), sideEffects.get());
        assertEquals(1, newCount.get(), "并发下仅首次为 New");
        assertEquals(1, sideEffects.get(), "实际业务效果只能产生一次");
        assertEquals(threads - 1, replayCount.get(), "其余必须是结果一致的回放");
    }

    @Test
    void sameKeyDifferentContentIsRejectedWithStableConflictReason() {
        MutableTimeSource clock = new MutableTimeSource(0);
        IdempotencyService service =
                new IdempotencyService(props(10_000, 500, 1_000), clock);

        String key = "order-key-2";
        assertInstanceOf(IdempotencyOutcome.New.class, service.begin("t", key, "hash-A"));
        service.complete("t", key, new StoredResponse(200, "ok".getBytes(), "application/json"));

        log.info("input same key={} with different content hash-B -> expect CONFLICT", key);
        IdempotencyOutcome conflict = service.begin("t", key, "hash-B");
        assertInstanceOf(IdempotencyOutcome.Conflict.class, conflict);

        // 相同内容仍可正常回放,冲突提交不得污染既有记录
        IdempotencyOutcome replay = service.begin("t", key, "hash-A");
        assertInstanceOf(IdempotencyOutcome.Replay.class, replay);
        assertEquals("ok", ((IdempotencyOutcome.Replay) replay).response().bodyAsString());
        log.info("conflict did not poison stored record; replay still returns original body");
    }

    @Test
    void expiredRecordAllowsFreshSubmissionWithDeterministicSemantics() {
        MutableTimeSource clock = new MutableTimeSource(0);
        IdempotencyService service =
                new IdempotencyService(props(100, 500, 1_000), clock);

        String key = "order-key-ttl";
        service.begin("t", key, "h");
        service.complete("t", key, new StoredResponse(200, "first".getBytes(), "application/json"));

        clock.set(99);
        IdempotencyOutcome beforeTtl = service.begin("t", key, "h");
        log.info("input t=99 (<ttl=100) -> {}", beforeTtl.getClass().getSimpleName());
        assertInstanceOf(IdempotencyOutcome.Replay.class, beforeTtl);

        clock.set(100);
        IdempotencyOutcome afterTtl = service.begin("t", key, "h");
        log.info("input t=100 (>=ttl=100) -> {} (record expired, treated as fresh submission)",
                afterTtl.getClass().getSimpleName());
        assertInstanceOf(IdempotencyOutcome.New.class, afterTtl,
                "过期后同一标识语义确定:视为全新提交,可能再次产生业务效果");
    }

    @Test
    void storeFullIsRejectedWithStableReason() {
        MutableTimeSource clock = new MutableTimeSource(0);
        IdempotencyService service =
                new IdempotencyService(props(100_000, 500, 2), clock);

        service.begin("t", "k1", "h1");
        service.complete("t", "k1", new StoredResponse(200, "x".getBytes(), "text/plain"));
        service.begin("t", "k2", "h2");
        service.complete("t", "k2", new StoredResponse(200, "y".getBytes(), "text/plain"));

        GuardException ex = assertThrows(GuardException.class,
                () -> service.begin("t", "k3", "h3"));
        log.info("input storeSize=2 maxRecords=2 -> reason={}", ex.reason().code());
        assertEquals(RejectReason.IDEMPOTENCY_STORE_FULL, ex.reason());
    }

    @Test
    void inflightWaitTimeoutHasStableReason() {
        MutableTimeSource clock = new MutableTimeSource(0);
        IdempotencyService service =
                new IdempotencyService(props(100_000, 50, 1_000), clock);

        assertInstanceOf(IdempotencyOutcome.New.class, service.begin("t", "slow", "h"));
        // 不调用 complete,首次请求始终在途
        long start = System.currentTimeMillis();
        GuardException ex = assertThrows(GuardException.class,
                () -> service.begin("t", "slow", "h"));
        long elapsed = System.currentTimeMillis() - start;
        log.info("input inflight key=slow -> reason={} elapsedMillis={}", ex.reason().code(), elapsed);
        assertEquals(RejectReason.IDEMPOTENCY_INFLIGHT_TIMEOUT, ex.reason());
    }
}
