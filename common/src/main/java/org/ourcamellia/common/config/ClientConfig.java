package org.ourcamellia.common.config;

import org.ourcamellia.common.Errors;

import java.nio.file.Path;
import java.util.Map;

/** 客户端配置（带安全默认值，REQUIREMENTS §9）。 */
public record ClientConfig(
        String mode,                       // "observatory" | "old" | "auto"（默认 auto——基于 tty 自动选择）
        String dataDir,                    // 默认：~/.ourcamellia
        boolean defaultRecording,          // 新会话是否自动开启历史录制
        boolean discoverable,              // 本地模式：是否响应发现查询
        boolean connectable,               // 本地模式：是否接受入站连接
        String listenHost,
        int listenPort,
        int discoveryPort,
        int discoveryTtlSeconds,
        int connectTimeoutMs,
        int readTimeoutMs,
        int pingIntervalMs,
        int maxFrameBytes,
        boolean secondaryPasswordProtectHistory,
        int secondaryPasswordIterations,
        boolean exitPromptOnBuffered,
        String logLevel) {

    public static ClientConfig defaults() {
        return new ClientConfig(
                "auto",
                defaultDataDir(),
                true,
                true,
                true,
                "0.0.0.0",
                54536,
                54535,
                60,
                10_000,
                90_000,
                30_000,
                1 << 20,
                false,
                210_000,
                true,
                "info");
    }

    private static String defaultDataDir() {
        String home = System.getProperty("user.home");
        return (home == null ? "." : home) + java.io.File.separator + ".ourcamellia";
    }

    /** 校验配置值：模式取值、端口范围与互斥、帧大小、二级密码迭代次数。 */
    public ClientConfig validated() {
        if (mode != null && !mode.equals("auto") && !mode.equals("observatory") && !mode.equals("old")) {
            throw new Errors.Validation("config: mode 必须为 auto|observatory|old");
        }
        if (listenPort < 1 || listenPort > 65535 || discoveryPort < 1 || discoveryPort > 65535) {
            throw new Errors.Validation("config: 端口无效");
        }
        if (listenPort == discoveryPort) {
            throw new Errors.Validation("config: listenPort 与 discoveryPort 必须不同");
        }
        if (maxFrameBytes < 1024 || maxFrameBytes > 1 << 24) {
            throw new Errors.Validation("config: maxFrameBytes 超出允许范围");
        }
        if (secondaryPasswordIterations < 10_000) {
            throw new Errors.Validation("config: secondaryPasswordIterations 过低（不安全）");
        }
        return this;
    }

    public Path dataDirPath() {
        return Path.of(dataDir);
    }
}