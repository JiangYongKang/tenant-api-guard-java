package com.github.highcumontoa.tenantapiguardjava.guard;

import org.springframework.stereotype.Component;

/**
 * 基于系统时钟的时间源。
 */
@Component
public class SystemTimeSource implements TimeSource {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
