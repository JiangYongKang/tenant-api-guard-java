package com.github.highcumontoa.tenantapiguardjava.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.github.highcumontoa.tenantapiguardjava.model.WindowMode;

/**
 * 本地可配置项（application.properties / YAML），支持按租户调整。
 *
 * <p>前缀 {@code tenant.guard}。配置变更后，配额在下一个时间窗口边界平滑生效，
 * 不会回溯改写当前窗口的计数。</p>
 */
@ConfigurationProperties(prefix = "tenant.guard")
public class GuardProperties {

    /** 单个配额时间窗口长度（毫秒）。 */
    private long windowMs = 1000L;

    /** 未在 tenants 中单独配置时使用的默认每窗口配额；&lt;=0 表示默认拒绝。 */
    private long defaultQuota = 0L;

    /** 幂等记录存活时间（毫秒），过期后同一标识视为首次提交。 */
    private long idempotencyTtlMs = 300_000L;

    /** 未单独配置时的默认每租户并发槽上限；&lt;=0 表示默认拒绝。 */
    private int defaultMaxConcurrent = 0;

    /** 全局并发信号量上限，用于本地资源紧张时的最终可控降级。 */
    private int globalMaxConcurrent = 1024;

    /** 首次请求处于执行中时，重复请求的等待/执行超时（毫秒）。 */
    private long inProgressTimeoutMs = 10_000L;

    /**
     * 配额运行时状态的不活跃回收阈值（毫秒）。租户超过该时长没有任何请求后，
     * 其配额状态可被回收。实际生效阈值为 max(stateIdleMs, windowMs)，
     * 保证回收永远发生在状态语义过期之后，不影响活跃租户的判定。
     */
    private long stateIdleMs = 600_000L;

    /** 不活跃状态定时回收的执行间隔（毫秒）。 */
    private long sweepIntervalMs = 60_000L;

    /** 按租户配置：key 为租户标识。 */
    private Map<String, TenantConfig> tenants = new LinkedHashMap<>();

    public long getWindowMs() {
        return windowMs;
    }

    public void setWindowMs(long windowMs) {
        this.windowMs = windowMs;
    }

    public long getDefaultQuota() {
        return defaultQuota;
    }

    public void setDefaultQuota(long defaultQuota) {
        this.defaultQuota = defaultQuota;
    }

    public long getIdempotencyTtlMs() {
        return idempotencyTtlMs;
    }

    public void setIdempotencyTtlMs(long idempotencyTtlMs) {
        this.idempotencyTtlMs = idempotencyTtlMs;
    }

    public int getDefaultMaxConcurrent() {
        return defaultMaxConcurrent;
    }

    public void setDefaultMaxConcurrent(int defaultMaxConcurrent) {
        this.defaultMaxConcurrent = defaultMaxConcurrent;
    }

    public int getGlobalMaxConcurrent() {
        return globalMaxConcurrent;
    }

    public void setGlobalMaxConcurrent(int globalMaxConcurrent) {
        this.globalMaxConcurrent = globalMaxConcurrent;
    }

    public long getInProgressTimeoutMs() {
        return inProgressTimeoutMs;
    }

    public void setInProgressTimeoutMs(long inProgressTimeoutMs) {
        this.inProgressTimeoutMs = inProgressTimeoutMs;
    }

    public long getStateIdleMs() {
        return stateIdleMs;
    }

    public void setStateIdleMs(long stateIdleMs) {
        this.stateIdleMs = stateIdleMs;
    }

    public long getSweepIntervalMs() {
        return sweepIntervalMs;
    }

    public void setSweepIntervalMs(long sweepIntervalMs) {
        this.sweepIntervalMs = sweepIntervalMs;
    }

    public Map<String, TenantConfig> getTenants() {
        return tenants;
    }

    public void setTenants(Map<String, TenantConfig> tenants) {
        this.tenants = tenants;
    }

    /**
     * 读取租户的每窗口配额；未配置时回落到默认配额。
     */
    public synchronized long quotaOf(String tenant) {
        TenantConfig cfg = tenants.get(tenant);
        if (cfg != null && cfg.getQuota() != null) {
            return cfg.getQuota();
        }
        return defaultQuota;
    }

    /**
     * 读取租户的并发槽上限；未配置时回落到默认值。
     */
    public synchronized int maxConcurrentOf(String tenant) {
        TenantConfig cfg = tenants.get(tenant);
        if (cfg != null && cfg.getMaxConcurrent() != null) {
            return cfg.getMaxConcurrent();
        }
        return defaultMaxConcurrent;
    }

    /**
     * 读取租户的配额口径；未配置时回落到 {@link WindowMode#FIXED}（保持既有行为）。
     */
    public synchronized WindowMode windowModeOf(String tenant) {
        TenantConfig cfg = tenants.get(tenant);
        if (cfg != null && cfg.getWindowMode() != null) {
            return cfg.getWindowMode();
        }
        return WindowMode.FIXED;
    }

    /**
     * 单个租户的本地配置。
     */
    public static class TenantConfig {
        /** 每时间窗口配额；为 null 时回落默认配额。 */
        private Long quota;
        /** 并发槽上限；为 null 时回落默认值。 */
        private Integer maxConcurrent;
        /** 配额口径（FIXED/SLIDING）；为 null 时回落 FIXED。 */
        private WindowMode windowMode;

        public Long getQuota() {
            return quota;
        }

        public void setQuota(Long quota) {
            this.quota = quota;
        }

        public Integer getMaxConcurrent() {
            return maxConcurrent;
        }

        public void setMaxConcurrent(Integer maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
        }

        public WindowMode getWindowMode() {
            return windowMode;
        }

        public void setWindowMode(WindowMode windowMode) {
            this.windowMode = windowMode;
        }
    }
}
