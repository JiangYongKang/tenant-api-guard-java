package com.github.highcumontoa.tenantapiguardjava.demo;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.highcumontoa.tenantapiguardjava.demo.OrderService.PlaceOrderResult;
import com.github.highcumontoa.tenantapiguardjava.guard.GuardInterceptor;
import com.github.highcumontoa.tenantapiguardjava.model.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 演示接口：
 * <ul>
 *   <li>{@code POST /api/orders} 写操作，受 租户识别 → 配额 → 舱壁 三重入口保护，
 *       并以 {@code X-Idempotency-Key} 实现幂等；</li>
 *   <li>{@code GET /api/hello/{name}} 只读接口，同样受入口配额/舱壁保护。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private final OrderService orderService;

    public DemoController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    public ApiResponse placeOrder(HttpServletRequest httpRequest,
                                  @RequestHeader(value = "X-Idempotency-Key", required = false) String key,
                                  @RequestBody OrderRequest request) {
        String tenant = (String) httpRequest.getAttribute(GuardInterceptor.TENANT_ATTRIBUTE);
        PlaceOrderResult result = orderService.placeOrder(tenant, key, request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", result.body());
        data.put("replay", result.replay());
        data.put("idempotencyExpireAtMs", result.expireAtMs());
        return ApiResponse.ok(data);
    }

    @GetMapping("/hello/{name}")
    public ApiResponse hello(HttpServletRequest httpRequest, @PathVariable String name) {
        String tenant = (String) httpRequest.getAttribute(GuardInterceptor.TENANT_ATTRIBUTE);
        return ApiResponse.ok(Map.of("tenant", tenant, "name", name));
    }
}
