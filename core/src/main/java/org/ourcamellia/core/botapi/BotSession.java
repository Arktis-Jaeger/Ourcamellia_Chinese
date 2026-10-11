package org.ourcamellia.core.botapi;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Ids;
import org.ourcamellia.common.Text;
import org.ourcamellia.common.protocol.Envelope;
import org.ourcamellia.common.protocol.Wire;
import org.ourcamellia.core.crypto.GroupCrypto;
import org.ourcamellia.core.security.RateLimiter;
import org.ourcamellia.core.server.ServerCore;
import org.ourcamellia.core.transport.Conn;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BOT 参与者的进程内编程接口（REQUIREMENTS §5.4、ARCHITECTURE.md §3）。
 * BOT 使用服务器运营者签发的令牌（仅存储为 SHA-256 摘要）以及随令牌签发的
 * X25519 私钥（用于解密加密群组内容）完成认证。BOT 以自身标记为独立的账户
 * 参与——它是一种参与者类型而非权限等级：该 API 中不存在任何管理操作。
 *
 * 这是面向外部程序的稳定主 API；可选的 localhost HTTP 适配器（BotHttpServer）
 * 只是对相同操作的薄封装。抛出的错误消息会进入 BOT API 的 JSON 错误响应
 * （API 契约），因此保持英文原文。
 */
public final class BotSession implements AutoCloseable, Conn {

    public static final long EVENT_POLL_TIMEOUT_MS = 60_000;

    private final ServerCore server;
    private final String botName;
    private final String tokenHash;
    private final RateLimiter rateLimiter;
    private final AtomicBoolean open = new AtomicBoolean(true);
    // 服务器投递给该 BOT 的信封队列
    private final ConcurrentLinkedQueue<Envelope> inbox = new ConcurrentLinkedQueue<>();
    private final Object sendLock = new Object();
    private final java.security.PrivateKey x25519PrivateKey;
    // 该 BOT 所知的群组状态（含解密后的群组密钥）
    private final ConcurrentHashMap<String, GroupState> groups = new ConcurrentHashMap<>();

    /** 该 BOT 的加密群组密钥状态。 */
    public static final class GroupState {
        public volatile String id;
        public volatile String name;
        public volatile String privacy;
        public volatile byte[] groupKey;
        public volatile long generation = 1;
    }

    public BotSession(ServerCore server, String botName, String tokenHash, int rateLimitPerMinute,
                      PrivateKey x25519PrivateKey) {
        this.server = server;
        this.botName = botName;
        this.tokenHash = tokenHash;
        this.rateLimiter = new RateLimiter(rateLimitPerMinute);
        this.x25519PrivateKey = x25519PrivateKey;
    }

    public String botName() {
        return botName;
    }

    // ---------------- 操作（仅用户级；绝无管理操作） ----------------

    /** 发送私聊消息；好友关系与群成员资格由服务端校验。 */
    public void sendPrivate(String to, String text) {
        requireOpen();
        rateLimit();
        Text.requireCleanText(text, "message", Text.MAX_MESSAGE);
        server.botDispatch(this, Wire.message(Ids.newId(), botName, to, text));
    }

    /** 发送群组消息；加密群组使用群组密钥加密。 */
    public void sendGroup(String groupId, String text) {
        requireOpen();
        rateLimit();
        Text.requireCleanText(text, "message", Text.MAX_MESSAGE);
        GroupState g = groups.get(groupId);
        String box = null;
        if (g != null && "encrypted".equals(g.privacy)) {
            if (g.groupKey == null) {
                throw new Errors.Denied("group key not available for encrypted group");
            }
            box = GroupCrypto.encryptMessage(g.groupKey, groupId,
                    text.getBytes(StandardCharsets.UTF_8));
            text = null;
        }
        server.botDispatch(this, Wire.groupMessage(Ids.newId(), groupId, botName, text, box));
    }

