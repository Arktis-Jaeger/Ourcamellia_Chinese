package org.ourcamellia.client;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.config.ClientConfig;

import java.io.IOException;
import java.util.Locale;

/** 客户端可执行入口。 */
public final class ClientMain {

    public static void main(String[] args) {
        int code = run(args);
        System.exit(code);
    }

    static int run(String[] args) {
        try {
            CliSupport.Options options = CliSupport.parseArgs(args);
            ClientConfig config = CliSupport.loadConfigOnly(options);
            String secondaryPassword = null;
            if (config.secondaryPasswordProtectHistory()) {
                secondaryPassword = resolveSecondaryPassword();
            }
            CliSupport.Launch launch = CliSupport.launch(options, secondaryPassword);

            // 模式解析优先级：
            //   1. 命令行 --mode（用户本次的显式选择）
            //   2. 配置 terminal.mode（默认 OBSERVATORY）
            //   3. 旧配置字段 mode（兼容）
            // 不再根据“是否有控制台 / 终端尺寸”自动切换。auto 仅作为旧值的
            // 兼容别名按 OBSERVATORY 处理，并且会打印一行说明。
            String mode = options.mode() != null
                    ? options.mode()
                    : config.effectiveTerminalMode();
            mode = mode.trim().toLowerCase(Locale.ROOT);
            if (mode.equals("auto")) {
                System.err.println("提示：auto 模式已废弃，本次按 OBSERVATORY 运行；"
                        + "请设置 terminal.mode=OBSERVATORY 或 OLD");
                mode = "observatory";
            }
            switch (mode) {
                case "old" -> new OldUi(launch.app(), System.in, System.out).run();
                case "observatory" -> {
                    if (!ObservatoryUi.supported()) {
                        System.err.println("当前终端不支持 Observatory 模式。请改用 --mode old，"
                                + "或在配置中设置 terminal.mode=OLD");
                        return CliSupport.EXIT_USAGE;
                    }
                    new ObservatoryUi(launch.app(), launch.dataDir()).run();
                }
                default -> {
                    System.err.println("未知模式：" + mode + "；允许值为 observatory 或 old");
                    return CliSupport.EXIT_USAGE;
                }
            }
            return CliSupport.EXIT_OK;
        } catch (Errors.Usage e) {
            System.err.println("用法错误：" + e.getMessage());
            return CliSupport.EXIT_USAGE;
        } catch (Errors.OcException e) {
            System.err.println("错误：" + e.getMessage());
            return CliSupport.EXIT_ERROR;
        } catch (IOException e) {
            System.err.println("终端错误：" + e.getMessage());
            return CliSupport.EXIT_ERROR;
        }
    }

    private static String resolveSecondaryPassword() {
        // 交互式：提示输入；非交互式：读环境变量，否则明确拒绝。
        String env = System.getenv("OURCAMELLIA_SECONDARY_PASSWORD");
        if (System.console() == null) {
            if (env != null && !env.isBlank()) {
                return env;
            }
            throw new Errors.Usage("已启用 secondaryPasswordProtectHistory，但标准输入非交互；"
                    + "请设置环境变量 OURCAMELLIA_SECONDARY_PASSWORD，或在配置中关闭该保护");
        }
        System.err.print("二级密码（保护本地历史记录）：");
        char[] pw = System.console().readPassword();
        if (pw == null || pw.length == 0) {
            throw new Errors.Usage("保护已启用时必须提供二级密码");
        }
        return new String(pw);
    }
}
