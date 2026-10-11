package org.ourcamellia.core.transport;

import org.ourcamellia.common.protocol.FrameCodec;

import java.io.InputStream;

/** 可直接读取的 Conn（供会话读线程使用）。 */
public interface ReadableConn extends Conn {

    InputStream inputStream();

    FrameCodec codec();

    /** 设置驱动心跳的读超时；非套接字链路为空操作。 */
    default void setSoTimeout(int ms) {
    }
}
