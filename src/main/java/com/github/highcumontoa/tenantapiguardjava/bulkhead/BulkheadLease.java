package com.github.highcumontoa.tenantapiguardjava.bulkhead;

/**
 * 隔离舱租约,请求处理结束时必须 close 归还许可。
 */
public interface BulkheadLease extends AutoCloseable {

    String tenant();

    @Override
    void close();
}