    /** 发送好友请求（BOT 账户的好友关系与普通用户相同）。 */
    public void sendFriendRequest(String to) {
        requireOpen();
        rateLimit();
        server.botDispatch(this, Wire.friendRequest(Ids.newId(), botName, to));
    }

    /** 轮询下一个面向用户的事件信封（消息、通知等）。 */
    public Envelope nextEvent(long timeoutMs) {
        requireOpen();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Envelope e = inbox.poll();
            if (e == null) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                continue;
            }
            if (absorbInternal(e)) {
                continue;
            }
            return e;
        }
        return null;
    }

    /** 排空当前排队的所有面向用户事件。 */
    public List<Envelope> drainEvents() {
        List<Envelope> out = new java.util.ArrayList<>();
        for (Envelope e : inbox) {
            if (!absorbInternal(e)) {
                out.add(e);
            }
        }
        return out;
    }

    /** 内部记账帧（群组元数据、密钥包裹）用于更新状态并对外隐藏。 */
    private boolean absorbInternal(Envelope e) {
        try {
            switch (e.type()) {
                case Wire.T_GROUP_UPDATE -> {
                    // 群组元数据更新：记录群信息；不再是成员则移除本地记录
                    var u = Wire.parseGroupUpdate(e);
                    onGroupUpdate(u.group().id(), u.group().name(), u.group().privacy(),
                            u.group().members());
                    return true;
                }
                case Wire.T_GROUP_KEY_WRAP -> {
                    // 群组密钥包裹：只有发给自己的才解密并缓存群组密钥
                    var w = Wire.parseGroupKeyWrap(e);
                    if (botName.equals(w.forUser())) {
                        onGroupKeyWrap(w.groupId(), w.generation(), w.box(), w.fromPub());
                    }
                    return true;
                }
                case Wire.T_ACK, Wire.T_NOTIFY_STATE -> {
                    // 操作确认在 API 表面上是隐式的。
                    return true;
                }
                default -> {
                    return false;
                }
            }
        } catch (Errors.OcException malformed) {
            return true; // 发给 BOT 的恶意帧一律丢弃，绝不对外暴露
        }
    }

    // ---------------- Conn 实现（出站帧路由到收件箱） ----------------

    @Override
    public void send(Envelope envelope) {
        // 由服务器调用，向该 BOT 投递回复/确认/错误。
        if (open.get()) {
            inbox.add(envelope);
        }
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public String remoteDescription() {
        return "bot:" + botName;
    }

    @Override
    public String remoteFingerprint() {
        return null;
    }

    @Override
    public void close() {
        if (open.getAndSet(false)) {
            server.closeBotSession(botName);
        }
    }

    // ---------------- 服务器驱动的更新 ----------------

    void onGroupUpdate(String id, String name, String privacy, List<String> members) {
        boolean isMember = members.contains(botName);
        GroupState g = groups.computeIfAbsent(id, k -> new GroupState());
        g.id = id;
        g.name = name;
        g.privacy = privacy;
        if (!isMember) {
            groups.remove(id);
        }
    }

    // 解密群组密钥包裹：用本机 X25519 私钥与发送方公钥导出 KEK 并解封
    void onGroupKeyWrap(String groupId, long generation, String box, String fromPub) {
        GroupState g = groups.get(groupId);
        if (g == null || x25519PrivateKey == null) {
            return;
        }
        try {
            byte[] senderPub = Base64.getDecoder().decode(fromPub);
            byte[] key = GroupCrypto.unwrapGroupKey(x25519PrivateKey, senderPub, groupId,
                    generation, box);
            if (key != null) {
                g.groupKey = key;
                g.generation = generation;
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void rateLimit() {
        // 按令牌哈希限流；超限错误消息进入 BOT API JSON 契约
        if (!rateLimiter.tryAcquire(tokenHash)) {
            throw new Errors.RateLimited("bot rate limit exceeded; wait a minute");
        }
    }

    private void requireOpen() {
        if (!open.get()) {
            throw new Errors.Network("bot session closed");
        }
    }
}
