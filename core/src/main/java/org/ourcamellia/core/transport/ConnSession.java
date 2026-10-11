package org.ourcamellia.core.transport;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.protocol.Envelope;
import org.ourcamellia.common.protocol.Wire;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 一条存活的连接：拥有专属的读线程（虚拟线程）、心跳（ping/pong）、读超时
 * 检测与处理器（Handler）分发。发送经由 {@link Conn} 同步。格式错误的帧或
 * 协议违规会关闭连接；常规断开不会导致进程崩溃。
 */
public final class ConnSession implements AutoCloseable {

    public interface Handler {
        void onEnvelope(Conn conn, Envelope envelope);

        void onClosed(Conn conn, String reason);
    }

    private static final Logger LOG = Logger.getLogger(ConnSession.class.getName());

    private final ReadableConn conn;
    private final Handler handler;
    private final int pingIntervalMs;
    private final int readTimeoutMs;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread reader;
    private volatile long lastReadAt = System.currentTimeMillis();

    public ConnSession(ReadableConn conn, Handler handler, int pingIntervalMs, int readTimeoutMs) {
        this.conn = conn;
        this.handler = handler;
        this.pingIntervalMs = pingIntervalMs;
        this.readTimeoutMs = readTimeoutMs;
        // 底层套接字读超时驱动心跳：至少 1 秒，避免过短超时造成误判
        conn.setSoTimeout(Math.max(1000, pingIntervalMs));
        // 启动专用虚拟线程执行读循环
        this.reader = Thread.ofVirtual().name("conn-" + conn.remoteDescription()).start(this::readLoop);
    }

    public ReadableConn conn() {
        return conn;
    }

    // 读循环：帧解码 → ping/pong 心跳 → 业务分发；任何异常都会带原因关闭连接
    private void readLoop() {
        String reason = "连接已关闭";
        try {
            while (conn.isOpen()) {
                Envelope e;
                try {
                    // 从输入流读取并解码一帧
                    var node = conn.codec().read(conn.inputStream());
                    lastReadAt = System.currentTimeMillis();
                    if (node == null) {
                        reason = "远端已关闭连接";
                        break;
                    }
                    e = Envelope.of(node);
                } catch (Errors.FrameTimeout timeout) {
                    // 读超时：若超过读超时阈值则判定失联；否则发送 ping 心跳探测
                    long now = System.currentTimeMillis();
                    if (now - lastReadAt > readTimeoutMs) {
                        reason = "读取超时";
                        break;
                    }
                    try {
                        conn.send(Wire.ping());
                    } catch (Errors.OcException sendFailure) {
                        reason = "发送失败";
                        break;
                    }
                    continue;
                }
                if (e.type().equals(Wire.T_PING)) {
                    conn.send(Wire.pong(e.id()));
                    continue;
                }
                if (e.type().equals(Wire.T_PONG)) {
                    continue;
                }
                if (e.type().equals(Wire.T_BYE)) {
                    reason = "远端发出道别（T_BYE）";
                    break;
                }
                try {
                    // 业务信封交给处理器；格式错误视为协议级违规并断开
                    handler.onEnvelope(conn, e);
                } catch (Errors.Malformed bad) {
                    LOG.log(Level.WARNING, "来自 {0} 的帧格式错误：{1}",
                            new Object[]{conn.remoteDescription(), bad.getMessage()});
                    reason = "帧格式错误";
                    break;
                }
            }
        } catch (Errors.Malformed e) {
            LOG.log(Level.WARNING, "来自 {0} 的协议违规：{1}",
                    new Object[]{conn.remoteDescription(), e.getMessage()});
            reason = "协议违规";
        } catch (Errors.Network e) {
            reason = "连接丢失";
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "来自 " + conn.remoteDescription() + " 的意外连接错误", t);
            reason = "内部错误";
        } finally {
            close(reason);
        }
    }

    public boolean isOpen() {
        return conn.isOpen();
    }

    @Override
    public void close() {
        close("本地关闭");
    }

    // 幂等关闭：只执行一次，先关底层连接，再通知处理器（回调异常不外泄）
    private void close(String reason) {
        if (closed.getAndSet(true)) {
            return;
        }
        conn.close();
        try {
            handler.onClosed(conn, reason);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "onClosed 回调执行失败", e);
        }
    }
}
