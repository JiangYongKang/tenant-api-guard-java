package com.github.highcumontoa.tenantapiguardjava.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.github.highcumontoa.tenantapiguardjava.config.GuardProperties;
import com.github.highcumontoa.tenantapiguardjava.guard.TimeSource;

/**
 * 配额运行时状态的空闲回收任务。
 *
 * <p>按配置周期扫描，把长时间无请求且回收后不影响判定的租户状态收回来，避免长期运行时
 * 不活跃租户的状态无限堆积。扫描对活跃租户的锁只做非阻塞尝试，绝不拖慢在线判定。</p>
 */
@Component
public class QuotaStateEvictor {

    private static final Logger log = LoggerFactory.getLogger(QuotaStateEvictor.class);

    private final QuotaService quotaService;
    private final GuardProperties properties;
    private final TimeSource timeSource;

    public QuotaStateEvictor(QuotaService quotaService, GuardProperties properties,
                             TimeSource timeSource) {
        this.quotaService = quotaService;
        this.properties = properties;
        this.timeSource = timeSource;
    }

    @Scheduled(fixedDelayString = "${tenant.guard.quota-evict-interval-ms:300000}")
    public void sweep() {
        long idleMs = properties.getQuotaIdleEvictMs();
        if (idleMs <= 0) {
            return;
        }
        long now = timeSource.nowMillis();
        int removed = quotaService.evictIdle(now, idleMs);
        log.info("quota evictor sweep input=now:{} idleMs:{} basis=periodic scan removed:{}",
                now, idleMs, removed);
    }
}
