package org.ourcamellia.core.transport;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.protocol.Envelope;
import org.ourcamellia.common.protocol.FrameCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 供协议测试使用的内存连接对：两条 {@link ReadableConn} 的流两两对接。
 * 非生产代码——仅用于测试及嵌入式（进程内）BOT 适配器。
 */
public final class InMemoryLink {

    private InMemoryLink() {}

    /** 用管道流创建一对互连的连接；任一端写入即被另一端读出 */
    public static Pair create(String nameA, String nameB, int maxFrame) {
        try {
            PipedInputStream aIn = new PipedInputStream(1 << 20);
            PipedOutputStream aOut = new PipedOutputStream(aIn);
            PipedInputStream bIn = new PipedInputStream(1 << 20);
            PipedOutputStream bOut = new PipedOutputStream(bIn);
            RawConn a = new RawConn(nameA, aIn, aOut, maxFrame);
            RawConn b = new RawConn(nameB, bIn, bOut, maxFrame);
            return new Pair(a, b);
        } catch (IOException e) {
            throw new Errors.Internal("无法创建内存链路", e);
        }
    }

    public record Pair(ReadableConn a, ReadableConn b) {}

    /** 基于管道流的连接；无 TLS（仅供测试/嵌入使用）。 */
    public static final class RawConn implements ReadableConn {
        private final String name;
        private final InputStream in;
        private final OutputStream out;
        private final FrameCodec codec;
        private final AtomicBoolean open = new AtomicBoolean(true);

        RawConn(String name, InputStream in, OutputStream out, int maxFrame) {
            this.name = name;
            this.in = in;
            this.out = out;
            this.codec = new FrameCodec(maxFrame);
        }

        @Override
        public synchronized void send(Envelope envelope) {
            if (!open.get()) {
                throw new Errors.Network("连接已关闭");
            }
            codec.write(out, envelope.root());
        }

        @Override
        public InputStream inputStream() {
            return in;
        }

        @Override
        public FrameCodec codec() {
            return codec;
        }

        @Override
        public boolean isOpen() {
            return open.get();
        }

        @Override
        public String remoteDescription() {
            return name;
        }

        @Override
        public String remoteFingerprint() {
            return null;
        }

        @Override
        public void close() {
            open.set(false);
        }
    }
}
