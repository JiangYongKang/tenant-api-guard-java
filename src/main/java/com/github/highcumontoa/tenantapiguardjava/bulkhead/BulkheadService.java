package com.github.highcumontoa.tenantapiguardjava.bulkhead;

import com.github.highcumontoa.tenantapiguardjava.common.RejectReason;
import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.exception.GuardException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 租户并发隔离舱。
 *
 * <p>每个租户拥有独立信号量(突发流量仅消耗自身许可),另设全局信号量兜住本地资源。
 * 获取为非阻塞:达到任一上限立即以稳定原因拒绝,不排队、不静默丢弃。
 * 租户之间信号量互不共享,因此单租户异常流量不会影响其他租户的判定。
 */
@Service
public class BulkheadService {

    private static final Logger log = LoggerFactory.getLogger(BulkheadService.class);

    private final GuardProperties properties;
    private final Semaphore globalSemaphore;
    private final ConcurrentHashMap<String, Semaphore> tenantSemaphores = new ConcurrentHashMap<>();

    public BulkheadService(GuardProperties properties) {
        this.properties = properties;
        this.globalSemaphore = new Semaphore(
                Math.max(1, properties.getBulkhead().getGlobalMaxConcurrent()),
                properties.getBulkhead().isFair());
    }

    /**
     * 非阻塞获取并发许可;租户上限或全局上限达到时抛出携带稳定原因的异常。
     */
    public BulkheadLease acquire(String tenant) {
        Semaphore tenantSemaphore = tenantSemaphores.computeIfAbsent(tenant,
                t -> new Semaphore(tenantPermits(t), properties.getBulkhead().isFair()));

        if (!tenantSemaphore.tryAcquire()) {
            int tenantMax = tenantPermits(tenant);
            int inUse = tenantMax - tenantSemaphore.availablePermits();
            log.warn("bulkhead reject reason={} tenant={} tenantMax={} tenantInUse={}",
                    RejectReason.TENANT_BURST_LIMITED.code(), tenant, tenantMax, inUse);
            throw new GuardException(RejectReason.TENANT_BURST_LIMITED,
                    "tenant=" + tenant + ", maxConcurrent=" + tenantMax);
        }

        if (!globalSemaphore.tryAcquire()) {
            tenantSemaphore.release();
            log.warn("bulkhead reject reason={} tenant={} globalMax={}",
                    RejectReason.GLOBAL_CAPACITY_LIMITED.code(), tenant,
                    properties.getBulkhead().getGlobalMaxConcurrent());
            throw new GuardException(RejectReason.GLOBAL_CAPACITY_LIMITED,
                    "globalMaxConcurrent=" + properties.getBulkhead().getGlobalMaxConcurrent());
        }

        log.debug("bulkhead acquire tenant={} globalAvailable={}",
                tenant, globalSemaphore.availablePermits());
        return new Lease(tenant, tenantSemaphore);
    }

    /** 当前租户在处理并发数(已获取未释放的租约数)。 */
    public int activeCount(String tenant) {
        Semaphore tenantSemaphore = tenantSemaphores.get(tenant);
        if (tenantSemaphore == null) {
            return 0;
        }
        return tenantPermits(tenant) - tenantSemaphore.availablePermits();
    }

    private int tenantPermits(String tenant) {
        Integer overridden = properties.getBulkhead().getTenantMaxConcurrent().get(tenant);
        int permits = overridden != null
                ? overridden
                : properties.getBulkhead().getDefaultTenantMaxConcurrent();
        return Math.max(1, permits);
    }

    private final class Lease implements BulkheadLease {

        private final String tenant;
        private final Semaphore tenantSemaphore;
        private volatile boolean released;

        Lease(String tenant, Semaphore tenantSemaphore) {
            this.tenant = tenant;
            this.tenantSemaphore = tenantSemaphore;
        }

        @Override
        public String tenant() {
            return tenant;
        }

        @Override
        public void close() {
            if (released) {
                return;
            }
            released = true;
            globalSemaphore.release();
            tenantSemaphore.release();
            log.debug("bulkhead release tenant={} globalAvailable={}",
                    tenant, globalSemaphore.availablePermits());
        }
    }
}
