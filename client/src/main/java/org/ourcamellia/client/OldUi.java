package org.ourcamellia.client;

import org.ourcamellia.common.domain.ConversationRef;
import org.ourcamellia.common.domain.Message;
import org.ourcamellia.common.domain.PeerStatus;
import org.ourcamellia.common.domain.SessionMode;
import org.ourcamellia.core.app.AppEvents;
import org.ourcamellia.core.app.ClientApp;
import org.ourcamellia.core.app.ClientAppFactory;
import org.ourcamellia.core.app.CommandSet;
import org.ourcamellia.core.app.ExitPolicy;
import org.ourcamellia.core.cmd.CommandDef;
import org.ourcamellia.core.cmd.CommandDispatcher;
import org.ourcamellia.core.cmd.CommandResult;
import org.ourcamellia.core.cmd.ParsedInput;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Old（旧式）模式：传统逐行 I/O（需求 §2.1）。不使用 ANSI 光标定位、不清屏——
 * 适用于脚本、日志、管道及能力受限的终端。与 Observatory 模式共享完全相同的
 * 应用核心与命令语义。
 *
 * <p>输入约定：消息文本中的 "\n" 表示换行；"\${" 表示字面量 "${"。
 * （命令兼容性说明：这些转义规则不因汉化而改变。）
 */
public final class OldUi {

    private final ClientApp app;
    private final BufferedReader in;
    private final PrintStream out;
    private final boolean interactive;
    private final CommandDispatcher dispatcher = new CommandDispatcher();
    private boolean notifyMode = false;

    public OldUi(ClientApp app, InputStream stdin, PrintStream stdout) {
        this.app = app;
        this.in = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
        this.out = stdout;
        this.interactive = System.console() != null;
        new CommandSet(app, new CommandSet.Prompts() {
            @Override
            public String promptSecret(String label) {
                out.print(label + "：");
                out.flush();
                try {
                    String line = in.readLine();
                    if (line == null) {
                        throw new org.ourcamellia.common.Errors.Usage("没有可用于 " + label + " 的输入");
                    }
                    return line;
                } catch (IOException e) {
                    throw new org.ourcamellia.common.Errors.Usage("无法读取输入");
                }
            }

            @Override
            public String promptLine(String label) {
                return promptSecret(label);
            }
        }).install(dispatcher);
    }

    public void run() {
        ClientAppFactory.attachEvents(app, new OldEvents());
        out.println("Ourcamellia 客户端（Old 文本模式）。输入 ${help} 查看命令，${quit} 退出。");
        while (!app.exitRequested()) {
            String line = readLine();
            if (line == null) {
                // EOF：按退出策略处理（可能提示缓冲内容丢失，或直接退出）。
                if (handleExit()) {
                    break;
                }
                continue;
            }
            handleLine(line);
        }
        app.close();
    }

    private void handleLine(String line) {
        // Old 模式会去除首尾空白（已记录的约定；带引号的字符串保留内部空格）。
        line = line.strip();
        ParsedInput parsed = org.ourcamellia.core.cmd.CommandParser.parse(line, true);
        if (parsed instanceof ParsedInput.Command cmd) {
            // "${info}" / "${notify}" 打开通知列表：下一行输入可使用 "<n> pass|reject|over" 快捷操作。
            notifyMode = (cmd.name().equals("info") || cmd.name().equals("notify"))
                    && cmd.args().isEmpty();
            CommandResult result = dispatcher.dispatch(cmd, context());
            printResult(result);
            if (result.exitRequested()) {
                if (handleExit()) {
                    app.requestExit();
                }
            }
            return;
        }
        if (parsed instanceof ParsedInput.Malformed mal) {
            notifyMode = false;
            out.println("错误：命令格式有误 — " + mal.reason() + "（未发送或执行任何内容）");
            return;
        }
        if (parsed instanceof ParsedInput.Blank) {
            return;
        }
        ParsedInput.Message message = (ParsedInput.Message) parsed;
        // 通知快捷操作："<n> pass|reject|over"（仅当通知列表处于打开状态）。
        if (notifyMode && message.text().matches("^\\d+\\s+(pass|reject|over)$")) {
            String[] parts = message.text().split("\\s+");
            int index = Integer.parseInt(parts[0]);
            out.println(app.notifyAction(index, parts[1]));
            return;
        }
        notifyMode = false;
        try {
            app.say(message.text());
        } catch (org.ourcamellia.common.Errors.OcException e) {
            out.println("错误：" + e.getMessage());
        }
    }

    private void printResult(CommandResult result) {
        for (String line : result.lines()) {
            if (!line.isBlank()) {
                out.println(line);
            }
        }
    }

    private CommandDef.Context context() {
        if (app.mode() == SessionMode.NONE) {
            return CommandDef.Context.L1;
        }
        return app.currentConversation() == null ? CommandDef.Context.L2 : CommandDef.Context.L3;
    }

    private String readLine() {
        out.print(prompt());
        out.flush();
        try {
            return in.readLine();
        } catch (IOException e) {
            out.println("错误：输入失败：" + e.getMessage());
            return null;
        }
    }

    private String prompt() {
        return switch (context()) {
            case L1 -> "(菜单)> ";
            case L2 -> "(列表)> ";
            case L3 -> "(" + app.currentConversation().title() + ")> ";
            case ANY -> "> ";
        };
    }

    /** 退出确认策略（P3）：仅在存在缓冲内容时提示；非交互输入绝不提示。 */
    private boolean handleExit() {
        String key = currentKeyOrNull();
        boolean buffered = key != null && app.hasBuffered(key);
        ExitPolicy.Decision decision = ExitPolicy.decide(buffered, interactive);
        if (!decision.promptNeeded()) {
            if (decision.message() != null) {
                out.println(decision.message());
            }
            return true;
        }
        while (true) {
            out.print("当前会话存在未持久化的缓冲消息。退出前是否持久化？(y/n/c)：");
            out.flush();
            try {
                String answer = in.readLine();
                if (answer == null) {
                    return true;
                }
                switch (answer.trim().toLowerCase()) {
                    case "y" -> {
                        int n = app.persist(key);
                        out.println("已持久化 " + n + " 条消息");
                        return true;
                    }
                    case "n" -> {
                        out.println("已丢弃缓冲消息");
                        return true;
                    }
                    case "c" -> {
                        out.println("已取消退出");
                        return false;
                    }
                    default -> out.println("请输入 y、n 或 c");
                }
            } catch (IOException e) {
                return true;
            }
        }
    }

    private String currentKeyOrNull() {
        ConversationRef cur = app.currentConversation();
        if (cur == null) {
            return null;
        }
        if (app.mode() == SessionMode.LOCAL && app.local() != null) {
            return app.local().convKey(cur.id());
        }
        if (app.mode() == SessionMode.SERVER && app.server() != null) {
            return app.server().convKey(cur);
        }
        return null;
    }

    private final class OldEvents implements AppEvents {
        @Override
        public void onMessage(ConversationRef conversation, Message message) {
            out.println(message.sender() + "> " + org.ourcamellia.common.Text.renderable(message.text()));
        }

        @Override
        public void onStatusChanged() {
        }

        @Override
        public void onNotificationsChanged() {
        }

        @Override
        public void onInfo(String text) {
            out.println("· " + text);
        }

        @Override
        public void onPeerStatus(String peerName, PeerStatus status) {
            out.println("· " + peerName + " 现在的状态：" + status.name().toLowerCase());
        }

        @Override
        public void onShutdown() {
        }
    }
}