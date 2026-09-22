package com.github.highcumontoa.tenantapiguardjava.idempotency;

/**
 * 首次请求完成后保存的响应快照,用于向重复提交回放一致结果。
 */
public record StoredResponse(int status, byte[] body, String contentType) {

    public String bodyAsString() {
        return body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }
}
