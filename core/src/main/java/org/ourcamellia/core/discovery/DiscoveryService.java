package org.ourcamellia.core.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Ids;
import org.ourcamellia.common.Json;
import org.ourcamellia.common.protocol.Wire;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 本地模式的 UDP 发现（REQUIREMENTS §3.1）：与直连相互独立的能力。查询以广播
 * 发出；只有同时“可被发现”且“可连接”的对端才会以单播应答
 * {username, tcpPort, certFingerprint, expiry}。应答绝不代表“当前在线”——它只
 * 用于填充对端目录。在广播不可用/被屏蔽的网络中，查询无结果，但手动输入地址
 * 仍然完全可用（已记录的限制）。
 */
public final class DiscoveryService implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(DiscoveryService.class.getName());
    public static final int MAX_DATAGRAM = 4096;

    private final int port;
    private final BooleanSupplier discoverable;
    private final BooleanSupplier connectable;
    private final Supplier<String> username;
    private final Supplier<Integer> tcpPort;
    private final Supplier<String> fingerprint;
    private final long ttlMillis;
    private final InetAddress broadcastOverride; // 测试钩子（单播到回环地址）

    private DatagramSocket socket;
    private Thread receiver;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean sendBroadcast = new AtomicBoolean(true);

    public DiscoveryService(int port, BooleanSupplier discoverable, BooleanSupplier connectable,
                            Supplier<String> username, Supplier<Integer> tcpPort,
                            Supplier<String> fingerprint, long ttlMillis,
                            InetAddress broadcastOverride) {
        this.port = port;
        this.discoverable = discoverable;
        this.connectable = connectable;
        this.username = username;
        this.tcpPort = tcpPort;
        this.fingerprint = fingerprint;
        this.ttlMillis = ttlMillis;
        this.broadcastOverride = broadcastOverride;
    }

    /** 绑定 UDP 套接字。端口冲突以清晰、非致命的错误呈现。 */
    public void start() {
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            socket.setBroadcast(true);
            running.set(true);
            // 接收循环运行在专用虚拟线程上
            receiver = Thread.ofVirtual().name("discovery-udp").start(this::receiveLoop);
        } catch (IOException e) {
            socket = null;
            running.set(false);
            LOG.log(Level.WARNING, "端口 {0} 上无法使用 UDP 发现：{1}",
                    new Object[]{port, e.getMessage()});
        }
    }

    /** 发送一次发现查询；应答异步到达（见 setReplySink）。 */
    public String query() {
        String id = Ids.newId();
        if (!running.get() || socket == null) {
            return id; // 发现不可用；返回空结果
        }
        try {
            byte[] payload = Json.writeBytes(Wire.discoverQuery(id));
            InetAddress target = broadcastOverride != null
                    ? broadcastOverride
                    : InetAddress.getByName("255.255.255.255");
            if (sendBroadcast.get()) {
                socket.send(new DatagramPacket(payload, payload.length, target, port));
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "发现查询发送失败：{0}", e.getMessage());
        }
        return id;
    }

    /** 直接向一个地址发送查询（供测试与手动刷新使用）。 */
    public String queryTo(InetAddress target) {
        return queryTo(target, port);
    }

    /** 直接向 地址:端口 发送查询（回环测试使用此重载）。 */
    public String queryTo(InetAddress target, int targetPort) {
        if (socket == null) {
            return Ids.newId();
        }
        String id = Ids.newId();
        try {
            byte[] payload = Json.writeBytes(Wire.discoverQuery(id));
            socket.send(new DatagramPacket(payload, payload.length, target, targetPort));
        } catch (IOException e) {
            LOG.log(Level.FINE, "发现单播发送失败：{0}", e.getMessage());
        }
        return id;
    }

    /** 实际绑定的 UDP 端口（不可用时为 0）。 */
    public int boundPort() {
        return socket == null || !socket.isBound() ? 0 : socket.getLocalPort();
    }

    public void setSendBroadcast(boolean enabled) {
        sendBroadcast.set(enabled);
    }

    public interface ReplySink {
        void onReply(org.ourcamellia.common.protocol.WireMessages.DiscoverReply reply, InetAddress source);
    }

    private volatile ReplySink replySink = (reply, source) -> {};

    public void setReplySink(ReplySink sink) {
        this.replySink = sink;
    }

    // 接收循环：解析数据报，按 type 分发——查询则回应当前身份，应答则投递给 replySink
    private void receiveLoop() {
        byte[] buf = new byte[MAX_DATAGRAM];
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                JsonNode root = Json.parse(java.util.Arrays.copyOf(packet.getData(), packet.getLength()));
                String type = root.path("type").asText("");
                if (type.equals(Wire.T_DISCOVER_QUERY)) {
                    handleQuery(packet);
                } else if (type.equals(Wire.T_DISCOVER_REPLY)) {
                    var reply = Wire.parseDiscoverReply(root);
                    if (reply.expiresAt() > System.currentTimeMillis()) {
                        replySink.onReply(reply, packet.getAddress());
                    }
                }
            } catch (Errors.OcException | IOException e) {
                // 格式错误的数据报直接丢弃；套接字继续服务
                LOG.log(Level.FINE, "跳过无法解析的发现数据报：{0}", e.getMessage());
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "发现接收出错", e);
            }
        }
    }

    // 收到查询：仅当“可被发现”且“可连接”时，用本机身份单播应答
    private void handleQuery(DatagramPacket packet) {
        if (!discoverable.getAsBoolean() || !connectable.getAsBoolean()) {
            return; // 配置为隐藏：保持沉默
        }
        try {
            var query = Wire.parseDiscoverQuery(Json.parse(
                    java.util.Arrays.copyOf(packet.getData(), packet.getLength())));
            String name = username.get();
            String fp = fingerprint.get();
            if (name == null || fp == null) {
                return;
            }
            JsonNode reply = Wire.discoverReply(query.id(), name, tcpPort.get(), fp,
                    System.currentTimeMillis() + ttlMillis);
            byte[] payload = Json.writeBytes(reply);
            socket.send(new DatagramPacket(payload, payload.length,
                    packet.getAddress(), packet.getPort()));
        } catch (Errors.OcException | IOException e) {
            LOG.log(Level.FINE, "发现应答发送失败：{0}", e.getMessage());
        }
    }

    public boolean available() {
        return running.get() && socket != null;
    }

    // 关闭：停止接收循环并释放 UDP 套接字
    @Override
    public void close() {
        running.set(false);
        if (socket != null) {
            socket.close();
        }
    }
}
