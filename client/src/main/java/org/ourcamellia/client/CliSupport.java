package org.ourcamellia.client;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.config.AppPaths;
import org.ourcamellia.common.config.ClientConfig;
import org.ourcamellia.common.config.ConfigLoader;
import org.ourcamellia.core.app.ClientApp;
import org.ourcamellia.core.app.ClientAppFactory;
import org.ourcamellia.core.persist.ChatPaths;
import org.ourcamellia.core.persist.ClientStateStore;
import org.ourcamellia.core.persist.LocalHistoryStore;
import org.ourcamellia.core.util.RedactingLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 两种展示模式共享的参数解析与应用装配。
 *
 * <p>启动顺序被刻意拆成两步：
 * <ol>
 *   <li>{@link #loadConfigOnly(Options)} 只读取配置（不创建数据目录、不写任何文件），
 *       这样二级密码、展示模式等问题可以在落盘前被发现；</li>
 *   <li>{@link #launch(Options, String)} 解析便携式数据目录、检查可写性、创建
 *       聊天记录存储，再构造 {@link ClientApp}。</li>
 * </ol>
 */
public final class CliSupport {

    public static final int EXIT_OK = 0;
    public static final int EXIT_ERROR = 1;
    public static final int EXIT_USAGE = 2;

    public record Options(String configPath, String dataDir, String mode) {}

    private CliSupport() {}

    public static Options parseArgs(String[] args) {
        String configPath = null;
        String dataDir = null;
        String mode = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help", "-h" -> {
                    System.out.println(usage());
                    System.exit(EXIT_OK);
                }
                case "--config" -> configPath = requireValue(args, ++i);
                case "--data-dir" -> dataDir = requireValue(args, ++i);
                case "--mode" -> mode = requireValue(args, ++i);
                default -> {
                    System.err.println("未知参数：" + args[i]);
                    System.err.println(usage());
                    System.exit(EXIT_USAGE);
                }
            }
        }
        return new Options(configPath, dataDir, mode);
    }

    private static String requireValue(String[] args, int i) {
        if (i >= args.length) {
            System.err.println("缺少参数值：" + args[i - 1]);
            System.err.println(usage());
            System.exit(EXIT_USAGE);
        }
        return args[i];
    }

    public static String usage() {
        return """
                ourcamellia-client — Ourcamellia 命令行客户端
                用法: ourcamellia-client [--config 路径] [--data-dir 路径] [--mode observatory|old]
                  --config 路径    JSON 配置文件（默认：<data-dir>/config.json）
                  --data-dir 路径  数据目录（默认：<程序目录>/data）
                  --mode           展示模式；不指定时使用配置 terminal.mode（默认 OBSERVATORY）
                                   兼容旧值 auto（等同于 OBSERVATORY，不再自动切换）
                """;
    }

    /** 仅加载生效配置（不创建任何存储）。 */
    public static ClientConfig loadConfigOnly(Options options) {
        ClientConfig defaults = ClientConfig.defaults();
        Path dataDir = options.dataDir() != null
                ? AppPaths.expandUserPath(options.dataDir())
                : defaults.dataDirPath();
        Path configFile = options.configPath() != null
                ? Path.of(options.configPath()).toAbsolutePath().normalize()
                : dataDir.resolve("config.json");
        List<String> warnings = new ArrayList<>();
        ClientConfig config = ConfigLoader.load(configFile, ClientConfig.class,
                defaults, warnings::add).validated();
        warnings.forEach(w -> System.err.println("配置警告：" + w));
        if (options.dataDir() != null) {
            config = withDataDir(config, options.dataDir());
        }
        if (options.mode() != null) {
            config = withMode(config, options.mode());
        }
        return config;
    }

    /**
     * 加载配置 + 存储并构建 ClientApp。
     *
     * <p>数据目录规则：
     * <ul>
     *   <li>默认 <code>&lt;程序目录&gt;/data</code>（打包时是发行目录，开发时是项目根）；</li>
     *   <li>可用 <code>--data-dir</code> 或配置 <code>dataDir</code> 覆盖；</li>
     *   <li>拒绝把运行数据写入 target/ 编译输出目录或 Study/ 学习材料目录；</li>
     *   <li>目录无法创建或不可写时给出明确错误并退出，绝不静默改写到用户主目录。</li>
     * </ul>
     */
    public static Launch launch(Options options, String secondaryPassword) {
        ClientConfig config = loadConfigOnly(options);
        Path dataDir;
        try {
            dataDir = ChatPaths.resolveDataDir(config.dataDirPath());
        } catch (Errors.OcException e) {
            throw new Errors.Usage(e.getMessage());
        }
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new Errors.Persistence("无法创建数据目录 " + dataDir
                    + "：" + e.getMessage() + "。请把程序放到可写目录，或用 --data-dir 指定可写目录");
        }
        if (!Files.isDirectory(dataDir) || !Files.isWritable(dataDir)) {
            throw new Errors.Persistence("数据目录不可写：" + dataDir
                    + "。请检查目录权限，或把程序放到可写目录");
        }
        // 聊天记录子目录也必须在启动时就暴露权限问题，而不是等到第一条消息。
        try {
            Files.createDirectories(dataDir.resolve("chats").resolve("local"));
            Files.createDirectories(dataDir.resolve("chats").resolve("servers"));
        } catch (IOException e) {
            throw new Errors.Persistence("无法创建聊天记录目录 " + dataDir.resolve("chats")
                    + "：" + e.getMessage() + "。请检查目录权限");
        }
        RedactingLog.configure(dataDir.resolve("logs"), config.logLevel());
        ClientStateStore store = new ClientStateStore(dataDir);
        LocalHistoryStore history = LocalHistoryStore.portable(dataDir,
                config.defaultRecording(), secondaryPassword, config.secondaryPasswordIterations());
        ClientApp app = ClientAppFactory.create(config, store, history, dataDir);
        return new Launch(config, dataDir, app);
    }

    public record Launch(ClientConfig config, Path dataDir, ClientApp app) {}

    private static ClientConfig withDataDir(ClientConfig c, String dataDir) {
        return new ClientConfig(c.mode(), dataDir, c.defaultRecording(), c.discoverable(),
                c.connectable(), c.listenHost(), c.listenPort(), c.discoveryPort(),
                c.discoveryTtlSeconds(), c.connectTimeoutMs(), c.readTimeoutMs(),
                c.pingIntervalMs(), c.maxFrameBytes(), c.secondaryPasswordProtectHistory(),
                c.secondaryPasswordIterations(), c.exitPromptOnBuffered(), c.logLevel(),
                c.terminalOrDefault());
    }

    private static ClientConfig withMode(ClientConfig c, String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("auto") && !normalized.equals("old")
                && !normalized.equals("observatory")) {
            throw new Errors.Usage("--mode 必须是 observatory、old 或 auto");
        }
        return new ClientConfig(normalized, c.dataDir(), c.defaultRecording(), c.discoverable(),
                c.connectable(), c.listenHost(), c.listenPort(), c.discoveryPort(),
                c.discoveryTtlSeconds(), c.connectTimeoutMs(), c.readTimeoutMs(),
                c.pingIntervalMs(), c.maxFrameBytes(), c.secondaryPasswordProtectHistory(),
                c.secondaryPasswordIterations(), c.exitPromptOnBuffered(), c.logLevel(),
                c.terminalOrDefault());
    }
}
