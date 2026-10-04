package com.github.highcumontoa.tenantapiguardjava.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.github.highcumontoa.tenantapiguardjava.guard.TimeSource;

/**
 * 不活跃配额状态的定时回收器。
 *
 * <p>服务长期运行时，很久不来的租户留下的运行时状态由这里周期性收回；
 * 回收语义见 {@link QuotaService#sweepInactive(long)}——只回收语义已过期的状态，
 * 不影响活跃租户的判定。</p>
 */
@Component
public class QuotaStateSweeper {

    private static final Logger log = LoggerFactory.getLogger(QuotaStateSweeper.class);

    private final QuotaService quotaService;
    private final TimeSource timeSource;

    public QuotaStateSweeper(QuotaService quotaService, TimeSource timeSource) {
        this.quotaService = quotaService;
        this.timeSource = timeSource;
    }

    @Scheduled(fixedDelayString = "${tenant.guard.sweep-interval-ms:60000}",
            initialDelayString = "${tenant.guard.sweep-interval-ms:60000}")
    public void sweep() {
        int removed = quotaService.sweepInactive(timeSource.nowMillis());
        if (removed > 0) {
            log.info("quota sweeper tick reclaimed={} tracked={}", removed, quotaService.trackedTenantCount());
        }
    }
}
