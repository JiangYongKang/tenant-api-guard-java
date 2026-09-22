package com.github.highcumontoa.tenantapiguardjava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.tenantapiguardjava.common.ApiResponse;
import com.github.highcumontoa.tenantapiguardjava.common.RejectReason;
import com.github.highcumontoa.tenantapiguardjava.exception.GuardException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * 控制器/DispatcherServlet 层的异常翻译。
 *
 * <p>过滤器 {@link GuardFilter} 自身产生的拒绝在过滤器内直接落盘(advice 无法拦截过滤器异常);
 * 本 advice 处理进入 DispatcherServlet 之后才抛出的 {@link GuardException}(例如控制器内部
 * 主动拒绝),并为未预期异常提供确定性的 500 响应,避免返回不确定结果。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(GuardException.class)
    public void handleGuard(GuardException ex, HttpServletResponse response) throws IOException {
        writeRejection(response, ex.reason(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public void handleUnexpected(Exception ex, HttpServletResponse response) throws IOException {
        log.error("unexpected error, returning deterministic 500", ex);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        byte[] payload = objectMapper.writeValueAsBytes(
                new ApiResponse(false, null, "INTERNAL_ERROR", "内部错误,请求未产生确定的保护结论"));
        response.getOutputStream().write(payload);
    }

    private void writeRejection(HttpServletResponse response, RejectReason reason, String detail)
            throws IOException {
        response.setStatus(reason.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        byte[] payload = objectMapper.writeValueAsBytes(ApiResponse.error(reason));
        response.setContentLength(payload.length);
        log.warn("rejection reason={} httpStatus={} detail={}",
                reason.code(), reason.httpStatus(), detail);
        response.getOutputStream().write(payload);
    }
}
