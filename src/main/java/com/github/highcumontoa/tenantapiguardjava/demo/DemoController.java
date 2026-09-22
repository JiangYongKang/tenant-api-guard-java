package com.github.highcumontoa.tenantapiguardjava.demo;

import com.github.highcumontoa.tenantapiguardjava.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 受保护的演示接口:读接口受配额与隔离保护,写接口额外受幂等保护。
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private final AtomicLong ordersCreated = new AtomicLong();

    @GetMapping("/ping")
    public ApiResponse ping() {
        return ApiResponse.ok(Map.of("pong", true));
    }

    @PostMapping("/orders")
    public ApiResponse createOrder(@RequestBody(required = false) Map<String, Object> body) {
        long id = ordersCreated.incrementAndGet();
        return ApiResponse.ok(Map.of("orderId", id, "received", body == null ? Map.of() : body));
    }
}
