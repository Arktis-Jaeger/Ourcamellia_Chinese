package org.ourcamellia.core.transport;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.protocol.Envelope;
import org.ourcamellia.common.protocol.FrameCodec;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** 基于 TLS（或裸测试）套接字的 Conn 实现。 */
public final class SocketConn implements ReadableConn {

    private final java.net.Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final FrameCodec codec;
    private final String description;
    private final String fingerprint;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Object sendLock = new Object();

    public SocketConn(java.net.Socket socket, String description, String fingerprint, int maxFrame) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.codec = new FrameCodec(maxFrame);
        this.description = description;
        this.fingerprint = fingerprint;
    }

    @Override
    public void send(Envelope envelope) {
        // 连接关闭后再发送直接报错；发送经由锁串行化，保证帧不交错
        if (!open.get()) {
            throw new Errors.Network("connection closed");
        }
        synchronized (sendLock) {
            codec.write(out, envelope.root());
        }
    }

    /** 供会话线程直接使用的原始输入流。 */
    @Override
    public InputStream inputStream() {
        return in;
    }

    @Override
    public FrameCodec codec() {
        return codec;
    }

    @Override
    public void setSoTimeout(int ms) {
        try {
            socket.setSoTimeout(ms);
        } catch (IOException ignored) {
        }
    }

    @Override
    public boolean isOpen() {
        return open.get() && !socket.isClosed();
    }

    @Override
    public String remoteDescription() {
        return description;
    }

    @Override
    public String remoteFingerprint() {
        return fingerprint;
    }

    @Override
    public void close() {
        // 幂等关闭：仅第一次真正关闭底层套接字
        if (open.getAndSet(false)) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
