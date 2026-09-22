package com.github.highcumontoa.tenantapiguardjava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.tenantapiguardjava.bulkhead.BulkheadLease;
import com.github.highcumontoa.tenantapiguardjava.bulkhead.BulkheadService;
import com.github.highcumontoa.tenantapiguardjava.common.ApiResponse;
import com.github.highcumontoa.tenantapiguardjava.common.RejectReason;
import com.github.highcumontoa.tenantapiguardjava.idempotency.IdempotencyOutcome;
import com.github.highcumontoa.tenantapiguardjava.idempotency.IdempotencyService;
import com.github.highcumontoa.tenantapiguardjava.idempotency.StoredResponse;
import com.github.highcumontoa.tenantapiguardjava.exception.GuardException;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaDecision;
import com.github.highcumontoa.tenantapiguardjava.quota.QuotaService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * 接口保护主过滤器:租户解析 -> 并发隔离 -> 配额判定 -> 写操作幂等。
 * 仅作用于 /api/**,保护顺序固定,每条拒绝都携带稳定原因并记录判定依据。
 *
 * <p>配额与并发许可一旦授予即不退还(下游业务失败也计入租户本窗口用量),
 * 这保证判定结论只取决于租户自身的请求序列,可解释、可复现。
 */
@Component
public class GuardFilter extends OncePerRequestFilter {

    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENCY_REPLAY_HEADER = "X-Idempotent-Replay";

    private static final Logger log = LoggerFactory.getLogger(GuardFilter.class);
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final BulkheadService bulkheadService;
    private final QuotaService quotaService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public GuardFilter(BulkheadService bulkheadService,
                       QuotaService quotaService,
                       IdempotencyService idempotencyService,
                       ObjectMapper objectMapper) {
        this.bulkheadService = bulkheadService;
        this.quotaService = quotaService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String tenant = request.getHeader(TENANT_HEADER);
        String method = request.getMethod();
        log.info("guard enter uri={} method={} tenant={}", request.getRequestURI(), method, tenant);

        if (tenant == null || tenant.isBlank()) {
            writeRejection(response, new GuardException(RejectReason.TENANT_MISSING));
            return;
        }

        try (BulkheadLease lease = bulkheadService.acquire(tenant)) {
            QuotaDecision decision = quotaService.tryAcquire(tenant);
            if (!decision.allowed()) {
                response.setHeader("Retry-After",
                        String.valueOf(Math.max(1L, decision.retryAfterMillis() / 1000L)));
                writeRejection(response, new GuardException(RejectReason.QUOTA_EXCEEDED,
                        "used=" + decision.used() + ", limit=" + decision.limit()
                                + ", retryAfterMillis=" + decision.retryAfterMillis()));
                return;
            }

            if (MUTATING_METHODS.contains(method)) {
                doMutating(request, response, filterChain, tenant, method);
            } else {
                filterChain.doFilter(request, response);
            }
        } catch (GuardException ex) {
            if (!response.isCommitted()) {
                writeRejection(response, ex);
            } else {
                log.warn("response already committed for reason={}", ex.reason().code());
            }
        }
    }

    private void doMutating(HttpServletRequest request,
                            HttpServletResponse response,
                            FilterChain filterChain,
                            String tenant,
                            String method)
            throws IOException, ServletException {
        String key = request.getHeader(IDEMPOTENCY_HEADER);
        if (key == null || key.isBlank()) {
            throw new GuardException(RejectReason.IDEMPOTENCY_KEY_MISSING);
        }

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        String contentHash = sha256Hex(cachedRequest.cachedBody());
        log.info("idempotency check tenant={} key={} method={} bytes={} contentHash={}",
                tenant, key, method, cachedRequest.cachedBody().length, contentHash);

        IdempotencyOutcome outcome = idempotencyService.begin(tenant, key, contentHash);
        if (outcome instanceof IdempotencyOutcome.Conflict) {
            throw new GuardException(RejectReason.IDEMPOTENCY_CONFLICT, "key=" + key);
        }
        if (outcome instanceof IdempotencyOutcome.Replay replay) {
            writeReplay(response, replay.response(), tenant, key);
            return;
        }

        // 首次提交:执行业务并捕获响应快照。业务成功则保存;出现异常或 5xx 则释放在途状态。
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        boolean businessFailed;
        try {
            filterChain.doFilter(cachedRequest, responseWrapper);
            businessFailed = responseWrapper.getStatus() >= 500;
            StoredResponse snapshot = new StoredResponse(
                    responseWrapper.getStatus(),
                    responseWrapper.getContentAsByteArray(),
                    responseWrapper.getContentType());
            if (businessFailed) {
                idempotencyService.fail(tenant, key);
            } else {
                idempotencyService.complete(tenant, key, snapshot);
            }
        } catch (IOException | ServletException | RuntimeException ex) {
            idempotencyService.fail(tenant, key);
            throw ex;
        } finally {
            responseWrapper.copyBodyToResponse();
        }
    }

    private void writeReplay(HttpServletResponse response, StoredResponse stored,
                             String tenant, String key) throws IOException {
        response.setStatus(stored.status());
        response.setHeader(IDEMPOTENCY_REPLAY_HEADER, "true");
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        byte[] body = stored.body() == null ? new byte[0] : stored.body();
        response.setContentLength(body.length);
        log.info("idempotency replay-written tenant={} key={} status={} bytes={}",
                tenant, key, stored.status(), body.length);
        response.getOutputStream().write(body);
    }

    private void writeRejection(HttpServletResponse response, GuardException ex) throws IOException {
        RejectReason reason = ex.reason();
        response.setStatus(reason.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        byte[] payload = objectMapper.writeValueAsBytes(ApiResponse.error(reason));
        response.setContentLength(payload.length);
        log.warn("rejection reason={} httpStatus={} detail={}",
                reason.code(), reason.httpStatus(), ex.getMessage());
        response.getOutputStream().write(payload);
    }

    static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
