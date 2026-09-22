package com.github.highcumontoa.tenantapiguardjava.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.highcumontoa.tenantapiguardjava.guard.TimeSource;
import com.github.highcumontoa.tenantapiguardjava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantapiguardjava.model.GuardRejectedException;
import com.github.highcumontoa.tenantapiguardjava.model.IdempotencyOutcome;
import com.github.highcumontoa.tenantapiguardjava.model.RejectReason;

/**
 * 示例写业务：通过 {@link IdempotencyService} 保证同一幂等键只产生一次实际效果。
 *
 * <p>内容指纹为 {@code product|quantity} 的 SHA-256；相同键不同内容会在幂等层被判定冲突。</p>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final IdempotencyService idempotencyService;
    private final TimeSource timeSource;

    /** 实际副作用计数，仅用于测试验证“只执行一次”。 */
    private final AtomicLong executedCount = new AtomicLong();

    public OrderService(IdempotencyService idempotencyService, TimeSource timeSource) {
        this.idempotencyService = idempotencyService;
        this.timeSource = timeSource;
    }

    /**
     * 幂等下单。
     *
     * @return 带 replay 标记的结果；首次 {@code replay=false}，重复提交 {@code replay=true}
     * @throws GuardRejectedException 缺少键 / 内容冲突 / 执行中超时，原因码稳定可区分
     */
    public PlaceOrderResult placeOrder(String tenant, String key, OrderRequest request) {
        if (key == null || key.isBlank()) {
            throw new GuardRejectedException(RejectReason.MISSING_IDEMPOTENCY_KEY,
                    "write operations require X-Idempotency-Key",
                    java.util.Map.of("tenant", tenant));
        }
        String hash = contentHash(request);
        log.info("order input=tenant:{} key:{} hash:{} product:{} quantity:{}",
                tenant, key, hash, request.getProduct(), request.getQuantity());

        IdempotencyOutcome outcome = idempotencyService.execute(
                tenant, key, hash,
                () -> doPlaceOrder(tenant, request));

        switch (outcome.status()) {
            case FIRST -> {
                return new PlaceOrderResult(outcome.replayBody(), false, outcome.expireAtMs());
            }
            case REPLAY -> {
                log.info("order replay input=tenant:{} key:{} basis=identical completed request, returning first result",
                        tenant, key);
                return new PlaceOrderResult(outcome.replayBody(), true, outcome.expireAtMs());
            }
            case CONFLICT -> throw new GuardRejectedException(
                    RejectReason.IDEMPOTENT_CONTENT_CONFLICT,
                    "same idempotency key submitted with different content",
                    java.util.Map.of(
                            "tenant", tenant,
                            "idempotencyKey", key,
                            "submittedHash", hash,
                            "firstHash", outcome.contentHash() == null ? "" : outcome.contentHash()));
            case IN_PROGRESS -> throw new GuardRejectedException(
                    RejectReason.IDEMPOTENT_IN_PROGRESS,
                    "the first request with this key is still in progress; retry later for the same result",
                    java.util.Map.of(
                            "tenant", tenant,
                            "idempotencyKey", key,
                            "firstHash", outcome.contentHash() == null ? "" : outcome.contentHash()));
            default -> throw new IllegalStateException("unknown idempotency status: " + outcome.status());
        }
    }

    /** 真正产生副作用的动作；同一幂等键下全局只被调用一次。 */
    private String doPlaceOrder(String tenant, OrderRequest request) {
        long n = executedCount.incrementAndGet();
        String orderId = UUID.randomUUID().toString();
        long at = timeSource.nowMillis();
        String body = new OrderResult(orderId, tenant, request.getProduct(),
                request.getQuantity(), at).toString();
        log.info("order side-effect EXECUTED tenant:{} orderId:{} executionSeq:{}", tenant, orderId, n);
        return body;
    }

    /** 对请求内容计算稳定指纹（字段顺序确定）。 */
    public static String contentHash(OrderRequest request) {
        String canonical = "product=" + nullSafe(request.getProduct())
                + "|quantity=" + request.getQuantity();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    public long executedCount() {
        return executedCount.get();
    }

    /** 下单返回：结果体 + 是否为重放 + 幂等记录过期时刻。 */
    public record PlaceOrderResult(String body, boolean replay, long expireAtMs) {
    }
}
