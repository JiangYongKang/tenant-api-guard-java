package com.github.highcumontoa.tenantapiguardjava.model;

/**
 * 幂等记录的生命周期状态。
 */
public enum IdempotentStatus {
    /** 首次出现，调用方应当执行业务并完成记录。 */
    FIRST,
    /** 已有相同内容的完成结果，直接复用。 */
    REPLAY,
    /** 首次请求仍在执行，并发重复请求的确定结论。 */
    IN_PROGRESS,
    /** 相同标识但内容哈希不一致，明确拒绝。 */
    CONFLICT
}
