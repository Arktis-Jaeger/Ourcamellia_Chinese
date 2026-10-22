package org.ourcamellia.core.app;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Ids;
import org.ourcamellia.common.SelfSignedCerts;
import org.ourcamellia.common.Text;
import org.ourcamellia.common.config.ClientConfig;
import org.ourcamellia.common.domain.ConversationRef;
import org.ourcamellia.common.domain.FriendState;
import org.ourcamellia.common.domain.Message;
import org.ourcamellia.common.domain.NotificationKind;
import org.ourcamellia.common.domain.NotificationState;
import org.ourcamellia.common.domain.PeerStatus;
import org.ourcamellia.common.protocol.Envelope;
import org.ourcamellia.common.protocol.Wire;
import org.ourcamellia.core.discovery.DiscoveryService;
import org.ourcamellia.core.persist.AtomicFiles;
import org.ourcamellia.core.persist.ClientStateStore;
import org.ourcamellia.core.persist.LocalHistoryStore;
import org.ourcamellia.core.transport.Conn;
import org.ourcamellia.core.transport.ConnSession;
import org.ourcamellia.core.transport.SocketConn;
import org.ourcamellia.core.transport.TlsConnections;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Local mode: direct peer communication with an independent local identity.
 *
 * - Discoverability and connectability are independent settings.
 * - Friendship gates chat: strangers may only use the friend-request path; ordinary
 *   messages from non-friends are refused (first-contact restriction).
 * - Observer-relative status: CONNECTED when a live connection exists, DISCOVERED
 *   after a recent discovery reply, UNREACHABLE after a failed attempt, UNCONFIRMED
 *   otherwise (honest "unknown" instead of a false "offline").
 * - Identity = local name + pinned certificate fingerprint (TOFU); a fingerprint
 *   change at a known friend's address is refused (impersonation defense).
 */
