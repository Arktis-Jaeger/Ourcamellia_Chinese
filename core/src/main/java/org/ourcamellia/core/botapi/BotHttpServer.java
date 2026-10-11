package org.ourcamellia.core.botapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Json;
import org.ourcamellia.common.config.ServerConfig;
import org.ourcamellia.common.protocol.Envelope;
import org.ourcamellia.core.server.ServerCore;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 可选的 BOT API HTTP 适配器（ARCHITECTURE.md §3）：对进程内 BotSession
 * 操作的薄封装。默认仅绑定回环地址，且除非运营者显式启用否则处于关闭状态；
 * 没有任何管理端点，每个请求都必须携带有效的 BOT 令牌（bearer 认证）。
 * 可通过配置绑定到非回环地址，这是文档中记录的运营者风险决策（该适配器
 * 无 TLS——请仅在回环地址上使用）。
 *
 * 端点（路径与 JSON 字段为对外 API 契约，保持原文）：
 *   POST /v1/auth        {token}                        → {bot, status:"ok"}
 *   POST /v1/send        {token, to, text}              → 私聊消息
 *   POST /v1/group-send  {token, groupId, text}         → 群组消息
 *   POST /v1/friend-request {token, to}                 → 好友请求
 *   GET  /v1/events?token=...                           → 排队事件（JSON 数组）
 */
public final class BotHttpServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(BotHttpServer.class.getName());

    private final ServerCore server;
    private final ServerConfig config;
    private HttpServer http;
    // token -> 会话；重复 token 复用同一会话
    private final Map<String, BotSession> sessions = new ConcurrentHashMap<>();

    public BotHttpServer(ServerCore server, ServerConfig config) {
        this.server = server;
        this.config = config;
    }

    public void start() {
        try {
            http = HttpServer.create(new InetSocketAddress(config.botApi().bindHost(), config.botApi().port()), 0);
            http.createContext("/v1/auth", this::handleAuth);
            http.createContext("/v1/send", this::handleSend);
            http.createContext("/v1/group-send", this::handleGroupSend);
            http.createContext("/v1/friend-request", this::handleFriendRequest);
            http.createContext("/v1/events", this::handleEvents);
            http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            http.start();
            LOG.info("BOT API 正在监听 " + config.botApi().bindHost() + ":" + config.botApi().port());
        } catch (IOException e) {
            throw new Errors.Persistence("无法启动 BOT API 监听服务", e);
        }
    }

    // 从请求体中取 token 并（按需）创建会话；缺失 token 为 401（错误消息进入 JSON 契约，保持英文）
    private BotSession sessionFor(JsonNode body) {
        String token = body.path("token").asText("");
        if (token.isBlank()) {
            throw new Errors.Auth("missing token");
        }
        return sessions.computeIfAbsent(token, t -> {
            try {
                return server.openBotSession(t, "");
            } catch (Errors.OcException e) {
                throw e;
            }
        });
    }

    // 端点处理：响应 JSON 为 API 契约（{"status":...} 等键值保持原文）
    private void handleAuth(HttpExchange exchange) {
        withBody(exchange, body -> {
            BotSession s = sessionFor(body);
            var out = Json.object();
            out.put("bot", s.botName());
            out.put("status", "ok");
            return out;
        });
    }

    private void handleSend(HttpExchange exchange) {
        withBody(exchange, body -> {
            BotSession s = sessionFor(body);
            s.sendPrivate(body.path("to").asText(""), body.path("text").asText(""));
            var out = Json.object();
            out.put("status", "sent");
            return out;
        });
    }

    private void handleGroupSend(HttpExchange exchange) {
        withBody(exchange, body -> {
            BotSession s = sessionFor(body);
            s.sendGroup(body.path("groupId").asText(""), body.path("text").asText(""));
            var out = Json.object();
            out.put("status", "sent");
            return out;
        });
    }

    private void handleFriendRequest(HttpExchange exchange) {
        withBody(exchange, body -> {
            BotSession s = sessionFor(body);
            s.sendFriendRequest(body.path("to").asText(""));
            var out = Json.object();
            out.put("status", "pending");
            return out;
        });
    }

    // 事件拉取端点：仅支持 GET ?token=...，返回事件 JSON 数组；错误码/错误消息为契约
    private void handleEvents(HttpExchange exchange) {
        try {
            String token = query(exchange, "token");
            if (token == null) {
                respond(exchange, 401, "{\"error\":\"missing token\"}");
                return;
            }
            BotSession s = sessions.get(token);
            if (s == null) {
                respond(exchange, 401, "{\"error\":\"invalid token\"}");
                return;
            }
            var out = Json.array();
            List<Envelope> events = s.drainEvents();
            for (Envelope e : events) {
                out.add(e.root());
            }
            respond(exchange, 200, Json.write(out));
        } catch (RuntimeException e) {
            respond(exchange, 500, "{\"error\":\"internal error\"}");
        }
    }

    // 统一请求体处理：解析 JSON → 执行 handler → 回 JSON；
    // Errors.OcException 按类别映射为 HTTP 状态码，消息进入 {"error":...} 契约
    private void withBody(HttpExchange exchange, java.util.function.Function<JsonNode, JsonNode> handler) {
        try {
            String raw;
            try {
                raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                respond(exchange, 400, "{\"error\":\"cannot read request body\"}");
                return;
            }
            JsonNode body = Json.parse(raw);
            JsonNode out = handler.apply(body);
            respond(exchange, 200, Json.write(out));
        } catch (Errors.OcException e) {
            // 按错误类别映射 HTTP 状态码：认证 401 / 拒绝 403 / 限流 429 / 参数 400 / 未找到 404 / 冲突 409 / 其余 500
            int code = switch (e.category) {
                case AUTH -> 401;
                case DENIED -> 403;
                case RATE_LIMITED -> 429;
                case VALIDATION, USAGE -> 400;
                case NOT_FOUND -> 404;
                case CONFLICT -> 409;
                default -> 500;
            };
            respond(exchange, code, "{\"error\":" + Json.write(e.getMessage()) + "}");
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "BOT API 请求处理失败", e);
            respond(exchange, 400, "{\"error\":\"bad request\"}");
        }
    }

    // 从查询串中解析参数（单个 key=value）
    private static String query(HttpExchange exchange, String key) {
        String q = exchange.getRequestURI().getQuery();
        if (q == null) {
            return null;
        }
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // 统一响应写出：application/json，并始终关闭交换
    private void respond(HttpExchange exchange, int code, String body) {
        try {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        } catch (IOException ignored) {
        } finally {
            exchange.close();
        }
    }

    // 关闭：停止 HTTP 服务并关闭所有 BOT 会话
    @Override
    public void close() {
        if (http != null) {
            http.stop(0);
        }
        for (BotSession s : new ArrayList<>(sessions.values())) {
            s.close();
        }
        sessions.clear();
    }
}
