package com.github.highcumontoa.tenantapiguardjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接口保护的全部本地可配置项,前缀 tenant.guard。
 */
@ConfigurationProperties(prefix = "tenant.guard")
public class GuardProperties {

    /** 配额配置。 */
    private Quota quota = new Quota();

    /** 幂等配置。 */
    private Idempotency idempotency = new Idempotency();

    /** 隔离舱(并发隔离)配置。 */
    private Bulkhead bulkhead = new Bulkhead();

    public Quota getQuota() {
        return quota;
    }

    public void setQuota(Quota quota) {
        this.quota = quota;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public void setIdempotency(Idempotency idempotency) {
        this.idempotency = idempotency;
    }

    public Bulkhead getBulkhead() {
        return bulkhead;
    }

    public void setBulkhead(Bulkhead bulkhead) {
        this.bulkhead = bulkhead;
    }

    /** 滑动窗口配额。 */
    public static class Quota {
        /** 默认每租户窗口内允许的请求数。 */
        private int defaultLimit = 100;
        /** 窗口长度(毫秒)。 */
        private long windowMillis = 60_000L;
        /** 窗口内子桶数量,用于平滑限流。 */
        private int bucketsPerWindow = 6;
        /** 按租户覆盖配额,未配置的租户使用 default-limit。 */
        private Map<String, Integer> tenantLimits = new LinkedHashMap<>();

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public long getWindowMillis() {
            return windowMillis;
        }

        public void setWindowMillis(long windowMillis) {
            this.windowMillis = windowMillis;
        }

        public int getBucketsPerWindow() {
            return bucketsPerWindow;
        }

        public void setBucketsPerWindow(int bucketsPerWindow) {
            this.bucketsPerWindow = bucketsPerWindow;
        }

        public Map<String, Integer> getTenantLimits() {
            return tenantLimits;
        }

        public void setTenantLimits(Map<String, Integer> tenantLimits) {
            this.tenantLimits = tenantLimits;
        }
    }

    /** 写操作幂等。 */
    public static class Idempotency {
        /** 幂等记录保留时长(毫秒)。 */
        private long ttlMillis = 24 * 60 * 60_000L;
        /** 并发下等待同一标识首次请求完成的最长时间(毫秒)。 */
        private long inflightTimeoutMillis = 5_000L;
        /** 本地幂等记录数硬上限。 */
        private int maxRecords = 100_000;
        /** 分段锁数量。 */
        private int lockStripes = 64;
        /** 过期记录清理周期(毫秒),<=0 表示关闭定时清理。 */
        private long purgeIntervalMillis = 60_000L;

        public long getTtlMillis() {
            return ttlMillis;
        }

        public void setTtlMillis(long ttlMillis) {
            this.ttlMillis = ttlMillis;
        }

        public long getInflightTimeoutMillis() {
            return inflightTimeoutMillis;
        }

        public void setInflightTimeoutMillis(long inflightTimeoutMillis) {
            this.inflightTimeoutMillis = inflightTimeoutMillis;
        }

        public int getMaxRecords() {
            return maxRecords;
        }

        public void setMaxRecords(int maxRecords) {
            this.maxRecords = maxRecords;
        }

        public int getLockStripes() {
            return lockStripes;
        }

        public void setLockStripes(int lockStripes) {
            this.lockStripes = lockStripes;
        }

        public long getPurgeIntervalMillis() {
            return purgeIntervalMillis;
        }

        public void setPurgeIntervalMillis(long purgeIntervalMillis) {
            this.purgeIntervalMillis = purgeIntervalMillis;
        }
    }

    /** 并发隔离舱。 */
    public static class Bulkhead {
        /** 全局最大并发在处理请求数。 */
        private int globalMaxConcurrent = 1_000;
        /** 单租户默认最大并发。 */
        private int defaultTenantMaxConcurrent = 100;
        /** 按租户覆盖单租户最大并发。 */
        private Map<String, Integer> tenantMaxConcurrent = new LinkedHashMap<>();
        /** 信号量是否公平。 */
        private boolean fair = true;

        public int getGlobalMaxConcurrent() {
            return globalMaxConcurrent;
        }

        public void setGlobalMaxConcurrent(int globalMaxConcurrent) {
            this.globalMaxConcurrent = globalMaxConcurrent;
        }

        public int getDefaultTenantMaxConcurrent() {
            return defaultTenantMaxConcurrent;
        }

        public void setDefaultTenantMaxConcurrent(int defaultTenantMaxConcurrent) {
            this.defaultTenantMaxConcurrent = defaultTenantMaxConcurrent;
        }

        public Map<String, Integer> getTenantMaxConcurrent() {
            return tenantMaxConcurrent;
        }

        public void setTenantMaxConcurrent(Map<String, Integer> tenantMaxConcurrent) {
            this.tenantMaxConcurrent = tenantMaxConcurrent;
        }

        public boolean isFair() {
            return fair;
        }

        public void setFair(boolean fair) {
            this.fair = fair;
        }
    }
}
