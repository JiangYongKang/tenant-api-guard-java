package com.github.highcumontoa.tenantapiguardjava.idempotency;

import com.github.highcumontoa.tenantapiguardjava.common.RejectReason;
import com.github.highcumontoa.tenantapiguardjava.common.TimeSource;
import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.exception.GuardException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 写操作幂等服务。
 *
 * <p>存储键为 (租户, 幂等标识),租户之间互不可见。每条记录有三种状态:
 * <ul>
 *   <li>在途(in-flight):首次请求正在执行,携带 future,并发重复请求有界等待其结论;</li>
 *   <li>已完成:保存响应快照与创建时间,重复提交回放完全一致的结果;</li>
 *   <li>冲突:相同标识但内容摘要不同,立即以 {@link RejectReason#IDEMPOTENCY_CONFLICT} 拒绝。</li>
 * </ul>
 *
 * <p>过期语义:已完成记录存活至少 ttlMillis;过期记录会被惰性清理或定时清理,
 * 清理后同一标识可作为全新提交(可能再次产生业务效果),这是确定且可解释的。
 * 容量达到 maxRecords 时先尽力清理过期记录,仍不足则以
 * {@link RejectReason#IDEMPOTENCY_STORE_FULL} 拒绝,不静默丢弃。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final GuardProperties properties;
    private final TimeSource timeSource;
    private final Object[] stripes;
    private final ConcurrentHashMap<String, Record> records = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    public IdempotencyService(GuardProperties properties, TimeSource timeSource) {
        this.properties = properties;
        this.timeSource = timeSource;
        int stripeCount = Math.max(1, properties.getIdempotency().getLockStripes());
        this.stripes = new Object[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = new Object();
        }
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idempotency-purge");
            t.setDaemon(true);
            return t;
        });
        long interval = properties.getIdempotency().getPurgeIntervalMillis();
        if (interval > 0) {
            cleaner.scheduleAtFixedRate(this::purgeExpired, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    /** 长度前缀拼接,保证不同 (租户,标识) 不会产生键碰撞。 */
    private static String compositeKey(String tenant, String key) {
        return tenant.length() + ":" + tenant + ":" + key;
    }

    private Object lockFor(String compositeKey) {
        int index = Math.floorMod(compositeKey.hashCode(), stripes.length);
        return stripes[index];
    }

    /**
     * 进入幂等检查。contentHash 为请求内容(体)的稳定摘要。
     */
    public IdempotencyOutcome begin(String tenant, String key, String contentHash) {
        String ck = compositeKey(tenant, key);
        synchronized (lockFor(ck)) {
            long now = timeSource.nowMillis();
            Record existing = records.get(ck);

            if (existing != null && !existing.inflight && isExpired(existing, now)) {
                log.info("idempotency expired tenant={} key={} createdAt={} ttlMillis={}",
                        tenant, key, existing.createdAt, properties.getIdempotency().getTtlMillis());
                records.remove(ck);
                existing = null;
            }

            if (existing == null) {
                if (records.size() >= properties.getIdempotency().getMaxRecords()
                        && !evictOneExpired(now)) {
                    log.warn("idempotency reject reason={} storeSize={} maxRecords={}",
                            RejectReason.IDEMPOTENCY_STORE_FULL.code(),
                            records.size(), properties.getIdempotency().getMaxRecords());
                    throw new GuardException(RejectReason.IDEMPOTENCY_STORE_FULL);
                }
                records.put(ck, new Record(contentHash, now));
                log.info("idempotency new tenant={} key={} contentHash={}", tenant, key, contentHash);
                return new IdempotencyOutcome.New(key);
            }

            if (!existing.contentHash.equals(contentHash)) {
                log.warn("idempotency reject reason={} tenant={} key={} expectedHash={} actualHash={}",
                        RejectReason.IDEMPOTENCY_CONFLICT.code(), tenant, key,
                        existing.contentHash, contentHash);
                return new IdempotencyOutcome.Conflict(key);
            }

            if (!existing.inflight) {
                log.info("idempotency replay tenant={} key={} status={}",
                        tenant, key, existing.response.status());
                return new IdempotencyOutcome.Replay(key, existing.response);
            }
        }

        // 在途:释放分段锁后有界等待首次请求的结论,等待期间不执行任何业务副作用。
        return awaitInflight(tenant, key, ck, contentHash);
    }

    private IdempotencyOutcome awaitInflight(String tenant, String key, String ck, String contentHash) {
        CompletableFuture<StoredResponse> future;
        synchronized (lockFor(ck)) {
            Record current = records.get(ck);
            if (current == null || !current.inflight) {
                return begin(tenant, key, contentHash);
            }
            future = current.future;
        }

        long timeoutMillis = properties.getIdempotency().getInflightTimeoutMillis();
        try {
            StoredResponse response = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            log.info("idempotency replay-after-wait tenant={} key={} status={}",
                    tenant, key, response.status());
            return new IdempotencyOutcome.Replay(key, response);
        } catch (TimeoutException e) {
            log.warn("idempotency reject reason={} tenant={} key={} timeoutMillis={}",
                    RejectReason.IDEMPOTENCY_INFLIGHT_TIMEOUT.code(), tenant, key, timeoutMillis);
            throw new GuardException(RejectReason.IDEMPOTENCY_INFLIGHT_TIMEOUT,
                    "key=" + key + ", timeoutMillis=" + timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GuardException(RejectReason.IDEMPOTENCY_INFLIGHT_TIMEOUT, "interrupted");
        } catch (ExecutionException e) {
            throw new GuardException(RejectReason.IDEMPOTENCY_INFLIGHT_TIMEOUT,
                    String.valueOf(e.getCause()));
        }
    }

    /** 首次请求成功完成后保存响应并唤醒所有等待者。 */
    public void complete(String tenant, String key, StoredResponse response) {
        String ck = compositeKey(tenant, key);
        CompletableFuture<StoredResponse> future;
        synchronized (lockFor(ck)) {
            Record record = records.get(ck);
            if (record == null || !record.inflight) {
                log.warn("idempotency complete ignored (record missing or not inflight) tenant={} key={}",
                        tenant, key);
                return;
            }
            record.response = response;
            record.inflight = false;
            future = record.future;
        }
        future.complete(response);
        log.info("idempotency completed tenant={} key={} status={} storeSize={}",
                tenant, key, response.status(), records.size());
    }

    /** 首次请求处理失败时释放在途状态,后续相同请求作为全新提交重试。 */
    public void fail(String tenant, String key) {
        String ck = compositeKey(tenant, key);
        CompletableFuture<StoredResponse> future;
        synchronized (lockFor(ck)) {
            Record record = records.get(ck);
            if (record == null || !record.inflight) {
                return;
            }
            records.remove(ck);
            future = record.future;
        }
        future.cancel(false);
        log.info("idempotency failed-and-released tenant={} key={}", tenant, key);
    }

    /** 当前有效记录数(含在途),用于测试与运维观测。 */
    public int activeRecordCount() {
        return records.size();
    }

    private boolean isExpired(Record record, long now) {
        return now - record.createdAt >= properties.getIdempotency().getTtlMillis();
    }

    /** 容量打满时尽力逐出一条已完成且过期的记录;在途记录不会被逐出。 */
    private boolean evictOneExpired(long now) {
        for (Map.Entry<String, Record> entry : records.entrySet()) {
            String ck = entry.getKey();
            synchronized (lockFor(ck)) {
                Record r = records.get(ck);
                if (r != null && !r.inflight && isExpired(r, now)) {
                    records.remove(ck);
                    log.info("idempotency lazy-evict key={}", ck);
                    return true;
                }
            }
        }
        return false;
    }

    void purgeExpired() {
        long now = timeSource.nowMillis();
        int removed = 0;
        Iterator<Map.Entry<String, Record>> it = records.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Record> entry = it.next();
            String ck = entry.getKey();
            synchronized (lockFor(ck)) {
                Record r = records.get(ck);
                if (r != null && !r.inflight && isExpired(r, now)) {
                    records.remove(ck);
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("idempotency purge removed={} remaining={}", removed, records.size());
        }
    }

    @PreDestroy
    public void shutdown() {
        cleaner.shutdownNow();
    }

    /** 幂等记录:在途时 future 用于并发等待,完成后 response 保存回放快照。 */
    private static final class Record {
        final String contentHash;
        final long createdAt;
        final CompletableFuture<StoredResponse> future;
        volatile boolean inflight = true;
        volatile StoredResponse response;

        Record(String contentHash, long createdAt) {
            this.contentHash = contentHash;
            this.createdAt = createdAt;
            this.future = new CompletableFuture<>();
        }
    }
}
