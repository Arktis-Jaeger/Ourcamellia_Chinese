package org.ourcamellia.core.transport;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.SelfSignedCerts;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;

/**
 * TLS 连接建立（JSSE）与基于“首次信任”（TOFU）的证书指纹固定。
 *
 * 客户端侧：当 {@code expectedFingerprint} 为 null 时，接受对方出示的证书并返回其
 * 指纹（由调用方持久化该固定值——TOFU）。当已有固定值且实际指纹不一致时，拒绝
 * 连接并给出明确警告（疑似身份伪造；SECURITY.md 威胁：仿冒）。
 *
 * 服务器侧：出示配置好的身份证书。
 */
public final class TlsConnections {

    private TlsConnections() {}

    // 建立带指纹固定的 TLS 客户端连接；返回已连接套接字与对端指纹
    public static ConnectionResult connect(String host, int port, String expectedFingerprint,
                                          SSLContext clientContext, int connectTimeoutMs) {
        try {
            SSLSocketFactory factory = clientContext.getSocketFactory();
            // 先按超时建立底层 TCP，再做 TLS 握手
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            SSLSocket ssl = (SSLSocket) factory.createSocket(
                    raw, host, port, true);
            ssl.setUseClientMode(true);
            ssl.startHandshake();
            // 取对端证书指纹；若与已固定的 TOFU 值不一致则拒绝连接（防仿冒）
            X509Certificate cert = (X509Certificate) ssl.getSession().getPeerCertificates()[0];
            String actual = SelfSignedCerts.fingerprint(cert);
            if (expectedFingerprint != null && !expectedFingerprint.equalsIgnoreCase(actual)) {
                ssl.close();
                throw new Errors.Network(
                        "证书指纹不匹配（" + host + ":" + port + "，疑似身份伪造），连接被拒绝："
                                + "期望固定指纹 " + expectedFingerprint + "，实际为 " + actual);
            }
            return new ConnectionResult(ssl, actual);
        } catch (Errors.OcException e) {
            throw e;
        } catch (IOException e) {
            // 保留原始连接异常信息，便于定位网络/握手问题
            throw new Errors.Network("无法连接到 " + host + ":" + port + "：" + e.getMessage(), e);
        }
    }

    // 在接受连接并完成 TLS 握手时强制握手超时，防止慢速攻击占住线程
    public static SSLSocket accept(javax.net.ssl.SSLServerSocket serverSocket, int handshakeTimeoutMs) {
        try {
            Socket raw = serverSocket.accept();
            raw.setTcpNoDelay(true);
            if (!(raw instanceof SSLSocket ssl)) {
                raw.close();
                throw new Errors.Network("收到非 TLS 明文套接字（预期为 TLS 连接）");
            }
            ssl.setUseClientMode(false);
            ssl.setSoTimeout(handshakeTimeoutMs);
            ssl.startHandshake();
            ssl.setSoTimeout(0);
            return ssl;
        } catch (IOException e) {
            throw new Errors.Network("TLS 接受连接失败：" + e.getMessage(), e);
        }
    }

    /** Creates a TLS server socket bound to host:port with the given identity context. */
    public static javax.net.ssl.SSLServerSocket listen(String host, int port, SSLContext serverContext) {
        return listen(host, port, serverContext, false);
    }

    /**
     * 创建 TLS 服务器套接字；当 requireClientAuth 为 true 时要求客户端出示证书
     * （本地模式使用，双方以各自的身份证书互认——身份 = 地址 + 密钥指纹）。
     */
    public static javax.net.ssl.SSLServerSocket listen(String host, int port, SSLContext serverContext,
                                                       boolean requireClientAuth) {
        try {
            var factory = serverContext.getServerSocketFactory();
            javax.net.ssl.SSLServerSocket ss = (javax.net.ssl.SSLServerSocket) factory.createServerSocket();
            ss.setReuseAddress(true);
            if (requireClientAuth) {
                ss.setNeedClientAuth(true);
            }
            ss.bind(new InetSocketAddress(host, port));
            return ss;
        } catch (IOException e) {
            throw new Errors.Network("无法绑定 " + host + ":" + port + "：" + e.getMessage(), e);
        }
    }

    public record ConnectionResult(SSLSocket socket, String fingerprint) {}
}
