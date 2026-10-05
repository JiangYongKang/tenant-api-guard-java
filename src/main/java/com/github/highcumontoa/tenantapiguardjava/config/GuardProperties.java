package com.github.highcumontoa.tenantapiguardjava.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.github.highcumontoa.tenantapiguardjava.quota.QuotaMode;

/**
 * 本地可配置项（application.properties / YAML），支持按租户调整。
 *
 * <p>前缀 {@code tenant.guard}。配额判定每次都读取最新配置：配置变更后下一笔判定即按
 * 新上限执行（不回溯已放行记录，但不存在旧上限缓存导致的多放/少放）。</p>
 */
@ConfigurationProperties(prefix = "tenant.guard")
public class GuardProperties {

    /** 单个配额时间窗口长度（毫秒）。 */
    private long windowMs = 1000L;

    /** 未在 tenants 中单独配置时使用的默认每窗口配额；&lt;=0 表示默认拒绝。 */
    private long defaultQuota = 0L;

    /** 未在 tenants 中单独配置时使用的默认定额口径；默认 FIXED，保持历史行为不变。 */
    private QuotaMode defaultQuotaMode = QuotaMode.FIXED;

    /** 幂等记录存活时间（毫秒），过期后同一标识视为首次提交。 */
    private long idempotencyTtlMs = 300_000L;

    /** 未单独配置时的默认每租户并发槽上限；&lt;=0 表示默认拒绝。 */
    private int defaultMaxConcurrent = 0;

    /** 全局并发信号量上限，用于本地资源紧张时的最终可控降级。 */
    private int globalMaxConcurrent = 1024;

    /** 首次请求处于执行中时，重复请求的等待/执行超时（毫秒）。 */
    private long inProgressTimeoutMs = 10_000L;

    /**
     * 配额运行时状态空闲回收阈值（毫秒）：某租户超过该时长没有任何配额请求时，
     * 其运行时状态可被后台回收；&lt;=0 表示不主动回收。默认 30 分钟。
     */
    private long quotaIdleEvictMs = 1_800_000L;

    /** 空闲状态后台扫描间隔（毫秒）。默认 5 分钟。 */
    private long quotaEvictIntervalMs = 300_000L;

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

    public QuotaMode getDefaultQuotaMode() {
        return defaultQuotaMode;
    }

    public void setDefaultQuotaMode(QuotaMode defaultQuotaMode) {
        this.defaultQuotaMode = defaultQuotaMode == null ? QuotaMode.FIXED : defaultQuotaMode;
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

    public long getQuotaIdleEvictMs() {
        return quotaIdleEvictMs;
    }

    public void setQuotaIdleEvictMs(long quotaIdleEvictMs) {
        this.quotaIdleEvictMs = quotaIdleEvictMs;
    }

    public long getQuotaEvictIntervalMs() {
        return quotaEvictIntervalMs;
    }

    public void setQuotaEvictIntervalMs(long quotaEvictIntervalMs) {
        this.quotaEvictIntervalMs = quotaEvictIntervalMs;
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
     * 读取租户的配额口径；未配置时回落到默认口径（默认 FIXED，老租户行为不变）。
     */
    public synchronized QuotaMode quotaModeOf(String tenant) {
        TenantConfig cfg = tenants.get(tenant);
        if (cfg != null && cfg.getQuotaMode() != null) {
            return cfg.getQuotaMode();
        }
        return defaultQuotaMode;
    }

    /**
     * 原子读取租户的配额设置快照（口径 + 上限），避免配置变更瞬间读到错配组合。
     */
    public synchronized TenantQuotaSetting quotaSettingOf(String tenant) {
        TenantConfig cfg = tenants.get(tenant);
        QuotaMode mode = (cfg != null && cfg.getQuotaMode() != null)
                ? cfg.getQuotaMode() : defaultQuotaMode;
        long quota = (cfg != null && cfg.getQuota() != null)
                ? cfg.getQuota() : defaultQuota;
        return new TenantQuotaSetting(mode, quota);
    }

    /** 单个租户的配额设置快照。 */
    public record TenantQuotaSetting(QuotaMode mode, long limit) {
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
     * 单个租户的本地配置。
     */
    public static class TenantConfig {
        /** 每时间窗口配额；为 null 时回落默认配额。 */
        private Long quota;
        /** 配额口径；为 null 时回落默认口径（FIXED）。 */
        private QuotaMode quotaMode;
        /** 并发槽上限；为 null 时回落默认值。 */
        private Integer maxConcurrent;

        public Long getQuota() {
            return quota;
        }

        public void setQuota(Long quota) {
            this.quota = quota;
        }

        public QuotaMode getQuotaMode() {
            return quotaMode;
        }

        public void setQuotaMode(QuotaMode quotaMode) {
            this.quotaMode = quotaMode;
        }

        public Integer getMaxConcurrent() {
            return maxConcurrent;
        }

        public void setMaxConcurrent(Integer maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
        }
    }
}
