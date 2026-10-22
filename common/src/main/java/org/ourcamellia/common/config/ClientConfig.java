package org.ourcamellia.common.config;

import org.ourcamellia.common.Errors;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 客户端配置。
 *
 * <p>字段分为三类：
 * <ol>
 *   <li>展示层：<code>terminal.mode</code>（OBSERVATORY 或 OLD）与
 *       <code>terminal.shiftEnterToSend</code>；</li>
 *   <li>本地网络：监听端口、发现端口、超时、帧大小等；</li>
 *   <li>数据与安全：dataDir、录制默认值、二级密码保护、日志级别。</li>
 * </ol>
 *
 * <p>兼容性说明：旧版本的顶层 <code>mode</code>（auto/observatory/old）仍然保留。
 * 若新配置没有明确改变 <code>terminal.mode</code>（仍为默认 OBSERVATORY），
 * 则继续尊重旧的 <code>mode=old</code>/<code>mode=observatory</code>；
 * 这样升级不会让 Old 模式用户被静默切到 Observatory。详见
 * {@link #effectiveTerminalMode()}。
 */
public record ClientConfig(
        String mode,                       // 旧版展示模式字段："auto" | "observatory" | "old"（仅兼容用）
        String dataDir,                    // 数据目录；默认 <程序目录>/data
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
        String logLevel,
        Terminal terminal) {

    /** 终端展示层配置。 */
    public record Terminal(String mode, boolean shiftEnterToSend) {

        public static final String OBSERVATORY = "OBSERVATORY";
        public static final String OLD = "OLD";

        public static Terminal defaults() {
            // 默认必须是 Observatory；shiftEnterToSend 默认 true（Enter 换行、Shift+Enter 发送）。
            return new Terminal(OBSERVATORY, true);
        }

        /**
         * 校验并规范化展示模式。
         *
         * <p>允许大小写混写，但存储时统一为大写；不接受 auto——自动切换会让用户
         * 无法确定自己看到的是哪一种界面。若旧配置需要兼容，由
         * {@link ClientConfig#effectiveTerminalMode()} 处理旧字段。
         */
        public Terminal validated() {
            String normalized = mode == null ? OBSERVATORY : mode.trim().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                normalized = OBSERVATORY;
            }
            if (!normalized.equals(OBSERVATORY) && !normalized.equals(OLD)) {
                throw new Errors.Validation("config: terminal.mode 必须为 OBSERVATORY 或 OLD");
            }
            return new Terminal(normalized, shiftEnterToSend);
        }
    }

    /**
     * 兼容旧代码/测试的 17 参数构造器：未提供 terminal 时使用默认值。
     * 生产代码应显式传入 terminal 配置。
     */
    public ClientConfig(String mode, String dataDir, boolean defaultRecording, boolean discoverable,
                        boolean connectable, String listenHost, int listenPort, int discoveryPort,
                        int discoveryTtlSeconds, int connectTimeoutMs, int readTimeoutMs,
                        int pingIntervalMs, int maxFrameBytes, boolean secondaryPasswordProtectHistory,
                        int secondaryPasswordIterations, boolean exitPromptOnBuffered, String logLevel) {
        this(mode, dataDir, defaultRecording, discoverable, connectable, listenHost, listenPort,
                discoveryPort, discoveryTtlSeconds, connectTimeoutMs, readTimeoutMs, pingIntervalMs,
                maxFrameBytes, secondaryPasswordProtectHistory, secondaryPasswordIterations,
                exitPromptOnBuffered, logLevel, Terminal.defaults());
    }

    public static ClientConfig defaults() {
        return new ClientConfig(
                "auto", // 旧字段缺省值；实际模式由 terminal.mode 决定
                AppPaths.defaultDataDir().toString(),
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
                "info",
                Terminal.defaults());
    }

    /** 校验配置值：模式取值、端口范围与互斥、帧大小、二级密码迭代次数、终端配置。 */
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
        Terminal t = (terminal == null ? Terminal.defaults() : terminal).validated();
        return new ClientConfig(mode, dataDir, defaultRecording, discoverable, connectable,
                listenHost, listenPort, discoveryPort, discoveryTtlSeconds, connectTimeoutMs,
                readTimeoutMs, pingIntervalMs, maxFrameBytes, secondaryPasswordProtectHistory,
                secondaryPasswordIterations, exitPromptOnBuffered, logLevel, t);
    }

    /**
     * 实际使用的展示模式（OBSERVATORY 或 OLD），不包含 auto。
     *
     * <p>优先级：
     * <ol>
     *   <li><code>terminal.mode=OLD</code> 明确要求 Old；</li>
     *   <li>否则若旧字段 <code>mode</code> 明确为 old/observatory，则沿用旧字段；</li>
     *   <li>否则为 OBSERVATORY。</li>
     * </ol>
     * 注意：<code>terminal.mode=OBSERVATORY</code> 是默认值，与“用户明确写了
     * OBSERVATORY”无法区分，因此旧字段 old 仍然优先，避免升级导致界面变化。
     * 如果用户希望从旧配置切换到 Observatory，应删除旧的 <code>mode</code>
     * 字段（或使用 <code>--mode observatory</code>）。
     */
    public String effectiveTerminalMode() {
        String t = terminal == null || terminal.mode() == null
                ? Terminal.OBSERVATORY : terminal.mode().trim().toUpperCase(Locale.ROOT);
        if (t.equals(Terminal.OLD)) {
            return Terminal.OLD;
        }
        if (mode != null) {
            String legacy = mode.trim().toLowerCase(Locale.ROOT);
            if (legacy.equals("old")) {
                return Terminal.OLD;
            }
            if (legacy.equals("observatory")) {
                return Terminal.OBSERVATORY;
            }
        }
        return Terminal.OBSERVATORY;
    }

    /** 聊天输入中 Enter/Shift+Enter 的发送策略；缺省 true。 */
    public boolean shiftEnterToSend() {
        return terminal == null || terminal.shiftEnterToSend();
    }

    public Terminal terminalOrDefault() {
        return terminal == null ? Terminal.defaults() : terminal;
    }

    public Path dataDirPath() {
        return AppPaths.expandUserPath(dataDir);
    }
}
