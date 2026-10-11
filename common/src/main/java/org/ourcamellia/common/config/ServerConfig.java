package org.ourcamellia.common.config;

import org.ourcamellia.common.Errors;

import java.nio.file.Path;
import java.util.List;

/** 服务器配置（带安全默认值，REQUIREMENTS §9）。 */
public record ServerConfig(
        String bindHost,
        int port,
        String serverName,
        String dataDir,
        boolean tlsAutoGenerate,
        String tlsKeystorePath,
        String tlsKeystorePassword,      // 留空 = 首次运行时自动生成随机密码
        List<String> requiredRegistrationFields,  // 固定核心：@UserName、Passwd；可扩展
        boolean requireEmail,
        String registrationPolicy,       // auto | manual | external
        int pendingTtlMinutes,
        VerifierConfig verifier,
        int notificationRetentionDays,
        boolean encryptedGroupsAllowed,
        BotApiConfig botApi,
        RateLimitConfig rateLimits,
        List<String> adminUsernames,
        int passwordHashIterations,
        int reviewLogRetention,
        int maxFrameBytes,
        int connectTimeoutMs,
        int readTimeoutMs,
        int pingIntervalMs,
        String logLevel) {

    public record VerifierConfig(
            String url,                    // "" = 禁用外部核验
            String sharedSecret,
            int timeoutMs,
            int maxRetries,
            boolean allowPrivateTargets,
            int maxRedirects) {
    }

    public record BotApiConfig(boolean enabled, String bindHost, int port, int rateLimitPerMinute) {}

    public record RateLimitConfig(
            int loginPerMinute,
            int registerPerMinute,
            int connectionsPerMinute,
            int friendRequestPerMinute,
            int messagePerMinute,
            int groupOpPerMinute) {}

    public static ServerConfig defaults() {
        return new ServerConfig(
                "0.0.0.0",
                54530,
                "Ourcamellia Server",
                "data",
                true,
                "tls/keystore.p12",
                "",
                List.of("@Email"),
                true,
                "auto",
                10_080,
                new VerifierConfig("", "", 10_000, 2, false, 3),
                30,
                true,
                new BotApiConfig(false, "127.0.0.1", 54700, 120),
                new RateLimitConfig(5, 3, 60, 10, 120, 30),
                List.of("@admin"),
                210_000,
                10_000,
                1 << 20,
                10_000,
                90_000,
                30_000,
                "info");
    }

    /** 校验配置值：端口、服务器名、注册策略、外部核验、保留期、迭代次数、管理员名单等。 */
    public ServerConfig validated() {
        if (port < 1 || port > 65535) {
            throw new Errors.Validation("config: 端口无效");
        }
        if (serverName == null || serverName.isBlank() || serverName.length() > 128) {
            throw new Errors.Validation("config: 必须提供 serverName（1-128 字符）");
        }
        if (!registrationPolicy.equals("auto") && !registrationPolicy.equals("manual")
                && !registrationPolicy.equals("external")) {
            throw new Errors.Validation("config: registrationPolicy 必须为 auto|manual|external");
        }
        if (registrationPolicy.equals("external")) {
            if (verifier == null || verifier.url().isBlank() || verifier.sharedSecret().isBlank()) {
                throw new Errors.Validation("config: external 注册策略需要配置 verifier.url 与 verifier.sharedSecret");
            }
        }
        if (pendingTtlMinutes < 1 || notificationRetentionDays < 1 || reviewLogRetention < 100) {
            throw new Errors.Validation("config: 保留期配置超出范围");
        }
        if (passwordHashIterations < 10_000) {
            throw new Errors.Validation("config: passwordHashIterations 过低（不安全）");
        }
        if (maxFrameBytes < 1024 || maxFrameBytes > 1 << 24) {
            throw new Errors.Validation("config: maxFrameBytes 超出允许范围");
        }
        if (adminUsernames == null || adminUsernames.isEmpty()) {
            throw new Errors.Validation("config: 至少需要一个管理员用户名用于引导运营者");
        }
        for (String a : adminUsernames) {
            org.ourcamellia.common.Text.requireServerName(a);
        }
        for (String f : requiredRegistrationFields) {
            if (f.isBlank() || f.length() > 32) {
                throw new Errors.Validation("config: 无效的注册字段名");
            }
        }
        return this;
    }

    public Path dataDirPath() {
        return Path.of(dataDir);
    }
}