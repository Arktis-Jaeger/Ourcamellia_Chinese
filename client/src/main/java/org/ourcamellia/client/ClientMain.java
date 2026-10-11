package org.ourcamellia.client;

import org.ourcamellia.common.Errors;

import java.io.IOException;

/** 客户端可执行入口。 */
public final class ClientMain {

    public static void main(String[] args) {
        int code = run(args);
        System.exit(code);
    }

    static int run(String[] args) {
        try {
            CliSupport.Options options = CliSupport.parseArgs(args);
            org.ourcamellia.common.config.ClientConfig config = CliSupport.loadConfigOnly(options);
            String secondaryPassword = null;
            if (config.secondaryPasswordProtectHistory()) {
                secondaryPassword = resolveSecondaryPassword();
            }
            CliSupport.Launch launch = CliSupport.launch(options, secondaryPassword);
            String mode = options.mode() != null ? options.mode() : launch.config().mode();
            if (mode.equals("auto")) {
                // 自动模式：有真实控制台则用 Observatory，否则退化为 Old（管道/脚本）。
                mode = System.console() != null ? "observatory" : "old";
            }
            switch (mode) {
                case "old" -> new OldUi(launch.app(), System.in, System.out).run();
                case "observatory" -> {
                    if (!ObservatoryUi.supported()) {
                        System.err.println("当前终端不支持 observatory 模式；请改用 --mode old");
                        return CliSupport.EXIT_USAGE;
                    }
                    new ObservatoryUi(launch.app()).run();
                }
                default -> {
                    System.err.println("未知模式：" + mode);
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