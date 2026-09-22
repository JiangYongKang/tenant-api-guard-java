package com.github.highcumontoa.tenantapiguardjava.idempotency;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.guard.TimeSource;
import com.github.highcumontoa.tenantapiguardjava.model.IdempotencyOutcome;

/**
 * 写操作幂等服务（本地内存实现）。
 *
 * <p>确定性语义：</p>
 * <ol>
 *   <li><b>首次（FIRST）</b>：键不存在时，首个请求以 putIfAbsent 方式登记为 RUNNING 并执行业务，
 *       同一时刻只有一个线程能成为执行者（single-flight），业务动作仅发生一次；</li>
 *   <li><b>并发重复（等待后 REPLAY）</b>：执行者未完成时，相同内容的并发请求在该键的
 *       条件变量上等待，完成后拿到与首次<b>完全一致</b>的结果；</li>
 *   <li><b>执行中超时（IN_PROGRESS）</b>：等待超过 {@code in-progress-timeout-ms} 仍未完成时，
 *       返回确定的 {@code IDEMPOTENT_IN_PROGRESS}（409），不猜测成败、不重复执行；</li>
 *   <li><b>内容冲突（CONFLICT）</b>：相同键携带不同 contentHash，一律返回
 *       {@code IDEMPOTENT_CONTENT_CONFLICT}（409），即使首次仍在执行；</li>
 *   <li><b>过期</b>：记录存活期从首次登记时刻起算 TTL；{@code expireAt &lt;= now} 即视为不存在，
 *       过期后同键再次提交被当作全新的首次请求（可再次产生一次效果），语义确定可解释。
 *       过期记录惰性清理，也可由 {@link #purgeExpired} 主动清理；</li>
 *   <li><b>业务异常</b>：执行者抛异常时移除登记并唤醒等待者，该键可被重新提交；
 *       异常向上抛出，不会伪造成功结果。</li>
 * </ol>
 *
 * <p>所有时间判断统一来自 {@link TimeSource}，杜绝注入时间与墙钟时间混用导致的错误过期。</p>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final GuardProperties properties;
    private final TimeSource timeSource;
    private final ConcurrentHashMap<Key, Entry> records = new ConcurrentHashMap<>();

    public IdempotencyService(GuardProperties properties, TimeSource timeSource) {
        this.properties = properties;
        this.timeSource = timeSource;
    }

    /**
     * 幂等地执行带返回体的写操作。
     *
     * @param tenant      租户
     * @param key         幂等标识
     * @param contentHash 请求内容指纹
     * @param action      首次执行的业务动作
     * @return 判定结论（FIRST 已执行 / REPLAY 重放 / IN_PROGRESS / CONFLICT）
     */
    public IdempotencyOutcome execute(String tenant, String key, String contentHash,
                                      Supplier<String> action) {
        Key mapKey = new Key(tenant, key);
        long deadline = 0L; // 0 表示尚未针对当前登记实例建立等待预算

        while (true) {
            long now = timeSource.nowMillis();
            Entry existing = records.get(mapKey);
            if (existing == null) {
                deadline = 0L;
                long expireAt = now + properties.getIdempotencyTtlMs();
                Entry fresh = new Entry(contentHash, expireAt);
                if (records.putIfAbsent(mapKey, fresh) == null) {
                    return runAsFirst(mapKey, fresh, tenant, key, contentHash, expireAt, action);
                }
                continue; // 登记竞争失败，重读
            }

            // 仅在首次面对某个登记实例时建立等待预算；同一实例上的循环重查不延长预算
            if (deadline == 0L) {
                deadline = now + properties.getInProgressTimeoutMs();
            }
            synchronized (existing.lock) {
                if (records.get(mapKey) != existing) {
                    deadline = 0L;
                    continue; // 登记实例已替换，下一轮针对新实例重新建立预算
                }
                if (existing.expireAtMs <= now) {
                    records.remove(mapKey, existing);
                    log.info("idempotency expired input=tenant:{} key:{} now:{} expireAt:{} basis=expireAt<=now",
                            tenant, key, now, existing.expireAtMs);
                    deadline = 0L;
                    continue;
                }
                if (!existing.contentHash.equals(contentHash)) {
                    log.info("idempotency decision input=tenant:{} key:{} hash:{} basis=conflict with firstHash:{} decision=CONFLICT",
                            tenant, key, contentHash, existing.contentHash);
                    return IdempotencyOutcome.conflict(tenant, key, existing.contentHash,
                            existing.expireAtMs);
                }
                if (existing.done) {
                    log.info("idempotency decision input=tenant:{} key:{} hash:{} basis=completed record decision=REPLAY",
                            tenant, key, contentHash);
                    return IdempotencyOutcome.replay(tenant, key, existing.contentHash,
                            existing.resultBody, existing.expireAtMs);
                }
                long waitMs = deadline - timeSource.nowMillis();
                if (waitMs <= 0) {
                    log.info("idempotency decision input=tenant:{} key:{} hash:{} basis=first still running after timeout decision=IN_PROGRESS",
                            tenant, key, contentHash);
                    return IdempotencyOutcome.inProgress(tenant, key, existing.contentHash,
                            existing.expireAtMs);
                }
                try {
                    // 等待由首次完成（notifyAll）唤醒，或超时；不做任何业务执行
                    existing.lock.wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("idempotency wait interrupted input=tenant:{} key:{} decision=IN_PROGRESS",
                            tenant, key);
                    return IdempotencyOutcome.inProgress(tenant, key, existing.contentHash,
                            existing.expireAtMs);
                }
                // 唤醒/假性唤醒后沿同一 deadline 重查，不延长等待预算
            }
        }
    }

    /**
     * 作为唯一首次执行者运行业务，并在完成/失败时更新登记与唤醒等待者。
     */
    private IdempotencyOutcome runAsFirst(Key mapKey, Entry entry, String tenant, String key,
                                          String contentHash, long expireAt,
                                          Supplier<String> action) {
        String body;
        try {
            body = action.get();
        } catch (RuntimeException ex) {
            synchronized (entry.lock) {
                records.remove(mapKey, entry);
                entry.lock.notifyAll();
            }
            log.info("idempotency first action failed input=tenant:{} key:{} basis=action threw:{} record removed",
                    tenant, key, ex.toString());
            throw ex;
        }
        synchronized (entry.lock) {
            entry.resultBody = body;
            entry.done = true;
            entry.lock.notifyAll();
        }
        log.info("idempotency decision input=tenant:{} key:{} hash:{} basis=first execution done decision=FIRST expireAt:{}",
                tenant, key, contentHash, expireAt);
        return IdempotencyOutcome.first(tenant, key, contentHash, body, expireAt);
    }

    /**
     * 只读查询幂等状态，不执行业务、不改变 TTL。
     *
     * @return REPLAY / IN_PROGRESS / CONFLICT 结论；键不存在或已过期时返回 {@code null}
     */
    public IdempotencyOutcome inspect(String tenant, String key, String contentHash) {
        Key mapKey = new Key(tenant, key);
        long now = timeSource.nowMillis();
        Entry entry = records.get(mapKey);
        if (entry == null) {
            return null;
        }
        synchronized (entry.lock) {
            if (records.get(mapKey) != entry || entry.expireAtMs <= now) {
                return null;
            }
            if (!entry.contentHash.equals(contentHash)) {
                return IdempotencyOutcome.conflict(tenant, key, entry.contentHash, entry.expireAtMs);
            }
            if (entry.done) {
                return IdempotencyOutcome.replay(tenant, key, entry.contentHash,
                        entry.resultBody, entry.expireAtMs);
            }
            return IdempotencyOutcome.inProgress(tenant, key, entry.contentHash, entry.expireAtMs);
        }
    }

    /** 主动清理所有过期记录，返回清理条数。 */
    public int purgeExpired() {
        long now = timeSource.nowMillis();
        int removed = 0;
        for (var e : records.entrySet()) {
            Entry entry = e.getValue();
            synchronized (entry.lock) {
                if (entry.expireAtMs <= now && records.remove(e.getKey(), entry)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /** 复合键：租户 + 幂等标识。 */
    private record Key(String tenant, String key) {
    }

    /** 单个幂等登记；其 {@code lock} 同时承担状态互斥与等待/通知。 */
    private static final class Entry {
        private final Object lock = new Object();
        private final String contentHash;
        private final long expireAtMs;
        private volatile boolean done;
        private String resultBody;

        private Entry(String contentHash, long expireAtMs) {
            this.contentHash = contentHash;
            this.expireAtMs = expireAtMs;
        }
    }
}
