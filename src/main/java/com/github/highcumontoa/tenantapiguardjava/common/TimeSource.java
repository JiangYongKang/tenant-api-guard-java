package com.github.highcumontoa.tenantapiguardjava.common;

/**
 * 时间源抽象,便于窗口边界测试使用可控时间。
 */
public interface TimeSource {

    long nowMillis();

    /** 生产环境使用的系统时间源。 */
    class SystemTimeSource implements TimeSource {
        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }
    }
}
