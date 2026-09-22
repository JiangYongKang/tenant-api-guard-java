package com.github.highcumontoa.tenantapiguardjava.tests;

import java.util.concurrent.atomic.AtomicLong;

import com.github.highcumontoa.tenantapiguardjava.guard.TimeSource;

/**
 * 测试用可控时间源：时间只在显式调用 {@link #advance} / {@link #setTime} 时前进。
 */
class ManualTimeSource implements TimeSource {

    private final AtomicLong now;

    ManualTimeSource(long initial) {
        this.now = new AtomicLong(initial);
    }

    @Override
    public long nowMillis() {
        return now.get();
    }

    void setTime(long t) {
        now.set(t);
    }

    void advance(long deltaMs) {
        now.addAndGet(deltaMs);
    }
}
