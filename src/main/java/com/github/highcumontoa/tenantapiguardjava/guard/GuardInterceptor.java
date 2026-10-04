package com.github.highcumontoa.tenantapiguardjava.guard;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.github.highcumontoa.tenantapiguardjava.bulkhead.TenantBulkheadService;
import com.github.highcumontoa.tenantapiguardjava.model.BulkheadDecision;
import com.github.highcumontoa.tenantapiguardjava.model.GuardRejectedException;
import com.github.highcumontoa.tenantapiguardjava.model.QuotaDecision;
import com.github.highcumontoa.tenantapiguardjava.model.RejectReason;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 请求入口守卫，顺序确定：租户识别 → 配额判定 → 舱壁占用。
 *
 * <p>任一环节拒绝都抛出携带稳定原因的 {@link GuardRejectedException}，绝不静默放行。
 * 只有真正占用成功的舱壁槽位会在 {@link #afterCompletion} 中释放。</p>
 */
@Component
public class GuardInterceptor implements HandlerInterceptor {

    public static final String TENANT_ATTRIBUTE = "guard.tenant";
    static final String ACQUIRED_ATTRIBUTE = "guard.bulkheadAcquired";

    /** 租户标识请求头。 */
    public static final String TENANT_HEADER = "X-Tenant-Id";

    private static final Logger log = LoggerFactory.getLogger(GuardInterceptor.class);

    private final QuotaService quotaService;
    private final TenantBulkheadService bulkheadService;
    private final TimeSource timeSource;

    public GuardInterceptor(QuotaService quotaService,
                            TenantBulkheadService bulkheadService,
                            TimeSource timeSource) {
        this.quotaService = quotaService;
        this.bulkheadService = bulkheadService;
        this.timeSource = timeSource;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenant = request.getHeader(TENANT_HEADER);
        if (tenant == null || tenant.isBlank()) {
            log.info("guard input=path:{} basis=missing header:{} decision=REJECT reason=MISSING_TENANT",
                    request.getRequestURI(), TENANT_HEADER);
            throw new GuardRejectedException(RejectReason.MISSING_TENANT,
                    "missing tenant header " + TENANT_HEADER,
                    Map.of("header", TENANT_HEADER, "path", request.getRequestURI()));
        }
        tenant = tenant.trim();
        request.setAttribute(TENANT_ATTRIBUTE, tenant);

        long now = timeSource.nowMillis();
        QuotaDecision quota = quotaService.tryAcquire(tenant, now);
        if (!quota.allowed()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("tenant", tenant);
            detail.put("mode", quota.mode().name());
            detail.put("windowIndex", quota.windowIndex());
            detail.put("windowStartMs", quota.windowStartMs());
            detail.put("windowEndMs", quota.windowEndMs());
            detail.put("consumed", quota.consumed());
            detail.put("limit", quota.limit());
            log.info("guard input=tenant:{} path:{} decision=REJECT reason:{} basis=consumed {}/{} window[{},{})",
                    tenant, request.getRequestURI(), quota.reason().code(),
                    quota.consumed(), quota.limit(), quota.windowStartMs(), quota.windowEndMs());
            throw new GuardRejectedException(quota.reason(),
                    "quota rejected: " + quota.reason().code(), detail);
        }

        BulkheadDecision bulkhead = bulkheadService.tryAcquire(tenant);
        if (!bulkhead.acquired()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("tenant", tenant);
            detail.put("inUse", bulkhead.inUse());
            detail.put("maxPermits", bulkhead.maxPermits());
            log.info("guard input=tenant:{} path:{} decision=REJECT reason:{} basis=slots {}/{}",
                    tenant, request.getRequestURI(), bulkhead.reason().code(),
                    bulkhead.inUse(), bulkhead.maxPermits());
            throw new GuardRejectedException(bulkhead.reason(),
                    "bulkhead rejected: " + bulkhead.reason().code(), detail);
        }
        request.setAttribute(ACQUIRED_ATTRIBUTE, Boolean.TRUE);
        log.info("guard input=tenant:{} path:{} decision=PASS basis=quota {}/{} slots {}/{}",
                tenant, request.getRequestURI(),
                quota.consumed(), quota.limit(), bulkhead.inUse(), bulkhead.maxPermits());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (Boolean.TRUE.equals(request.getAttribute(ACQUIRED_ATTRIBUTE))) {
            String tenant = (String) request.getAttribute(TENANT_ATTRIBUTE);
            bulkheadService.release(tenant);
        }
    }
}
