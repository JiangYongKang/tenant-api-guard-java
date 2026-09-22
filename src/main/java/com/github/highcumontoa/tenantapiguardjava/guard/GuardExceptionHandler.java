package com.github.highcumontoa.tenantapiguardjava.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.github.highcumontoa.tenantapiguardjava.model.ApiResponse;
import com.github.highcumontoa.tenantapiguardjava.model.ErrorBody;
import com.github.highcumontoa.tenantapiguardjava.model.GuardRejectedException;

/**
 * 把守卫拒绝异常转换为携带稳定原因码的 HTTP 响应。
 *
 * <p>每种 {@link com.github.highcumontoa.tenantapiguardjava.model.RejectReason}
 * 对应确定的状态码；响应体永远包含 reason 与判定明细，不返回空泛或不确定结果。</p>
 */
@RestControllerAdvice
public class GuardExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GuardExceptionHandler.class);

    @ExceptionHandler(GuardRejectedException.class)
    public ResponseEntity<ApiResponse> handle(GuardRejectedException ex) {
        String tenant = null;
        if (ex.detail() != null && ex.detail().get("tenant") instanceof String t) {
            tenant = t;
        }
        ErrorBody body = new ErrorBody(ex.reason().code(), ex.getMessage(), tenant, ex.detail());
        HttpStatus status = HttpStatus.valueOf(ex.reason().httpStatus());
        log.info("guard response decision=REJECT reason:{} httpStatus:{} detail:{}",
                ex.reason().code(), status.value(), ex.detail());
        return ResponseEntity.status(status).body(ApiResponse.error(body));
    }
}
