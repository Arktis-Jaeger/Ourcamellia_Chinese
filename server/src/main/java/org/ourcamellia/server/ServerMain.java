package org.ourcamellia.server;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.config.ConfigLoader;
import org.ourcamellia.common.config.ServerConfig;
import org.ourcamellia.core.server.ServerCore;
import org.ourcamellia.core.server.ServerStateStore;
import org.ourcamellia.core.util.RedactingLog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 服务器可执行入口。 */
public final class ServerMain {

    public static void main(String[] args) {
        int code = run(args);
        System.exit(code);
    }

    static int run(String[] args) {
        try {
            String configPath = null;
            String dataDir = null;
            boolean genkey = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help", "-h" -> {
                        System.out.println(usage());
                        return 0;
                    }
                    case "genkey" -> genkey = true;
                    case "--config" -> configPath = value(args, ++i);
                    case "--data-dir" -> dataDir = value(args, ++i);
                    case "run" -> {
                    }
                    default -> {
                        System.err.println("未知参数：" + args[i]);
                        System.err.println(usage());
                        return 2;
                    }
                }
            }
            Path dir = Path.of(dataDir != null ? dataDir : ServerConfig.defaults().dataDir());
            Path configFile = configPath != null ? Path.of(configPath) : dir.resolve("server.json");
            List<String> warnings = new ArrayList<>();
            ServerConfig config = ConfigLoader.load(configFile, ServerConfig.class,
                    ServerConfig.defaults(), warnings::add).validated();
            warnings.forEach(w -> System.err.println("配置警告：" + w));
            if (dataDir != null) {
                config = withDataDir(config, dataDir);
            }
            // 配置文件中的 dataDir（或命令行覆盖）具有最终权威。
            dir = Path.of(config.dataDir());
            RedactingLog.configure(dir.resolve("logs"), config.logLevel());
            if (genkey) {
                generateKey(config, dir);
                return 0;
            }
            ServerStateStore store = new ServerStateStore(dir);
            ServerCore server = new ServerCore(config, store, new org.ourcamellia.core.server.ServerEvents() {
                @Override
                public void onInfo(String line) {
                    System.out.println("[服务器] " + line);
                }

                @Override
                public void onAudit(String line) {
                    System.out.println("[审计] " + line);
                }
            });
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.close();
                } catch (RuntimeException ignored) {
                }
            }));
            // 当 stdin 为交互式时由控制台驱动关闭；当 stdin 被重定向/关闭时，
            // 服务器以守护模式持续运行，直到收到 SIGTERM。
            ServerConsole console = new ServerConsole(server, System.in, System.out);
            console.run();
            if (console.quitRequested()) {
                server.close();
            }
            return 0;
        } catch (Errors.Usage e) {
            System.err.println("用法错误：" + e.getMessage());
            return 2;
        } catch (Errors.OcException e) {
            System.err.println("错误：" + e.getMessage());
            return 1;
        }
    }

    private static void generateKey(ServerConfig config, Path dir) {
        var generated = org.ourcamellia.common.SelfSignedCerts.generate("ourcamellia-server", 3650);
        Path ks = dir.resolve(config.tlsKeystorePath());
        try {
            java.nio.file.Files.createDirectories(ks.toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            System.err.println("无法创建密钥库目录：" + e.getMessage());
            System.exit(1);
        }
        String password = config.tlsKeystorePassword().isBlank()
                ? org.ourcamellia.common.CryptoUtil.randomHex(16) : config.tlsKeystorePassword();
        org.ourcamellia.common.SelfSignedCerts.saveKeystore(ks, generated.cert(), generated.key(), password);
        System.out.println("已生成 TLS 身份： " + ks);
        System.out.println("指纹 (fingerprint)：" + generated.fingerprint());
        System.out.println("密钥库密码：" + password
                + "（请在配置中设置 tlsKeystorePassword，或保留自动生成的 tls/keystore.password 文件）");
    }

    private static ServerConfig withDataDir(ServerConfig c, String dataDir) {
        return new ServerConfig(c.bindHost(), c.port(), c.serverName(), dataDir,
                c.tlsAutoGenerate(), c.tlsKeystorePath(), c.tlsKeystorePassword(),
                c.requiredRegistrationFields(), c.requireEmail(), c.registrationPolicy(),
                c.pendingTtlMinutes(), c.verifier(), c.notificationRetentionDays(),
                c.encryptedGroupsAllowed(), c.botApi(), c.rateLimits(), c.adminUsernames(),
                c.passwordHashIterations(), c.reviewLogRetention(), c.maxFrameBytes(),
                c.connectTimeoutMs(), c.readTimeoutMs(), c.pingIntervalMs(), c.logLevel());
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            System.err.println("缺少参数值：" + args[i - 1]);
            System.exit(2);
        }
        return args[i];
    }

    static String usage() {
        return """
                ourcamellia-server — Ourcamellia 独立社区服务器
                用法: ourcamellia-server run [--config 路径] [--data-dir 路径]
                       ourcamellia-server genkey [--config 路径] [--data-dir 路径]
                  --config 路径   JSON 配置文件（默认：<data-dir>/server.json）
                  --data-dir 路径 数据目录（默认：./data）
                """;
    }
}