public final class LocalSession implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(LocalSession.class.getName());
    private static final String PROTOCOL_VERSION_STR = String.valueOf(Envelope.PROTOCOL_VERSION);

    private final ClientConfig config;
    private final ClientStateStore store;
    private final LocalHistoryStore history;
    private final AppEvents events;
    private final Path dataDir;

    private String myName;
    private SelfSignedCerts.GeneratedCert identity;
    private SSLContext serverContext;
    private SSLContext clientContext;
    private javax.net.ssl.SSLServerSocket listener;
    private DiscoveryService discovery;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Runtime-togglable settings (initialized from config; REQUIREMENTS 3.2).
    private volatile boolean discoverable;
    private volatile boolean connectable;

    private final Object lock = new Object();
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private final Map<String, DiscoveredPeer> discovered = new ConcurrentHashMap<>();

    /** A known/discovered peer entry. */
    public record DiscoveredPeer(String name, String host, int port, String fingerprint, long expiresAt) {}

    static final class Peer {
        String name;
        ConnSession session;
        PeerStatus status = PeerStatus.UNCONFIRMED;
        String address;       // host:port of the live connection
        String fingerprint;

        boolean sessionIsOpen() {
            return session != null && session.isOpen();
        }
    }

    public LocalSession(ClientConfig config, ClientStateStore store, LocalHistoryStore history,
                        AppEvents events, Path dataDir) {
        this.config = config;
        this.store = store;
        this.history = history;
        this.events = events;
        this.dataDir = dataDir;
        this.discoverable = config.discoverable();
        this.connectable = config.connectable();
    }

    public boolean isActive() {
        return myName != null;
    }

    public String myName() {
        return myName;
    }

    public String myFingerprint() {
        return identity == null ? null : identity.fingerprint();
    }

    /** 进入本地模式：加载/生成本地身份（自签证书），按需开启入站监听与发现服务。 */
    public void start(String name) {
        synchronized (lock) {
            if (myName != null) {
                throw new Errors.Conflict("本地模式已在运行");
            }
            Text.requireLocalName(name);
            this.myName = name;

            Path ks = dataDir.resolve("local-identity.p12");
            try {
                if (java.nio.file.Files.exists(ks)) {
                    identity = SelfSignedCerts.loadKeystore(ks, keystorePassword());
                } else {
                    identity = SelfSignedCerts.generate("ourcamellia-local-" + name, 3650);
                    SelfSignedCerts.saveKeystore(ks, identity.cert(), identity.key(), keystorePassword());
                }
            } catch (Errors.OcException e) {
                throw new Errors.Persistence("本地身份不可用：" + e.getMessage(), e);
            }
            // 身份 = 本地用户名 + 自签证书指纹（TOFU 信任锚点）。
            store.setLocalIdentity(name, identity.fingerprint());
            serverContext = SelfSignedCerts.serverContextWithTrustAll(identityKeyStore(), keystorePassword().toCharArray());
            // 对端身份在握手完成后才用「指纹固定」来确认，
            // 因此 TLS 层这里信任任意证书（见 SelfSignedCerts.trustAllContext）。
            clientContext = SelfSignedCerts.trustAllContext(identityKeyStore(), keystorePassword().toCharArray());

            if (connectable) {
                try {
                    listener = TlsConnections.listen(config.listenHost(), config.listenPort(),
                            serverContext, true);
                    Thread.ofVirtual().name("local-listener").start(this::acceptLoop);
                } catch (Errors.OcException e) {
                    LOG.log(Level.WARNING, "入站监听不可用（{0}）；出站连接不受影响", e.getMessage());
                    events.onInfo("警告：入站监听不可用：" + e.getMessage());
                }
            }
            // 发现服务（ALOHA/组播探测）：可被发现与可连接是两个独立开关；
            // 收到的发现响应把「用户名 → 地址/端口/指纹」登记到 discovered 表。
            discovery = new DiscoveryService(config.discoveryPort(),
                    () -> discoverable, () -> connectable && listener != null,
                    () -> myName, () -> config.listenPort(), () -> identity.fingerprint(),
                    config.discoveryTtlSeconds() * 1000L, null);
            discovery.setReplySink((reply, source) -> onDiscoveryReply(reply, source));
            discovery.start();
        }
    }

    public boolean discoverable() {
        return discoverable;
    }

    public boolean connectable() {
        return connectable;
    }

    /** Toggles discovery visibility (independent of connectability). */
    public void setDiscoverable(boolean on) {
        synchronized (lock) {
            requireActive();
            this.discoverable = on;
        }
    }

    /** 切换是否接受入站连接；开启时绑定监听、关闭时解除绑定。 */
    public void setConnectable(boolean on) {
        synchronized (lock) {
            requireActive();
            if (on == this.connectable) {
                return;
            }
            this.connectable = on;
            if (on) {
                try {
                    listener = TlsConnections.listen(config.listenHost(), config.listenPort(),
                            serverContext, true);
                    Thread.ofVirtual().name("local-listener").start(this::acceptLoop);
                } catch (Errors.OcException e) {
                    this.connectable = false;
                    throw new Errors.Network("无法启用入站连接：" + e.getMessage());
                }
            } else if (listener != null) {
                try {
                    listener.close();
                } catch (java.io.IOException ignored) {
                }
                listener = null;
            }
        }
    }

    private String keystorePassword() {
        // 每台安装随机生成的密码，与密钥库同目录存放（权限 0600）。
        // 私钥的静态保护依赖文件系统权限边界（见 THREAT_MODEL.md）。
        Path pwFile = dataDir.resolve("local-identity.password");
        byte[] raw = AtomicFiles.readOrNull(pwFile);
        if (raw != null) {
            return new String(raw, java.nio.charset.StandardCharsets.US_ASCII).trim();
        }
        String pw = org.ourcamellia.common.CryptoUtil.randomHex(16);
        AtomicFiles.writeString(pwFile, pw + "\n");
        try {
            java.nio.file.Files.setPosixFilePermissions(pwFile,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
        }
        return pw;
    }

    private KeyStore identityKeyStore() {
        try {
            KeyStore ks = KeyStore.getInstance(SelfSignedCerts.KEYSTORE_TYPE);
            ks.load(null, null);
            ks.setKeyEntry(SelfSignedCerts.DEFAULT_ALIAS, identity.key(),
                    keystorePassword().toCharArray(),
                    new java.security.cert.Certificate[]{identity.cert()});
            return ks;
        } catch (Exception e) {
            throw new Errors.Internal("密钥库组装失败", e);
        }
    }

    /** 入站连接接受循环：每个连接一个 ConnSession（含心跳 ping 与读超时）。 */
    private void acceptLoop() {
        while (!closed.get() && listener != null) {
            try {
                var ssl = TlsConnections.accept(listener, config.connectTimeoutMs());
                String fp = SelfSignedCerts.fingerprint(
                        (java.security.cert.X509Certificate) ssl.getSession().getPeerCertificates()[0]);
                String desc = ssl.getInetAddress().getHostAddress() + ":" + ssl.getPort();
                SocketConn conn = new SocketConn(ssl, desc, fp, config.maxFrameBytes());
                InboundHolder holder = new InboundHolder();
                ConnSession session = new ConnSession(conn, inboundHandler(holder),
                        config.pingIntervalMs(), config.readTimeoutMs());
                holder.session = session;
                holder.conn = conn;
            } catch (Errors.OcException e) {
                System.err.println("调试-接受：OcException " + e + " " + e.getMessage());
                if (!closed.get()) {
                    LOG.log(Level.FINE, "入站接受失败：{0}", e.getMessage());
                }
            } catch (java.io.IOException e) {
                System.err.println("调试-接受：IOException " + e + " " + e.getMessage());
                if (!closed.get()) {
                    LOG.log(Level.FINE, "入站接受 I/O 失败：{0}", e.getMessage());
                }
            } catch (Throwable t) {
                System.err.println("调试-接受：Throwable " + t);
            }
        }
    }

    private static final class InboundHolder {
        volatile ConnSession session;
        volatile Conn conn;
    }

    /** 入站握手：首帧必须是 HELLO；校验协议版本后回以我方 HELLO 完成双方自我介绍。 */
    private ConnSession.Handler inboundHandler(InboundHolder holder) {
        return new ConnSession.Handler() {
            private volatile String peerName;

            @Override
            public void onEnvelope(Conn conn, Envelope envelope) {
                if (peerName == null) {
                    if (!envelope.type().equals(Wire.T_HELLO)) {
                        conn.send(Wire.error(envelope.id(), Errors.Category.MALFORMED, "必须先发送 HELLO"));
                        conn.close();
                        return;
                    }
                    var hello = Wire.parseHello(envelope);
                    if (!hello.version().equals(PROTOCOL_VERSION_STR)) {
                        conn.send(Wire.error(envelope.id(), Errors.Category.MALFORMED,
                                "不支持的协议版本 " + hello.version()));
                        conn.close();
                        return;
                    }
                    String name = Text.requireLocalName(hello.client());
                    peerName = name;
                    // 双方互相表明身份：回送我方 HELLO。
                    conn.send(Wire.hello(myName, PROTOCOL_VERSION_STR));
                    onPeerHello(name, holder, conn.remoteFingerprint());
                    return;
                }
                dispatch(peerName, conn, envelope);
            }

            @Override
            public void onClosed(Conn conn, String reason) {
                if (peerName != null) {
                    onPeerDisconnected(peerName);
                }
            }
        };
    }

    /** 入站 HELLO 处理：核对已固定指纹（防冒充），登记对端并置为 CONNECTED。 */
    private void onPeerHello(String name, InboundHolder holder, String remoteFp) {
        synchronized (lock) {
            // 防冒充：已知好友名下的连接，其证书指纹若与已固定指纹不一致则拒绝。
            ClientStateStore.FriendRec friend = store.friend(name);
            if (friend != null && friend.fingerprint() != null
                    && !friend.fingerprint().equalsIgnoreCase(remoteFp)) {
                holder.conn.send(Wire.error(null, Errors.Category.DENIED,
                        "证书与 '" + name + "' 的已固定身份不匹配"));
                holder.conn.close();
                return;
            }
            Peer old = peers.get(name);
            if (old != null) {
                old.session.close();
            }
            Peer peer = new Peer();
            peer.name = name;
            peer.fingerprint = remoteFp;
            peer.address = holder.conn.remoteDescription();
            peer.session = holder.session;
            peers.put(name, peer);
            setStatus(name, PeerStatus.CONNECTED, peer.address);
        }
    }

    /** 出站连接：TOFU 固定服务器证书指纹，建立后发送 HELLO。 */
    public void connect(String host, int port) {
        synchronized (lock) {
            requireActive();
            if (port < 1 || port > 65535) {
                throw new Errors.Validation("无效端口");
            }
            String address = host + ":" + port;
            // 复用同一地址上已有的活动连接。
            for (Peer p : peers.values()) {
                if (address.equals(p.address) && p.sessionIsOpen()) {
                    return;
                }
            }
            // 该地址若是已知好友则固定其指纹；否则按 TOFU 首次信任。
            String expected = PinGuard.expectedFingerprint(store, address);
            var result = TlsConnections.connect(host, port, expected, clientContext, config.connectTimeoutMs());
            PinGuard.check(store, result.fingerprint(), address);
            String desc = result.socket().getInetAddress().getHostAddress() + ":" + port;
            SocketConn conn;
            try {
                conn = new SocketConn(result.socket(), desc, result.fingerprint(), config.maxFrameBytes());
            } catch (java.io.IOException e) {
                throw new Errors.Network("连接建立失败", e);
            }
            Peer peer = new Peer();
            peer.address = address;
            peer.fingerprint = result.fingerprint();
            peer.session = new ConnSession(conn, outboundHandler(peer), config.pingIntervalMs(),
                    config.readTimeoutMs());
            peer.session.conn().send(Wire.hello(myName, PROTOCOL_VERSION_STR));
        }
    }

    /** 出站握手（HELLO 交换）：首帧必须是 HELLO，完成后才进入普通帧分发。 */
    private ConnSession.Handler outboundHandler(Peer peer) {
        return new ConnSession.Handler() {
            private volatile boolean gotHello = false;

            @Override
            public void onEnvelope(Conn conn, Envelope envelope) {
                if (!gotHello) {
                    if (!envelope.type().equals(Wire.T_HELLO)) {
                        conn.send(Wire.error(envelope.id(), Errors.Category.MALFORMED, "必须先发送 HELLO"));
                        conn.close();
                        return;
                    }
                    var hello = Wire.parseHello(envelope);
                    if (!hello.version().equals(PROTOCOL_VERSION_STR)) {
                        conn.send(Wire.error(envelope.id(), Errors.Category.MALFORMED,
                                "不支持的协议版本 " + hello.version()));
                        conn.close();
                        return;
                    }
                    String name = Text.requireLocalName(hello.client());
                    gotHello = true;
                    synchronized (lock) {
                        peer.name = name;
                        Peer old = peers.get(name);
                        if (old != null && old != peer) {
                            old.session.close();
                        }
                        peers.put(name, peer);
                        setStatus(name, PeerStatus.CONNECTED, peer.address);
                    }
                    return;
                }
                dispatch(peer.name, conn, envelope);
            }

            @Override
            public void onClosed(Conn conn, String reason) {
                synchronized (lock) {
                    if (peer.name != null && peers.get(peer.name) == peer) {
                        peers.remove(peer.name);
                    }
                }
                if (peer.name != null) {
                    events.onPeerStatus(peer.name, PeerStatus.UNCONFIRMED);
                    events.onStatusChanged();
                }
            }
        };
    }

    // ---------------- protocol dispatch ----------------

/** 协议帧分发：按帧类型路由到各处理器；错误帧展示对端说明并回错误。 */
    private void dispatch(String peerName, Conn conn, Envelope envelope) {
        String type = envelope.type();
        try {
            switch (type) {
                case Wire.T_MESSAGE -> onMessageFrame(peerName, conn, envelope);
                case Wire.T_DELIVERY -> onDeliveryFrame(peerName, envelope);
                case Wire.T_FRIEND_REQUEST -> onFriendRequestFrame(peerName, conn, envelope);
                case Wire.T_FRIEND_REPLY -> onFriendReplyFrame(peerName, envelope);
                case Wire.T_ERROR -> events.onInfo("对端 " + peerName + " 报告："
                        + envelope.body().path("message").asText("error"));
                default -> conn.send(Wire.error(envelope.id(), Errors.Category.USAGE, "不支持的帧类型"));
            }
        } catch (Errors.OcException e) {
            conn.send(Wire.error(envelope.id(), e.category, e.getMessage()));
        }
    }

    /** 收到普通消息：校验发送者/接收者，且必须是已接受的好友（首次接触限制）。 */
    private void onMessageFrame(String peerName, Conn conn, Envelope envelope) {
        var m = Wire.parseMessage(envelope);
        if (!m.sender().equals(peerName)) {
            throw new Errors.Malformed("发送者与连接对端不一致");
        }
        if (!m.to().equals(myName)) {
            throw new Errors.Malformed("消息不是发给我的");
        }
        if (friendState(peerName) != FriendState.ACCEPTED) {
            // 首次接触限制：必须已是好友才允许聊天，陌生人只能走好友请求路径。
            conn.send(Wire.error(envelope.id(), Errors.Category.DENIED,
                    "你不是 " + myName + " 的好友；请先发送好友请求"));
            return;
        }
        recordInbound(peerName, m.msgId(), m.sender(), m.text(), m.ts());
        conn.send(Wire.delivery(m.msgId(), peerName, "delivered"));
    }

    /** 收到送达回执（对端确已收到），向用户提示。 */
    private void onDeliveryFrame(String peerName, Envelope envelope) {
        var d = Wire.parseDelivery(envelope);
        events.onInfo("已送达 " + d.to());
        events.onStatusChanged();
    }

    /** 收到好友请求：校验字段，登记为待处理（PENDING_IN）并生成通知。 */
    private void onFriendRequestFrame(String peerName, Conn conn, Envelope envelope) {
        var req = Wire.parseFriendRequest(envelope);
        if (!req.from().equals(peerName) || !req.to().equals(myName)) {
            throw new Errors.Malformed("好友请求字段不匹配");
        }
        FriendState current = friendState(peerName);
        if (current == FriendState.ACCEPTED) {
            conn.send(Wire.friendReply(req.requestId(), myName, peerName, true));
            return;
        }
        if (current == FriendState.PENDING_IN) {
            return; // 已待处理；幂等
        }
        store.putFriend(new ClientStateStore.FriendRec(peerName, FriendState.PENDING_IN.name(),
                conn.remoteDescription(), conn.remoteFingerprint(), System.currentTimeMillis()));
        store.addNotification(new ClientStateStore.NotifRec(
                Ids.newId(), NotificationKind.FRIEND_REQUEST.name(), NotificationState.UNREAD.name(),
                peerName, peerName + " 向你发送了好友请求", System.currentTimeMillis(),
                System.currentTimeMillis() + 7L * 24 * 3600 * 1000, req.requestId()));
        events.onNotificationsChanged();
        events.onStatusChanged();
    }

    /** 收到好友回复：接受则转为好友（ACCEPTED），拒绝则记为已拒绝（REJECTED）。 */
    private void onFriendReplyFrame(String peerName, Envelope envelope) {
        var reply = Wire.parseFriendReply(envelope);
        if (!reply.from().equals(peerName) || !reply.to().equals(myName)) {
            throw new Errors.Malformed("好友回复字段不匹配");
        }
        if (reply.accept()) {
            store.putFriend(new ClientStateStore.FriendRec(peerName, FriendState.ACCEPTED.name(),
                    peerAddress(peerName), peerFingerprint(peerName), System.currentTimeMillis()));
            store.addNotification(new ClientStateStore.NotifRec(
                    Ids.newId(), NotificationKind.INFO.name(), NotificationState.UNREAD.name(),
                    peerName, peerName + " 已接受你的好友请求", System.currentTimeMillis(),
                    0, reply.requestId()));
        } else {
            store.putFriend(new ClientStateStore.FriendRec(peerName, FriendState.REJECTED.name(),
                    null, null, System.currentTimeMillis()));
        }
        events.onNotificationsChanged();
        events.onStatusChanged();
    }

    // ---------------- friendship operations ----------------

    /** 发送好友请求；对端不可达时记为 UNDELIVERABLE 并返回 false。 */
    public boolean sendFriendRequest(String peerName) {
        synchronized (lock) {
            requireActive();
            Text.requireLocalName(peerName);
            if (peerName.equals(myName)) {
                throw new Errors.Usage("不能添加自己为好友");
            }
            FriendState current = friendState(peerName);
            if (current == FriendState.ACCEPTED) {
                throw new Errors.Conflict("你已经是 " + peerName + " 的好友");
            }
            if (current == FriendState.PENDING_IN) {
                throw new Errors.Conflict("来自 " + peerName
                        + " 的好友请求待处理 —— 请接受或拒绝");
            }
            if (current == FriendState.PENDING_OUT) {
                throw new Errors.Conflict("已向 " + peerName + " 发送的好友请求仍在等待处理");
            }
            Peer peer = peers.get(peerName);
            if (peer == null || !peer.sessionIsOpen()) {
                // 没有现成连接：若发现表里有未过期的记录，则先按它发起连接。
                DiscoveredPeer d = discovered.get(peerName);
                if (d != null && d.expiresAt() > System.currentTimeMillis()) {
                    connect(d.host(), d.port());
                    peer = peers.get(peerName);
                }
            }
            if (peer == null || !peer.sessionIsOpen()) {
                store.putFriend(new ClientStateStore.FriendRec(peerName, FriendState.UNDELIVERABLE.name(),
                        null, null, System.currentTimeMillis()));
                events.onStatusChanged();
                return false;
            }
            String requestId = Ids.newId();
            peer.session.conn().send(Wire.friendRequest(requestId, myName, peerName));
            store.putFriend(new ClientStateStore.FriendRec(peerName, FriendState.PENDING_OUT.name(),
                    peer.address, peer.fingerprint, System.currentTimeMillis()));
            events.onStatusChanged();
            return true;
        }
    }

    /** 接受待处理的好友请求（requestId 来自通知的 refId）。 */
    public void acceptFriendRequest(String peerName, String requestId) {
        synchronized (lock) {
            requireActive();
            Text.requireLocalName(peerName);
            Peer peer = peers.get(peerName);
            if (peer == null || !peer.sessionIsOpen()) {
                throw new Errors.Network("无法连接到 " + peerName
                        + " —— 仅当对端可达时才能发送接受请求");
            }
            peer.session.conn().send(Wire.friendReply(requestId, myName, peerName, true));
            store.putFriend(new ClientStateStore.FriendRec(peerName, FriendState.ACCEPTED.name(),
                    peer.address, peer.fingerprint, System.currentTimeMillis()));
            events.onStatusChanged();
        }
    }

    public void rejectFriendRequest(String peerName, String requestId) {
        synchronized (lock) {
            requireActive();
            Text.requireLocalName(peerName);
            Peer peer = peers.get(peerName);
            if (peer != null && peer.sessionIsOpen()) {
                peer.session.conn().send(Wire.friendReply(requestId, myName, peerName, false));
            }
            store.putFriend(new ClientStateStore.FriendRec(peerName, FriendState.REJECTED.name(),
                    null, null, System.currentTimeMillis()));
            events.onStatusChanged();
        }
    }

    // ---------------- messaging ----------------

    /** 发送普通消息给好友：要求已接受好友关系且连接在线。 */
    public void sendMessage(String peerName, String text) {
        synchronized (lock) {
            requireActive();
            Text.requireCleanText(text, "消息", Text.MAX_MESSAGE);
            if (friendState(peerName) != FriendState.ACCEPTED) {
                throw new Errors.Denied("你不是 " + peerName + " 的好友");
            }
            Peer peer = peers.get(peerName);
            if (peer == null || !peer.sessionIsOpen()) {
                throw new Errors.Network("无法连接到 " + peerName + " —— 消息未发送");
            }
            String msgId = Ids.newId();
            long ts = System.currentTimeMillis();
            peer.session.conn().send(Wire.message(msgId, myName, peerName, text));
            recordOutbound(peerName, msgId, text, ts);
        }
    }

    /** 仅测试用：向已连接对端发送裸帧，绕过本地检查。 */
    void rawSend(String peerName, Envelope envelope) {
        synchronized (lock) {
            Peer peer = peers.get(peerName);
            if (peer == null || !peer.sessionIsOpen()) {
                throw new Errors.Network("无法连接到 " + peerName);
            }
            peer.session.conn().send(envelope);
        }
    }

    private void recordInbound(String peerName, String msgId, String sender, String text, long ts) {
        String key = convKey(peerName);
        history.onMessage(key, peerName, new LocalHistoryStore.Entry(msgId, sender, text, ts, "in"));
        events.onMessage(ConversationRef.privateChat(peerName, false),
                new Message(msgId, key, sender, text, ts));
    }

    private void recordOutbound(String peerName, String msgId, String text, long ts) {
        String key = convKey(peerName);
        history.onMessage(key, peerName, new LocalHistoryStore.Entry(msgId, myName, text, ts, "out"));
        events.onMessage(ConversationRef.privateChat(peerName, false),
                new Message(msgId, key, myName, text, ts));
    }

    public String convKey(String peerName) {
        return "local:" + peerName;
    }

    // ---------------- status / queries ----------------

    public FriendState friendState(String peerName) {
        ClientStateStore.FriendRec f = store.friend(peerName);
        return f == null ? FriendState.NONE : FriendState.valueOf(f.state());
    }

    /** 观察者相对状态：有活动连接为 CONNECTED；近期发现响应为 DISCOVERED；否则未确认。 */
    public PeerStatus peerStatus(String peerName) {
        Peer peer;
        synchronized (lock) {
            peer = peers.get(peerName);
        }
        if (peer != null && peer.sessionIsOpen()) {
            return PeerStatus.CONNECTED;
        }
        DiscoveredPeer d = discovered.get(peerName);
        if (d != null && d.expiresAt() > System.currentTimeMillis()) {
            return PeerStatus.DISCOVERED;
        }
        return PeerStatus.UNCONFIRMED;
    }

    public List<String> friends() {
        List<String> out = new ArrayList<>();
        for (var e : store.state().friends.entrySet()) {
            if (FriendState.valueOf(e.getValue().state()) == FriendState.ACCEPTED) {
                out.add(e.getKey());
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /**
     * 返回对端当前已知的网络地址（host:port），用于本地会话列表展示。
     *
     * <p>数据来源按可靠性排序：
     * <ol>
     *   <li>已建立连接的实际对端地址；</li>
     *   <li>最近一次发现响应中的地址与端口；</li>
     *   <li>都没有时返回 null——UI 必须显示“地址未知”，不得编造。</li>
     * </ol>
     * 本方法只读取观察者已知的信息，不发起连接，也不改变任何状态。
     */
    public String peerEndpoint(String peerName) {
        synchronized (lock) {
            Peer peer = peers.get(peerName);
            if (peer != null && peer.address != null && !peer.address.isBlank()) {
                return peer.address;
            }
        }
        DiscoveredPeer d = discovered.get(peerName);
        if (d != null && d.expiresAt() > System.currentTimeMillis()) {
            return d.host() + ":" + d.port();
        }
        return null;
    }

    public List<DiscoveredPeer> discoveredPeers() {
        long now = System.currentTimeMillis();
        return discovered.values().stream()
                .filter(d -> d.expiresAt() > now)
                .sorted(Comparator.comparing(DiscoveredPeer::name))
                .toList();
    }

    public void runDiscovery() {
        if (discovery != null) {
            discovery.query();
        }
    }

    public boolean discoveryAvailable() {
        return discovery != null && discovery.available();
    }

    /** 发现响应回调：登记对端（含地址/端口/指纹，带过期时间），无连接时置为 DISCOVERED。 */
    private void onDiscoveryReply(org.ourcamellia.common.protocol.WireMessages.DiscoverReply reply, InetAddress source) {
        if (myName == null || reply.username().equals(myName)) {
            return; // 自己发出的探测回显，忽略
        }
        discovered.put(reply.username(), new DiscoveredPeer(reply.username(),
                source.getHostAddress(), reply.tcpPort(), reply.fp(), reply.expiresAt()));
        synchronized (lock) {
            if (peers.get(reply.username()) == null) {
                events.onPeerStatus(reply.username(), PeerStatus.DISCOVERED);
            }
        }
        events.onStatusChanged();
    }

    /** 更新对端状态并通知 UI（事件回调统一在持有 lock 或安全前提下触发）。 */
    private void setStatus(String name, PeerStatus status, String address) {
        Peer peer = peers.get(name);
        if (peer != null) {
            peer.status = status;
            if (address != null) {
                peer.address = address;
            }
        }
        events.onPeerStatus(name, status);
        events.onStatusChanged();
    }

    private void onPeerDisconnected(String name) {
        synchronized (lock) {
            peers.remove(name);
        }
        // 诚实状态：我们无法区分「离线」与「未知」，统一回到 UNCONFIRMED。
        events.onPeerStatus(name, PeerStatus.UNCONFIRMED);
        events.onStatusChanged();
    }

    private String peerFingerprint(String name) {
        Peer p = peers.get(name);
        return p != null ? p.fingerprint : null;
    }

    private String peerAddress(String name) {
        Peer p = peers.get(name);
        return p != null ? p.address : null;
    }

    private void requireActive() {
        if (myName == null) {
            throw new Errors.Usage("本地模式尚未激活");
        }
    }

    /** 关闭本地模式：停发现、关监听、关闭全部对端会话。 */
    @Override
    public void close() {
        if (closed.getAndSet(true)) {
            return;
        }
        if (discovery != null) {
            discovery.close();
        }
        if (listener != null) {
            try {
                listener.close();
            } catch (java.io.IOException ignored) {
            }
        }
        synchronized (lock) {
            for (Peer p : new ArrayList<>(peers.values())) {
                if (p.session != null) {
                    p.session.close();
                }
            }
            peers.clear();
        }
        events.onShutdown();
    }
}
