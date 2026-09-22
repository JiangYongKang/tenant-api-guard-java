package com.github.highcumontoa.tenantapiguardjava.bulkhead;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.model.BulkheadDecision;
import com.github.highcumontoa.tenantapiguardjava.model.RejectReason;

/**
 * 按租户的并发舱壁 + 全局资源信号量。
 *
 * <p>确定性保证：</p>
 * <ul>
 *   <li>每个租户独立槽位计数与锁，单租户的突发流量只会在自己的舱壁处得到
 *       {@link RejectReason#BULKHEAD_FULL}，不影响其他租户的判定；</li>
 *   <li>{@code tryAcquire} 完全非阻塞、不排队，满了立即拒绝，绝不静默丢弃或让请求悬停；</li>
 *   <li>先占用租户槽、再占用全局信号量；全局资源耗尽时回滚租户槽并返回
 *       {@link RejectReason#RESOURCE_PRESSURE}，这是明确可控的降级结论；</li>
 *   <li>并发上限配置调整对新到请求立即生效，但不会强制中断已在执行的请求。</li>
 * </ul>
 */
@Service
public class TenantBulkheadService {

    private static final Logger log = LoggerFactory.getLogger(TenantBulkheadService.class);

    private final GuardProperties properties;
    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();
    private final Semaphore globalPermits;

    public TenantBulkheadService(GuardProperties properties) {
        this.properties = properties;
        this.globalPermits = new Semaphore(Math.max(1, properties.getGlobalMaxConcurrent()));
    }

    /**
     * 非阻塞占用一个并发槽。
     */
    public BulkheadDecision tryAcquire(String tenant) {
        int max = properties.maxConcurrentOf(tenant);
        if (max <= 0) {
            log.info("bulkhead decision input={{}}, basis=maxConcurrent<=0, decision=REJECT reason=TENANT_NOT_CONFIGURED",
                    tenant);
            return BulkheadDecision.rejected(tenant, RejectReason.TENANT_NOT_CONFIGURED, 0, 0);
        }

        Slot slot = slots.computeIfAbsent(tenant, t -> new Slot(max));
        synchronized (slot.lock) {
            slot.maxPermits = max;
            if (slot.inUse >= slot.maxPermits) {
                log.info("bulkhead decision input={{}}, basis=tenant slots {}/}} full, decision=REJECT reason=BULKHEAD_FULL",
                        tenant, slot.inUse, slot.maxPermits);
                return BulkheadDecision.rejected(tenant, RejectReason.BULKHEAD_FULL,
                        slot.maxPermits, slot.inUse);
            }
            if (!globalPermits.tryAcquire()) {
                log.info("bulkhead decision input={{}}, basis=global permits exhausted, decision=REJECT reason=RESOURCE_PRESSURE",
                        tenant);
                return BulkheadDecision.rejected(tenant, RejectReason.RESOURCE_PRESSURE,
                        slot.maxPermits, slot.inUse);
            }
            slot.inUse++;
            log.info("bulkhead decision input={{}}, basis=tenant slots {}/}}, globalFree={}}, decision=ACQUIRE",
                    tenant, slot.inUse, slot.maxPermits, globalPermits.availablePermits());
            return BulkheadDecision.acquired(tenant, slot.maxPermits, slot.inUse);
        }
    }

    /**
     * 释放槽位；必须且只能与一次成功的 {@link #tryAcquire} 成对调用。
     */
    public void release(String tenant) {
        Slot slot = slots.get(tenant);
        if (slot == null) {
            return;
        }
        synchronized (slot.lock) {
            if (slot.inUse > 0) {
                slot.inUse--;
                globalPermits.release();
                log.info("bulkhead release input={{}}, basis=request completed, tenantSlots={}/}}, globalFree={}}",
                        tenant, slot.inUse, slot.maxPermits, globalPermits.availablePermits());
            }
        }
    }

    /** 单租户舱壁状态。 */
    private static final class Slot {
        private final Object lock = new Object();
        private int inUse;
        private int maxPermits;

        private Slot(int maxPermits) {
            this.maxPermits = maxPermits;
        }
    }
}
