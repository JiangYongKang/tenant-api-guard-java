package com.github.highcumontoa.tenantapiguardjava.model;

/**
 * 幂等判定结论（不可变）。
 *
 * @param tenant        租户标识
 * @param key           幂等标识
 * @param status        生命周期状态
 * @param contentHash   首次提交内容的哈希（用于解释冲突依据）
 * @param replayBody    {@link IdempotentStatus#REPLAY} 时的首次结果
 * @param rejectReason  {@link IdempotentStatus#CONFLICT} / IN_PROGRESS 的稳定原因
 * @param expireAtMs    记录过期时间（epoch 毫秒）；过期后等同首次，语义确定
 */
public record IdempotencyOutcome(
        String tenant,
        String key,
        IdempotentStatus status,
        String contentHash,
        String replayBody,
        RejectReason rejectReason,
        long expireAtMs) {

    public static IdempotencyOutcome first(String tenant, String key, String hash,
                                           String body, long expireAtMs) {
        return new IdempotencyOutcome(tenant, key, IdempotentStatus.FIRST, hash, body, null, expireAtMs);
    }

    public static IdempotencyOutcome replay(String tenant, String key, String hash,
                                            String body, long expireAtMs) {
        return new IdempotencyOutcome(tenant, key, IdempotentStatus.REPLAY, hash, body, null, expireAtMs);
    }

    public static IdempotencyOutcome inProgress(String tenant, String key, String hash, long expireAtMs) {
        return new IdempotencyOutcome(tenant, key, IdempotentStatus.IN_PROGRESS, hash, null,
                RejectReason.IDEMPOTENT_IN_PROGRESS, expireAtMs);
    }

    public static IdempotencyOutcome conflict(String tenant, String key, String hash, long expireAtMs) {
        return new IdempotencyOutcome(tenant, key, IdempotentStatus.CONFLICT, hash, null,
                RejectReason.IDEMPOTENT_CONTENT_CONFLICT, expireAtMs);
    }
}
