package com.github.highcumontoa.tenantapiguardjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.guard.SystemTimeSource;
import com.github.highcumontoa.tenantapiguardjava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantapiguardjava.model.IdempotencyOutcome;
import com.github.highcumontoa.tenantapiguardjava.model.IdempotentStatus;

/**
 * 幂等：并发重复提交只生效一次且结果一致、相同标识不同内容冲突、执行中超时、TTL 过期语义。
 */
class IdempotencyConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConcurrencyTest.class);

    private GuardProperties props(long ttlMs, long inProgressTimeoutMs) {
        GuardProperties p = new GuardProperties();
        p.setIdempotencyTtlMs(ttlMs);
        p.setInProgressTimeoutMs(inProgressTimeoutMs);
        return p;
    }

    @Test
    @DisplayName("并发重复提交：20 个相同请求并发，仅 1 次实际效果，全部得到与首次一致的结果")
    void concurrentDuplicatesExecuteExactlyOnce() throws Exception {
        int threads = 20;
        IdempotencyService service = new IdempotencyService(
                props(60_000, 30_000), new SystemTimeSource());
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch insideAction = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ConcurrentHashMap<IdempotentStatus, AtomicInteger> statusCount = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, AtomicInteger> bodyCount = new ConcurrentHashMap<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        String firstBody = "ORDER-1001";
        log.info("TEST input=threads:{} tenant:t1 key:K1 hash:H1 basis=barrier then concurrent execute identical requests",
                threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    IdempotencyOutcome o = service.execute("t1", "K1", "H1", () -> {
                        int seq = executions.incrementAndGet();
                        log.info("TEST side-effect EXECUTED seq:{} thread:{} basis=first request body:{}",
                                seq, Thread.currentThread().getName(), firstBody);
                        insideAction.countDown();
                        try {
                            releaseAction.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        return firstBody;
                    });
                    statusCount.computeIfAbsent(o.status(), s -> new AtomicInteger()).incrementAndGet();
                    bodyCount.computeIfAbsent(o.replayBody(), b -> new AtomicInteger()).incrementAndGet();
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            });
        }
        assertTrue(insideAction.await(5, TimeUnit.SECONDS), "首次动作必须开始执行");
        Thread.sleep(200);
        assertEquals(1, executions.get(), "首次完成前实际效果只能有一次");
        releaseAction.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "全部请求必须得到确定结论");
        assertNull(error.get(), "不应出现异常: " + error.get());

        log.info("TEST result executions:{} statusCount:{} bodyCount:{}",
                executions.get(), statusCount, bodyCount);
        assertEquals(1, executions.get(), "只允许一次实际效果");
        assertEquals(1, statusCount.getOrDefault(IdempotentStatus.FIRST, new AtomicInteger()).get());
        assertEquals(threads - 1,
                statusCount.getOrDefault(IdempotentStatus.REPLAY, new AtomicInteger()).get(),
                "其余必须为 REPLAY");
        assertEquals(threads, bodyCount.getOrDefault(firstBody, new AtomicInteger()).get(),
                "重复请求必须返回与首次完全一致的结果");
    }

    @Test
    @DisplayName("相同标识不同内容：完成后提交被明确拒绝 CONFLICT，且不污染原记录")
    void sameKeyDifferentContentIsRejected() {
        ManualTimeSource clock = new ManualTimeSource(1_000);
        IdempotencyService service = new IdempotencyService(props(60_000, 30_000), clock);
        IdempotencyOutcome first = service.execute("t1", "K2", "H-A", () -> "BODY-A");
        log.info("TEST input=tenant:t1 key:K2 hash:H-A basis=first decision:{} body:{}",
                first.status(), first.replayBody());
        assertEquals(IdempotentStatus.FIRST, first.status());

        clock.advance(1);
        IdempotencyOutcome same = service.execute("t1", "K2", "H-A", () -> {
            throw new AssertionError("重复提交不得再次执行业务");
        });
        assertEquals(IdempotentStatus.REPLAY, same.status());
        assertEquals("BODY-A", same.replayBody(), "重复请求返回首次一致结果");

        clock.advance(1);
        IdempotencyOutcome conflict = service.execute("t1", "K2", "H-B-DIFFERENT", () -> {
            throw new AssertionError("冲突内容不得产生任何效果");
        });
        log.info("TEST input=tenant:t1 key:K2 hash:H-B-DIFFERENT basis=differs from firstHash:{} decision:{}",
                conflict.contentHash(), conflict.status());
        assertEquals(IdempotentStatus.CONFLICT, conflict.status());
        assertEquals("IDEMPOTENT_CONTENT_CONFLICT", conflict.rejectReason().code());
        assertEquals(409, conflict.rejectReason().httpStatus());
        assertEquals("H-A", conflict.contentHash(), "错误结论必须携带首次内容指纹作为判定依据");

        clock.advance(1);
        IdempotencyOutcome replayAgain = service.execute("t1", "K2", "H-A", () -> "X");
        assertEquals(IdempotentStatus.REPLAY, replayAgain.status());
        assertEquals("BODY-A", replayAgain.replayBody(), "冲突不得污染原记录");
    }

    @Test
    @DisplayName("执行中提交不同内容：即使首次仍在运行也立即得到 CONFLICT")
    void conflictWhileInProgressIsImmediate() throws Exception {
        ManualTimeSource clock = new ManualTimeSource(1_000);
        IdempotencyService service = new IdempotencyService(props(60_000, 30_000), clock);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> service.execute("t1", "K2B", "H-A", () -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "BODY-A";
        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        IdempotencyOutcome conflict = service.execute("t1", "K2B", "H-OTHER", () -> {
            throw new AssertionError();
        });
        log.info("TEST input=tenant:t1 key:K2B hash:H-OTHER basis=first running but content differs decision:{}",
                conflict.status());
        assertEquals(IdempotentStatus.CONFLICT, conflict.status());
        release.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("执行中超时：手动时钟越过等待上限，重复请求得到确定的 IN_PROGRESS")
    void inProgressTimeoutIsDeterministic() throws Exception {
        ManualTimeSource clock = new ManualTimeSource(1_000);
        IdempotencyService service = new IdempotencyService(props(60_000, 80), clock);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> service.execute("t1", "K3", "H1", () -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "SLOW-BODY";
        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        // 另一线程驱动时钟越过超时点，避免 wait 在手动时钟下永久挂起
        Thread driver = new Thread(() -> {
            try {
                Thread.sleep(30);
                clock.advance(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        driver.start();
        IdempotencyOutcome waiting = service.execute("t1", "K3", "H1", () -> {
            throw new AssertionError("等待者不得执行业务");
        });
        driver.join();
        log.info("TEST input=tenant:t1 key:K3 basis=first still running and wait budget 80ms exhausted decision:{}",
                waiting.status());
        assertEquals(IdempotentStatus.IN_PROGRESS, waiting.status());
        assertEquals("IDEMPOTENT_IN_PROGRESS", waiting.rejectReason().code());
        assertEquals(409, waiting.rejectReason().httpStatus());

        // 首次随后完成；新的重复请求可以拿到一致结果
        release.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        IdempotencyOutcome replay = service.execute("t1", "K3", "H1", () -> {
            throw new AssertionError();
        });
        assertEquals(IdempotentStatus.REPLAY, replay.status());
        assertEquals("SLOW-BODY", replay.replayBody());
    }

    @Test
    @DisplayName("TTL 边界：过期前一刻为重放，expireAt 时刻起等同首次提交")
    void expiryBoundaryIsDeterministic() {
        long ttl = 1_000;
        ManualTimeSource clock = new ManualTimeSource(1_000);
        IdempotencyService service = new IdempotencyService(props(ttl, 30_000), clock);

        IdempotencyOutcome first = service.execute("t1", "K4", "H1", () -> "BODY-1");
        assertEquals(IdempotentStatus.FIRST, first.status());
        assertEquals(2_000, first.expireAtMs());
        log.info("TEST input=tenant:t1 key:K4 ttl:{} basis=first registered expireAt:{}",
                ttl, first.expireAtMs());

        clock.setTime(1_999);
        IdempotencyOutcome replay = service.execute("t1", "K4", "H1", () -> "BODY-2");
        assertEquals(IdempotentStatus.REPLAY, replay.status(), "TTL 边界前一刻仍为重放");
        assertEquals("BODY-1", replay.replayBody());

        clock.setTime(2_000);
        assertNull(service.inspect("t1", "K4", "H1"), "expireAt 时刻记录必须视为不存在");
        IdempotencyOutcome secondFirst = service.execute("t1", "K4", "H1", () -> "BODY-2");
        log.info("TEST input=tenant:t1 key:K4 now:2000 basis=expireAt<=now decision:{}",
                secondFirst.status());
        assertEquals(IdempotentStatus.FIRST, secondFirst.status(), "过期后等同首次提交，可再产生一次效果");
        assertEquals("BODY-2", secondFirst.replayBody(), "首次结论也直接携带本次结果");

        clock.setTime(2_001);
        IdempotencyOutcome replay2 = service.execute("t1", "K4", "H1", () -> "X");
        assertEquals(IdempotentStatus.REPLAY, replay2.status());
        assertEquals("BODY-2", replay2.replayBody(), "过期后产生的新记录返回其自身首次结果");
    }

    @Test
    @DisplayName("业务失败：首次动作抛异常时记录被移除，相同键可重新提交成功")
    void failedFirstCanBeRetried() {
        ManualTimeSource clock = new ManualTimeSource(1_000);
        IdempotencyService service = new IdempotencyService(props(60_000, 30_000), clock);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> service.execute("t1", "K5", "H1", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        clock.advance(1);
        IdempotencyOutcome retry = service.execute("t1", "K5", "H1", () -> {
            calls.incrementAndGet();
            return "RECOVERED";
        });
        log.info("TEST input=tenant:t1 key:K5 basis=first action failed then retried calls:{} decision:{}",
                calls.get(), retry.status());
        assertEquals(IdempotentStatus.FIRST, retry.status());
        assertEquals("RECOVERED", retry.replayBody());
        assertEquals(2, calls.get(), "失败不产生伪造成功，且允许重新提交");
    }
}
