package org.ourcamellia.core.app;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.SelfSignedCerts;
import org.ourcamellia.common.protocol.Envelope;
import org.ourcamellia.common.protocol.Wire;
import org.ourcamellia.core.persist.AtomicFiles;
import org.ourcamellia.core.transport.Conn;
import org.ourcamellia.core.transport.ConnSession;
import org.ourcamellia.core.transport.SocketConn;
import org.ourcamellia.core.transport.TlsConnections;

import java.nio.file.Path;

/**
 * 服务器模式的连接建立：对服务器证书做 TOFU 指纹固定。客户端把固定指纹按服务器地址
 * 存入状态中；指纹一旦变化即拒绝连接（可能被冒充），除非用户清除该固定记录。
 *
 * 登录后所有服务器协议帧（登录/注册/好友/群组/通知）由 ServerProtocol 处理；
 * 本类负责套接字生命周期（连接句柄职责）。
 */
final class ServerConnection {

    private ServerConnection() {}

    /** 建立连接：加载客户端身份 → TOFU 校验服务器指纹 → 组装 ConnSession 并发送 HELLO。 */
    static void connect(ServerSession session, String host, int port, String address) {
        var cfg = session.config();
        Path dataDir = session.app().dataDir();
        SelfSignedCerts.GeneratedCert identity;
        Path ks = dataDir.resolve("server-identity.p12");
        String ksPassword = readServerKsPassword(dataDir); // 首次使用时创建密码
        try {
            if (java.nio.file.Files.exists(ks)) {
                identity = SelfSignedCerts.loadKeystore(ks, ksPassword);
            } else {
                identity = SelfSignedCerts.generate("ourcamellia-client", 3650);
                SelfSignedCerts.saveKeystore(ks, identity.cert(), identity.key(), ksPassword);
            }
        } catch (Errors.OcException e) {
            throw new Errors.Persistence("客户端身份不可用：" + e.getMessage(), e);
        }
        // 仅当服务器要求双向 TLS 时才出示客户端身份（v1 协议不要求）。
        var clientContext = SelfSignedCerts.trustAllContext(null, null);

        // 先取已固定的期望指纹；首次连接无记录时按 TOFU 首次信任并固定。
        String expectedPin = PinGuard.expectedFingerprint(session.store(), address);
        var result = TlsConnections.connect(host, port, expectedPin, clientContext, cfg.connectTimeoutMs());
        PinGuard.check(session.store(), result.fingerprint(), address);
        String desc = host + ":" + port;
        SocketConn conn;
        try {
            conn = new SocketConn(result.socket(), desc, result.fingerprint(), cfg.maxFrameBytes());
        } catch (java.io.IOException e) {
            throw new Errors.Network("连接建立失败", e);
        }
        // ConnSession 负责心跳（pingIntervalMs）与读超时恢复（readTimeoutMs）：
        // 超时/断开时走 onClosed → ServerSession.onClosed 清理会话状态。
        ConnSession cs = new ConnSession(conn, new ConnSession.Handler() {
            @Override
            public void onEnvelope(Conn c, Envelope envelope) {
                ServerProtocol.handle(session, c, envelope);
            }

            @Override
            public void onClosed(Conn c, String reason) {
                session.onClosed(reason);
            }
        }, cfg.pingIntervalMs(), cfg.readTimeoutMs());
        session.setSession(cs);
        cs.conn().send(Wire.hello("ourcamellia-client", String.valueOf(Envelope.PROTOCOL_VERSION)));
    }

    private static String readServerKsPassword(Path dataDir) {
        byte[] raw = AtomicFiles.readOrNull(dataDir.resolve("server-identity.password"));
        if (raw != null) {
            return new String(raw, java.nio.charset.StandardCharsets.US_ASCII).trim();
        }
        return createServerKsPassword(dataDir);
    }

    private static String createServerKsPassword(Path dataDir) {
        String pw = org.ourcamellia.common.CryptoUtil.randomHex(16);
        AtomicFiles.writeString(dataDir.resolve("server-identity.password"), pw + "\n");
        try {
            java.nio.file.Files.setPosixFilePermissions(dataDir.resolve("server-identity.password"),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
        }
        return pw;
    }
}
