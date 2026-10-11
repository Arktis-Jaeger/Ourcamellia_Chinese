package org.ourcamellia.server;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Text;
import org.ourcamellia.common.config.ServerConfig;
import org.ourcamellia.core.cmd.Arg;
import org.ourcamellia.core.cmd.CommandDispatcher;
import org.ourcamellia.core.cmd.CommandResult;
import org.ourcamellia.core.cmd.CommandDef;
import org.ourcamellia.core.cmd.ParsedInput;
import org.ourcamellia.core.server.ServerCore;
import org.ourcamellia.core.server.ServerStateStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;

import static org.ourcamellia.core.cmd.CommandDef.Context.*;
import static org.ourcamellia.core.cmd.CommandDef.ParamType.*;

/**
 * 运营者控制台：仅本地、面向服务器管理的逐行接口（需求 §9）。它在服务器主机上
 * 通过 stdin 运行，绝不暴露到网络；主机访问即运营者认证边界（已记录）。其命令使用
 * 与客户端相同的形式文法。
 *
 * <p>汉化范围：控制台提示、命令说明与结果/错误消息。命令名（admin/announce/
 * registration/groups/review/mod-delete/bot-create/bot-revoke/policy/stats/help/quit）
 * 与参数取值（"add"/"remove"、"approve"/"reject"）属于可执行语法，保持不变。
 */
public final class ServerConsole {

    private final ServerCore server;
    private final BufferedReader in;
    private final PrintStream out;
    private final CommandDispatcher dispatcher = new CommandDispatcher();
    private volatile boolean quit = false;

    public ServerConsole(ServerCore server, InputStream stdin, PrintStream stdout) {
        this.server = server;
        this.in = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
        this.out = stdout;
        install();
    }

