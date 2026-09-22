package com.github.highcumontoa.tenantapiguardjava.quota;

/**
 * 配额判定结果。allowed=false 时 retryAfterMillis 给出建议重试等待时间。
 */
public record QuotaDecision(boolean allowed, String tenant, int limit, int used, long retryAfterMillis) {

    public static QuotaDecision allow(String tenant, int limit, int used) {
        return new QuotaDecision(true, tenant, limit, used, 0L);
    }

    public static QuotaDecision reject(String tenant, int limit, int used, long retryAfterMillis) {
        return new QuotaDecision(false, tenant, limit, used, retryAfterMillis);
    }
}
