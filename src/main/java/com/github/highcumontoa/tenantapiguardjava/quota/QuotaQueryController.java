package com.github.highcumontoa.tenantapiguardjava.quota;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.highcumontoa.tenantapiguardjava.guard.TimeSource;
import com.github.highcumontoa.tenantapiguardjava.model.ApiResponse;
import com.github.highcumontoa.tenantapiguardjava.model.QuotaUsage;

/**
 * 配额用量只读查询口子（运维观察用）。
 *
 * <p>挂在 {@code /internal/**} 下，不经过 {@code /api/**} 的守卫拦截器，因此：</p>
 * <ul>
 *   <li>查询本身<b>不占用配额</b>，也不改变任何运行时状态；</li>
 *   <li>查询结果由 {@link QuotaService#inspect} 与真实放行共用同一段锁内判定逻辑，
 *       同一时刻“查询说还能放”与“真实请求被放/被打回”严格一致。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/quota")
public class QuotaQueryController {

    private static final Logger log = LoggerFactory.getLogger(QuotaQueryController.class);

    private final QuotaService quotaService;
    private final TimeSource timeSource;

    public QuotaQueryController(QuotaService quotaService, TimeSource timeSource) {
        this.quotaService = quotaService;
        this.timeSource = timeSource;
    }

    @GetMapping
    public ApiResponse usage(@RequestParam("tenant") String tenant) {
        String t = tenant == null ? "" : tenant.trim();
        long now = timeSource.nowMillis();
        log.info("quota query input=tenant:{} now:{} basis=read-only inspect", t, now);
        QuotaUsage u = quotaService.inspect(t, now);

        Map<String, Object> interval = new LinkedHashMap<>();
        interval.put("startMs", u.windowStartMs());
        interval.put("endMs", u.windowEndMs());
        interval.put("windowIndex", u.windowIndex());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenant", u.tenant());
        data.put("mode", u.mode().name());
        data.put("used", u.consumed());
        data.put("remaining", u.remaining());
        data.put("limit", u.limit());
        data.put("interval", interval);
        data.put("wouldAllow", u.allowed());
        data.put("reason", u.reason() == null ? null : u.reason().code());
        data.put("observedAtMs", u.observedAtMs());
        return ApiResponse.ok(data);
    }
}
