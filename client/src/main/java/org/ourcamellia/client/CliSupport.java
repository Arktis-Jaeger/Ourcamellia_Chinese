package org.ourcamellia.client;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.config.ClientConfig;
import org.ourcamellia.common.config.ConfigLoader;
import org.ourcamellia.core.app.ClientApp;
import org.ourcamellia.core.app.ClientAppFactory;
import org.ourcamellia.core.persist.ClientStateStore;
import org.ourcamellia.core.persist.LocalHistoryStore;
import org.ourcamellia.core.util.RedactingLog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 两种展示模式共享的参数解析与应用装配。 */
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
                用法: ourcamellia-client [--config 路径] [--data-dir 路径] [--mode auto|old|observatory]
                  --config 路径    JSON 配置文件（默认：<data-dir>/config.json）
                  --data-dir 路径  数据目录（默认：~/.ourcamellia）
                  --mode           展示模式；auto 在真实终端上自动选择 observatory
                """;
    }

    /** 仅加载生效配置（不创建任何存储）。 */
    public static ClientConfig loadConfigOnly(Options options) {
        Path dataDir = Path.of(options.dataDir() != null
                ? options.dataDir() : ClientConfig.defaults().dataDir());
        Path configFile = options.configPath() != null
                ? Path.of(options.configPath()) : dataDir.resolve("config.json");
        List<String> warnings = new ArrayList<>();
        ClientConfig config = ConfigLoader.load(configFile, ClientConfig.class,
                ClientConfig.defaults(), warnings::add).validated();
        warnings.forEach(w -> System.err.println("配置警告：" + w));
        if (options.dataDir() != null) {
            config = withDataDir(config, options.dataDir());
        }
        if (options.mode() != null) {
            config = withMode(config, options.mode());
        }
        return config;
    }

    /** 加载配置 + 存储并构建 ClientApp。 */
    public static Launch launch(Options options, String secondaryPassword) {
        ClientConfig config = loadConfigOnly(options);
        Path dataDir = Path.of(config.dataDir());
        try {
            java.nio.file.Files.createDirectories(dataDir);
        } catch (java.io.IOException e) {
            System.err.println("无法创建数据目录 " + dataDir + "：" + e.getMessage());
            System.exit(EXIT_ERROR);
        }
        RedactingLog.configure(dataDir.resolve("logs"), config.logLevel());
        ClientStateStore store = new ClientStateStore(dataDir);
        LocalHistoryStore history = new LocalHistoryStore(dataDir.resolve("history"),
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
                c.secondaryPasswordIterations(), c.exitPromptOnBuffered(), c.logLevel());
    }

    private static ClientConfig withMode(ClientConfig c, String mode) {
        if (!mode.equals("auto") && !mode.equals("old") && !mode.equals("observatory")) {
            throw new Errors.Usage("--mode 必须是 auto、old 或 observatory");
        }
        return new ClientConfig(mode, c.dataDir(), c.defaultRecording(), c.discoverable(),
                c.connectable(), c.listenHost(), c.listenPort(), c.discoveryPort(),
                c.discoveryTtlSeconds(), c.connectTimeoutMs(), c.readTimeoutMs(),
                c.pingIntervalMs(), c.maxFrameBytes(), c.secondaryPasswordProtectHistory(),
                c.secondaryPasswordIterations(), c.exitPromptOnBuffered(), c.logLevel());
    }
}
