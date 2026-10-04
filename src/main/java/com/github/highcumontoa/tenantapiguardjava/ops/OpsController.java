package com.github.highcumontoa.tenantapiguardjava.ops;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.highcumontoa.tenantapiguardjava.guard.TimeSource;
import com.github.highcumontoa.tenantapiguardjava.model.ApiResponse;
import com.github.highcumontoa.tenantapiguardjava.model.QuotaUsage;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaService;

/**
 * 运维只读接口（不挂在 /api/** 下，不经过入口守卫、不占用任何配额与舱壁槽位）。
 *
 * <p>{@code GET /ops/quota/{tenant}}：查询某租户当前配额用量——已用、剩余、
 * 判定所依据的时间区段与口径。查询为纯读操作，不计数、不触发窗口切换；
 * 结果与同一时刻真实请求的放行/拒绝结论一致。</p>
 */
@RestController
@RequestMapping("/ops")
public class OpsController {

    private final QuotaService quotaService;
    private final TimeSource timeSource;

    public OpsController(QuotaService quotaService, TimeSource timeSource) {
        this.quotaService = quotaService;
        this.timeSource = timeSource;
    }

    @GetMapping("/quota/{tenant}")
    public ApiResponse quotaUsage(@PathVariable String tenant) {
        QuotaUsage u = quotaService.usage(tenant, timeSource.nowMillis());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenant", u.tenant());
        data.put("mode", u.mode().name());
        data.put("windowIndex", u.windowIndex());
        data.put("segmentStartMs", u.segmentStartMs());
        data.put("segmentEndMs", u.segmentEndMs());
        data.put("consumed", u.consumed());
        data.put("limit", u.limit());
        data.put("remaining", u.remaining());
        data.put("wouldPermit", u.wouldPermit());
        data.put("reason", u.reason() == null ? null : u.reason().code());
        return ApiResponse.ok(data);
    }
}
