package com.github.highcumontoa.tenantapiguardjava.idempotency;

/**
 * 幂等检查的三类确定结论:
 * NEW 首次提交,由调用方执行实际处理;REPLAY 重复提交,回放 stored;
 * CONFLICT 同标识但内容不一致。
 */
public sealed interface IdempotencyOutcome {

    String key();

    record New(String key) implements IdempotencyOutcome {
    }

    record Replay(String key, StoredResponse response) implements IdempotencyOutcome {
    }

    record Conflict(String key) implements IdempotencyOutcome {
    }
}
