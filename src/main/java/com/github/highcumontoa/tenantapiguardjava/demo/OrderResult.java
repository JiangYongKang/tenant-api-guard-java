package com.github.highcumontoa.tenantapiguardjava.demo;

/**
 * 示例写操作结果（不可变）。
 */
public record OrderResult(String orderId, String tenant, String product, int quantity, long createdAtMs) {
}
