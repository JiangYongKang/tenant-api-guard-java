package com.github.highcumontoa.tenantapiguardjava.guard;

/**
 * 时间源抽象，便于在测试中精确控制窗口与 TTL 边界。
 */
public interface TimeSource {
    long nowMillis();
}
