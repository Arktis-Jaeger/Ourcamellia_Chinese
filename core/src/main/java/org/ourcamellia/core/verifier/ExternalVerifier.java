package org.ourcamellia.core.verifier;

import com.fasterxml.jackson.databind.JsonNode;
import org.ourcamellia.common.CryptoUtil;
import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 外部注册审批校验器客户端（REQUIREMENTS §4.2、SECURITY.md §6）。
 *
 * 契约（详见 PROTOCOL.md）：
 * - 以 JSON {@code {v, ticketId, username, nonce, fields}} 发起 POST，并携带请求头
 *   {@code X-Ourcamellia-Ticket} 与 {@code X-Ourcamellia-Signature} = hex(HMAC-SHA256(sharedSecret, body))。
 * - 校验器回 JSON {@code {ticketId, status: approved|rejected|pending, nonce, signature}}，
 *   其中 signature = hex(HMAC-SHA256(secret, nonce + "|" + ticketId + "|" + status))。
 *   只有签名有效、ticketId 匹配且 nonce 回显一致的响应才会被接受——未经验证的
 *   回调绝不会被当作“已批准”处理。
 * - 有限超时与重试次数，每个 ticket 幂等。绝不发送密码或二级密码，只发送
 *   配置的注册字段。
 * - 目标地址由运营者配置并经过 SSRF 防护（SsrGuard）；手动跟随重定向并重新
 *   校验，最多 maxRedirects 次。
 */
public final class ExternalVerifier {

    public enum Decision { APPROVED, REJECTED, PENDING, ERROR }

    public record Result(Decision decision, String message) {}

    private final String url;
    private final String secret;
    private final int timeoutMs;
    private final int maxRetries;
    private final boolean allowPrivateTargets;
    private final int maxRedirects;
    private final HttpClient client;

    public ExternalVerifier(String url, String secret, int timeoutMs, int maxRetries,
                            boolean allowPrivateTargets, int maxRedirects) {
        this.url = url;
        this.secret = secret;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.allowPrivateTargets = allowPrivateTargets;
        this.maxRedirects = maxRedirects;
        SsrGuard.check(url, allowPrivateTargets, maxRedirects);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 提交一次注册审批请求；绝不抛出异常（网络问题 → ERROR）。 */
    public Result submit(String ticketId, String username, Map<String, String> fields) {
        String nonce = CryptoUtil.randomHex(16);
        var body = Json.object();
        body.put("v", 1);
        body.put("ticketId", ticketId);
        body.put("username", username);
        body.put("nonce", nonce);
        var f = body.putObject("fields");
        fields.forEach(f::put);
        String payload = Json.write(body);

        int attempts = maxRetries + 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                HttpResponse<String> response = send(url, payload, ticketId);
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    String location = response.headers().firstValue("location").orElse(null);
                    URI next = SsrGuard.validateRedirect(URI.create(url), location, allowPrivateTargets);
                    if (next == null) {
                        return new Result(Decision.ERROR, "校验器重定向目标被拒绝（SSRF 防护）");
                    }
                    return follow(next, 1, payload, ticketId, nonce);
                }
                return parse(response.body(), ticketId, nonce);
            } catch (Errors.OcException e) {
                return new Result(Decision.ERROR, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(Decision.ERROR, "已中断");
            } catch (Exception e) {
                if (attempt == attempts - 1) {
                    return new Result(Decision.ERROR, "校验器不可达：" + e.getMessage());
                }
            }
        }
        return new Result(Decision.ERROR, "校验器不可达");
    }

    // 手动跟随重定向：每跳重新做 SSRF 校验，超过上限即失败
    private Result follow(URI target, int depth, String payload, String ticketId, String nonce) {
        if (depth > maxRedirects) {
            return new Result(Decision.ERROR, "校验器重定向次数过多");
        }
        try {
            HttpResponse<String> response = send(target.toString(), payload, ticketId);
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                String location = response.headers().firstValue("location").orElse(null);
                URI next = SsrGuard.validateRedirect(target, location, allowPrivateTargets);
                if (next == null) {
                    return new Result(Decision.ERROR, "校验器重定向目标被拒绝（SSRF 防护）");
                }
                return follow(next, depth + 1, payload, ticketId, nonce);
            }
            return parse(response.body(), ticketId, nonce);
        } catch (Errors.OcException e) {
            return new Result(Decision.ERROR, e.getMessage());
        } catch (Exception e) {
            return new Result(Decision.ERROR, "校验器不可达：" + e.getMessage());
        }
    }

    // 发送带 HMAC-SHA256 签名的 POST 请求；签名覆盖整个请求体
    private HttpResponse<String> send(String targetUrl, String payload, String ticketId)
            throws Exception {
        byte[] signature = CryptoUtil.hmacSha256(secret.getBytes(StandardCharsets.UTF_8),
                payload.getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(targetUrl))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("X-Ourcamellia-Ticket", ticketId)
                .header("X-Ourcamellia-Signature", java.util.HexFormat.of().formatHex(signature))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // 解析回执：非 JSON、字段不匹配、未知状态或签名无效一律判为 ERROR
    private Result parse(String responseBody, String ticketId, String nonce) {
        JsonNode root;
        try {
            root = Json.parse(responseBody == null ? "" : responseBody);
        } catch (Errors.Malformed e) {
            return new Result(Decision.ERROR, "校验器返回了无效的 JSON");
        }
        String gotTicket = root.path("ticketId").asText("");
        String status = root.path("status").asText("");
        String gotNonce = root.path("nonce").asText("");
        String signature = root.path("signature").asText("");
        if (!gotTicket.equals(ticketId) || !gotNonce.equals(nonce)) {
            return new Result(Decision.ERROR, "校验器回执与请求不匹配（可能被伪造）");
        }
        if (!status.equals("approved") && !status.equals("rejected") && !status.equals("pending")) {
            return new Result(Decision.ERROR, "校验器返回了未知状态");
        }
        String canonical = nonce + "|" + ticketId + "|" + status;
        byte[] expected = CryptoUtil.hmacSha256(secret.getBytes(StandardCharsets.UTF_8),
                canonical.getBytes(StandardCharsets.UTF_8));
        String expectedHex = java.util.HexFormat.of().formatHex(expected);
        if (!CryptoUtil.safeEquals(expectedHex, signature)) {
            return new Result(Decision.ERROR, "校验器回执签名无效（已拒绝）");
        }
        String message = root.path("message").asText("");
        return new Result(switch (status) {
            case "approved" -> Decision.APPROVED;
            case "rejected" -> Decision.REJECTED;
            default -> Decision.PENDING;
        }, message);
    }
}
