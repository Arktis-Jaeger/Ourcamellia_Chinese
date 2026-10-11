package org.ourcamellia.core.verifier;

import org.ourcamellia.common.Errors;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * 对运营者配置的外部校验器 URL 的 SSRF 防护（SECURITY.md §6）：
 * 协议白名单、拦截私网/回环/链路本地地址、校验重定向目标、限制重定向链长度。
 * 每次跳转只做一次 DNS 解析并对每个解析出的地址逐一检查；检查与连接之间的
 * 残余 DNS 重绑定竞态窗口在 THREAT_MODEL.md 中有说明。
 */
public final class SsrGuard {

    private SsrGuard() {}

    /** 当 URL 违反策略时抛出 Validation。 */
    public static URI check(String url, boolean allowPrivateTargets, int maxRedirects) {
        if (url == null || url.isBlank()) {
            throw new Errors.Validation("校验器 URL 为空");
        }
        if (url.length() > 2048) {
            throw new Errors.Validation("校验器 URL 过长");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new Errors.Validation("校验器 URL 不是合法的 URI");
        }
        checkHop(uri, allowPrivateTargets);
        return uri;
    }

    // 检查一跳：协议白名单 + 主机解析 + 私网地址拦截
    private static void checkHop(URI uri, boolean allowPrivateTargets) {
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new Errors.Validation("校验器 URL 的协议必须是 http 或 https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new Errors.Validation("校验器 URL 缺少主机名");
        }
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            if (all.length == 0) {
                throw new Errors.Validation("校验器主机名无法解析");
            }
            for (InetAddress a : all) {
                if (!allowPrivateTargets && isBlocked(a)) {
                    throw new Errors.Validation("校验器 URL 解析到非公网地址："
                            + a.getHostAddress() + "（SSRF 防护）");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new Errors.Validation("校验器主机名无法解析");
        }
    }

    static boolean isBlocked(InetAddress a) {
        return a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress();
    }

    /** 校验重定向的 Location 头；返回绝对 URI，非法时返回 null。 */
    public static URI validateRedirect(URI base, String location, boolean allowPrivateTargets) {
        if (location == null || location.isBlank()) {
            return null;
        }
        try {
            URI next = base.resolve(location);
            checkHop(next, allowPrivateTargets);
            return next;
        } catch (Errors.OcException | IllegalArgumentException e) {
            return null;
        }
    }
}