    private void install() {
        dispatcher.register(def("admin", "添加或移除运营者", "${admin(\"add\"|\"remove\", @name)}",
                        List.of(p("action", CHOICE, false, "add | remove"), p("name", STRING, false, "@name")), ANY),
                (args, ctx) -> {
                    String action = args.get(0).asString();
                    String name = Text.requireServerName(args.get(1).asString());
                    if (action.equals("add")) {
                        server.promote(name);
                    } else if (action.equals("remove")) {
                        server.demote(name);
                    } else {
                        throw new Errors.Usage("操作必须为 add 或 remove");
                    }
                    return CommandResult.ok("已完成");
                });
        dispatcher.register(def("announce", "发布到公告频道（所有账户）", "${announce(\"text\")}",
                        List.of(p("text", STRING, false, "公告文本")), ANY),
                (args, ctx) -> {
                    server.announce(args.get(0).asString());
                    return CommandResult.ok("公告已发布");
                });
        dispatcher.register(def("registration", "批准或拒绝待处理的注册申请",
                        "${registration(\"approve\"|\"reject\", ticketId)}",
                        List.of(p("action", CHOICE, false, "approve | reject"), p("ticket", STRING, false, "票据 id")), ANY),
                (args, ctx) -> {
                    String action = args.get(0).asString();
                    if (action.equals("approve")) {
                        server.approveRegistration(args.get(1).asString());
                    } else if (action.equals("reject")) {
                        server.rejectRegistration(args.get(1).asString());
                    } else {
                        throw new Errors.Usage("操作必须为 approve 或 reject");
                    }
                    return CommandResult.ok("已完成");
                });
        dispatcher.register(def("groups", "列出群组（含 id 与隐私模式）", "${groups}", List.of(), ANY),
                (args, ctx) -> {
                    List<String> lines = new java.util.ArrayList<>();
                    lines.add("群组（id · 名称 · 隐私 · 群主）：");
                    for (var g : server.store().state().groups.values()) {
                        lines.add("  " + g.id + " · " + g.name + " · " + g.privacy + " · 群主 " + g.owner);
                    }
                    return CommandResult.ok(lines);
                });
        dispatcher.register(def("review", "查看某运营者可见群组的审查日志",
                        "${review(groupId [, N])}",
                        List.of(p("groupId", STRING, false, "群组 id"), p("limit", NUMBER, true, "条数（默认 20）")), ANY),
                (args, ctx) -> {
                    int limit = args.size() >= 2 ? ((Arg.Num) args.get(1)).asInt() : 20;
                    List<String> lines = new java.util.ArrayList<>();
                    for (var e : server.review(args.get(0).asString(), limit)) {
                        lines.add(e.ts() + " " + e.sender() + "> " + e.text());
                    }
                    if (lines.isEmpty()) {
                        lines.add("（审查日志中暂无消息）");
                    }
                    return CommandResult.ok(lines);
                });
        dispatcher.register(def("mod-delete", "在所有副本中删除某条运营者可见群组消息",
                        "${mod-delete(groupId, msgId)}",
                        List.of(p("groupId", STRING, false, "群组 id"), p("msgId", STRING, false, "消息 id")), ANY),
                (args, ctx) -> {
                    server.modDelete(args.get(0).asString(), args.get(1).asString());
                    return CommandResult.ok("审核通知已转发");
                });
        dispatcher.register(def("bot-create", "创建 BOT 账户（令牌仅显示一次）",
                        "${bot-create(@name)}", List.of(p("name", STRING, false, "@name")), ANY),
                (args, ctx) -> {
                    String name = Text.requireServerName(args.get(0).asString());
                    var cred = server.createBot("operator", name);
                    return CommandResult.ok("BOT " + name + " 已创建 —— 请妥善保存以下凭证（仅显示一次）：",
                            "  令牌 (token): " + cred.token(),
                            "  X25519 私钥（base64）：" + cred.x25519PrivateKeyBase64());
                });
        dispatcher.register(def("bot-revoke", "吊销某 BOT 账户的全部令牌",
                        "${bot-revoke(@name)}", List.of(p("name", STRING, false, "@name")), ANY),
                (args, ctx) -> {
                    server.revokeBotTokens("operator", Text.requireServerName(args.get(0).asString()));
                    return CommandResult.ok("令牌已吊销");
                });
        dispatcher.register(def("policy", "显示服务器策略摘要", "${policy}", List.of(), ANY),
                (args, ctx) -> CommandResult.ok(server.policySummary().split("\n")));
        dispatcher.register(def("stats", "显示连接与账户统计", "${stats}", List.of(), ANY),
                (args, ctx) -> CommandResult.ok(server.stats()));
        dispatcher.register(def("help", "列出控制台命令", "${help}", List.of(), ANY),
                (args, ctx) -> CommandResult.ok(dispatcher.helpText(ANY)));
        dispatcher.register(def("quit", "优雅关闭", "${quit}", List.of(), ANY),
                (args, ctx) -> {
                    quit = true;
                    return CommandResult.exit("即将关闭");
                });
    }

    public void run() {
        out.println("Ourcamellia 服务器控制台。输入 ${help} 查看命令，${quit} 关闭。");
        while (!quit) {
            out.print("服务器> ");
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                out.println("控制台输入失败：" + e.getMessage());
                break;
            }
            if (line == null) {
                break; // EOF：继续对外服务，控制台仅停止读取
            }
            ParsedInput parsed = org.ourcamellia.core.cmd.CommandParser.parse(line, false);
            if (parsed instanceof ParsedInput.Command cmd) {
                CommandResult result = dispatcher.dispatch(cmd, ANY);
                for (String l : result.lines()) {
                    if (!l.isBlank()) {
                        out.println(l);
                    }
                }
            } else if (parsed instanceof ParsedInput.Malformed mal) {
                out.println("错误：命令格式有误 — " + mal.reason());
            } else if (parsed instanceof ParsedInput.Message m) {
                out.println("错误：无法识别的输入 —— 输入 ${help} 查看控制台命令");
            }
        }
    }

    public boolean quitRequested() {
        return quit;
    }

    private static CommandDef def(String name, String summary, String usage,
                                  List<CommandDef.Param> params, CommandDef.Context... contexts) {
        EnumSet<CommandDef.Context> set = EnumSet.noneOf(CommandDef.Context.class);
        set.addAll(List.of(contexts));
        return new CommandDef(name, summary, usage, params, set);
    }

    private static CommandDef.Param p(String name, CommandDef.ParamType type, boolean optional, String desc) {
        return new CommandDef.Param(name, type, optional, desc);
    }
}