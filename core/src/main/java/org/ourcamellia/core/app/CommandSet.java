package org.ourcamellia.core.app;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Text;
import org.ourcamellia.common.domain.ConversationRef;
import org.ourcamellia.common.domain.FriendState;
import org.ourcamellia.common.domain.Notification;
import org.ourcamellia.common.domain.NotificationKind;
import org.ourcamellia.common.domain.SessionMode;
import org.ourcamellia.core.cmd.Arg;
import org.ourcamellia.core.cmd.CommandDef;
import org.ourcamellia.core.cmd.CommandDispatcher;
import org.ourcamellia.core.cmd.CommandResult;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.ourcamellia.core.cmd.CommandDef.Context.*;
import static org.ourcamellia.core.cmd.CommandDef.ParamType.*;

/**
 * 标准命令集（docs/COMMAND_GRAMMAR.md §3），装配到应用层。两种 CLI 模式使用
 * 完全相同的命令集——业务规则位于 ClientApp 而非此处。密码等输入通过注入的
 * {@link Prompts} 钩子由各 UI 以自己的方式安全获取。
 *
 * <p>汉化范围：所有面向用户的说明（命令 summary/usage/参数描述、结果与错误消息）。
 * 命令名（${help} 等）与命令参数取值（"on"/"off"、"local"/"server"、
 * "pass"/"reject"/"over" 等）属于可执行语法，一律保持不变。
 */
public final class CommandSet {

    /** 由 UI 提供的安全输入钩子。 */
    public interface Prompts {
        String promptSecret(String label);

        String promptLine(String label);
    }

    private final ClientApp app;
    private final Prompts prompts;
    private CommandDispatcher dispatcher;

    public CommandSet(ClientApp app, Prompts prompts) {
        this.app = app;
        this.prompts = prompts;
    }

    public void install(CommandDispatcher d) {
        this.dispatcher = d;
        d.register(def("help", "列出命令或说明某条命令", "${help [name]}",
                        List.of(param("name", ATOM, true, "命令名")), ANY),
                this::help);
        d.register(def("status", "显示当前状态（连接、对端、录制等）", "${status}",
                        List.of(), ANY),
                (args, ctx) -> CommandResult.ok(statusLines()));
        d.register(def("info", "列出活动通知", "${info}", List.of(), ANY),
                (args, ctx) -> CommandResult.ok(notificationLines()));
        d.register(def("notify", "打开 (N) 或处理一条通知：${notify(N, \"pass|reject|over\")}",
                        "${notify [N [action]]}",
                        List.of(param("index", NUMBER, true, "通知编号"),
                                param("action", CHOICE, true, "pass | reject | over")), ANY),
                this::notify);
        d.register(def("mode", "选择模式：${mode(\"local\", name)} 或 ${mode(\"server\", \"host:port\")}",
                        "${mode(local|server, value)}",
                        List.of(param("which", CHOICE, false, "local | server"),
                                param("value", STRING, false, "本地名称或服务器地址")), L1),
                this::mode);
        d.register(def("server-alias", "为当前服务器设置私有本地备注",
                        "${server-alias(\"note\")}",
                        List.of(param("note", STRING, false, "自由文本备注")), ANY),
                this::serverAlias);
        d.register(def("server-info", "服务器官方名称、策略与你的备注", "${server-info}",
                        List.of(), ANY),
                (args, ctx) -> CommandResult.ok(serverInfoLines()));
        d.register(def("register", "注册账户（省略密码时提示输入）",
                        "${register(@name [, \"password\", @email])}",
                        List.of(param("name", STRING, false, "@name"),
                                param("password", STRING, true, "密码（省略时提示输入）"),
                                param("email", STRING, true, "@Email")), ANY),
                this::register);
        d.register(def("login", "登录（省略密码时提示输入）",
                        "${login(@name [, \"password\"])}",
                        List.of(param("name", STRING, false, "@name"),
                                param("password", STRING, true, "密码（省略时提示输入）")), ANY),
                this::login);
        d.register(def("logout", "断开与服务器的连接", "${logout}", List.of(), ANY),
                (args, ctx) -> {
                    if (app.server() == null) {
                        throw new Errors.Usage("未连接到服务器");
                    }
                    app.server().logout();
                    return CommandResult.ok("已登出");
                });
        d.register(def("open", "打开一个会话", "${open(name)}",
                        List.of(param("name", ATOM, false, "对端或群组名称")), L2),
                this::open);
        d.register(def("close", "关闭当前会话", "${close}", List.of(), L3),
                (args, ctx) -> {
                    app.closeConversation();
                    return CommandResult.ok("会话已关闭");
                });
        d.register(def("friends", "列出好友及其观察者相对状态", "${friends}",
                        List.of(), L2),
                (args, ctx) -> CommandResult.ok(friendLines()));
        d.register(def("friend-request", "发送好友请求", "${friend-request(name)}",
                        List.of(param("name", ATOM, false, "对端或 @用户")), L2),
                this::friendRequest);
        d.register(def("discoverable", "切换被发现开关（本地模式）",
                        "${discoverable(\"on\"|\"off\")}",
                        List.of(param("value", CHOICE, false, "on | off")), L2),
                (args, ctx) -> {
                    boolean on = requireOnOff(args.get(0));
                    app.setDiscoverable(on);
                    return CommandResult.ok("可被发现：" + (on ? "开启" : "关闭"));
                });
        d.register(def("connectable", "切换入站连接接受开关（本地模式）",
                        "${connectable(\"on\"|\"off\")}",
                        List.of(param("value", CHOICE, false, "on | off")), L2),
                (args, ctx) -> {
                    boolean on = requireOnOff(args.get(0));
                    app.setConnectable(on);
                    return CommandResult.ok("可连接：" + (on ? "开启" : "关闭"));
                });
        d.register(def("connect", "按地址发起连接尝试（本地模式）",
                        "${connect(\"host:port\")}",
                        List.of(param("address", STRING, false, "host:port")), L2),
                this::connect);
        d.register(def("discover", "立即执行一次发现查询（本地模式）", "${discover}",
                        List.of(), L2),
                (args, ctx) -> {
                    if (app.local() == null) {
                        throw new Errors.Usage("本地模式未激活");
                    }
                    app.local().runDiscovery();
                    return CommandResult.ok("发现查询已发送" + (app.local().discoveryAvailable()
                            ? "" : "（当前网络不可用发现功能）"));
                });
        d.register(def("create-group", "创建运营者可见群组（成员可见内容）",
                        "${create-group(\"name\", member...)}",
                        List.of(param("name", STRING, false, "群组名称"),
                                param("members", STRING, true, "成员名称（可重复）")), L2),
                (args, ctx) -> createGroup(args, false));
        d.register(def("create-encrypted-group", "创建加密群组（运营者无法读取内容）",
                        "${create-encrypted-group(\"name\", member...)}",
                        List.of(param("name", STRING, false, "群组名称"),
                                param("members", STRING, true, "成员名称（可重复）")), L2),
                (args, ctx) -> createGroup(args, true));
        d.register(def("group-invite", "邀请成员（仅群主）", "${group-invite(name)}",
                        List.of(param("member", ATOM, false, "成员名称")), L3),
                (args, ctx) -> {
                    String groupId = currentGroupId();
                    app.server().inviteMember(groupId, args.get(0).asString());
                    return CommandResult.ok("已向 " + args.get(0).asString() + " 发送邀请");
                });
        d.register(def("group-remove", "移除成员（仅群主）；加密群组需轮换密钥",
                        "${group-remove(name)}",
                        List.of(param("member", ATOM, false, "成员名称")), L3),
                (args, ctx) -> {
                    String groupId = currentGroupId();
                    app.server().removeMember(groupId, args.get(0).asString());
                    return CommandResult.ok("成员已移除 —— 加密群组请立即轮换密钥：${group-send-keys}");
                });
        d.register(def("group-leave", "退出当前群组", "${group-leave}", List.of(), L3),
                (args, ctx) -> {
                    app.server().leaveGroup(currentGroupId());
                    app.closeConversation();
                    return CommandResult.ok("已退出群组");
                });
        d.register(def("group-transfer", "转让群主（仅群主）", "${group-transfer(name)}",
                        List.of(param("member", ATOM, false, "新群主")), L3),
                (args, ctx) -> {
                    app.server().transferGroup(currentGroupId(), args.get(0).asString());
                    return CommandResult.ok("群主已转让给 " + args.get(0).asString());
                });
        d.register(def("group-members", "列出群组成员与隐私模式", "${group-members}",
                        List.of(), L3),
                (args, ctx) -> CommandResult.ok(groupMemberLines()));
        d.register(def("group-send-keys", "群主：分发/轮换加密群组密钥",
                        "${group-send-keys}", List.of(), L3),
                (args, ctx) -> {
                    app.server().rotateGroupKeys(currentGroupId());
                    return CommandResult.ok("新群组密钥已分发");
                });
        d.register(def("record", "显示或设置自动录制：${record(\"on\"|\"off\")}",
                        "${record [on|off]}",
                        List.of(param("value", CHOICE, true, "on | off")), ANY),
                this::record);
        d.register(def("persist", "持久化当前会话中未持久化的缓冲消息",
                        "${persist}", List.of(), ANY),
                (args, ctx) -> {
                    String key = currentKeyOrError();
                    int n = app.persist(key);
                    return CommandResult.ok(n == 0
                            ? "没有需要持久化的缓冲内容" : "已持久化 " + n + " 条消息");
                });
        d.register(def("history-delete", "删除已保存的历史记录：${history-delete(\"current\"|\"peer\"|\"all\" [, name])}",
                        "${history-delete(scope [, name])}",
                        List.of(param("scope", CHOICE, false, "current | peer | all"),
                                param("name", STRING, true, "对端名称（scope=peer 时需要）")), ANY),
                this::historyDelete);
        d.register(def("say", "显式发送文本（载荷按字面处理，不再解析）",
                        "${say(text)}",
                        List.of(param("text", STRING, false, "载荷")), ANY),
                this::say);
        d.register(def("quit", "退出程序（按退出确认策略处理）", "${quit}",
                        List.of(), ANY),
                (args, ctx) -> CommandResult.exit("再见"));
        d.register(def("exit", "退出程序（quit 的别名）", "${exit}", List.of(), ANY),
                (args, ctx) -> CommandResult.exit("再见"));
    }

    // ---------------- 命令处理 ----------------

    private CommandResult help(List<Arg> args, CommandDef.Context ctx) {
        if (args.isEmpty()) {
            return CommandResult.ok(dispatcher.helpText(ctx));
        }
        return CommandResult.ok(dispatcher.helpOne(args.get(0).asString()));
    }

    private CommandResult notify(List<Arg> args, CommandDef.Context ctx) {
        if (args.isEmpty()) {
            return CommandResult.ok(notificationLines());
        }
        int index = ((Arg.Num) args.get(0)).asInt();
        if (args.size() == 1) {
            List<Notification> list = app.notifications();
            if (index < 1 || index > list.size()) {
                throw new Errors.Validation("通知 " + index + " 不存在");
            }
            Notification n = list.get(index - 1);
            return CommandResult.ok("通知 " + index + "：[" + notificationKindLabel(n.kind()) + "] 来自 " + n.from()
                    + " — " + n.text(), "操作：${notify(" + index + ", \"pass\")}, "
                    + "${notify(" + index + ", \"reject\")}, ${notify(" + index + ", \"over\")}");
        }
        String action = args.get(1).asString();
        return CommandResult.ok(app.notifyAction(index, action));
    }

    private CommandResult mode(List<Arg> args, CommandDef.Context ctx) {
        String which = args.get(0).asString();
        String value = args.get(1).asString();
        if (which.equals("local")) {
            app.enterLocal(value);
            return CommandResult.ok("已进入本地模式：你为 '" + value + "'");
        }
        if (which.equals("server")) {
            app.connectServer(value);
            return CommandResult.ok("正在连接 " + value + " —— 等待服务器欢迎信息");
        }
        throw new Errors.Usage("模式必须为 local 或 server");
    }

    private CommandResult serverAlias(List<Arg> args, CommandDef.Context ctx) {
        if (app.mode() != SessionMode.SERVER || app.server() == null) {
            throw new Errors.Usage("未连接到服务器");
        }
        String note = args.get(0).asString();
        app.store().putAlias(app.server().serverAddress(), note);
        return CommandResult.ok("已为 " + app.server().serverAddress() + " 保存备注");
    }

    private String[] serverInfoLines() {
        if (app.mode() != SessionMode.SERVER || app.server() == null) {
            return new String[]{"未连接到服务器"};
        }
        var s = app.server();
        String alias = app.store().alias(s.serverAddress());
        return new String[]{
                "服务器名称 : " + (s.serverName() == null ? "（尚未知）" : s.serverName()),
                "地址        : " + s.serverAddress(),
                "你的备注    : " + (alias == null ? "（无 —— 可用 ${server-alias(...)} 设置）" : alias),
                "登录状态    : " + (s.loggedInUser() == null ? "未登录 —— 使用 ${login(...)}" : s.loggedInUser()),
        };
    }

    private CommandResult register(List<Arg> args, CommandDef.Context ctx) {
        if (app.mode() != SessionMode.SERVER || app.server() == null) {
            throw new Errors.Usage("未连接到服务器");
        }
        String name = args.get(0).asString();
        Text.requireServerName(name);
        String password = args.size() >= 2 ? args.get(1).asString() : prompts.promptSecret("密码（" + name + "）");
        String email = args.size() >= 3 ? args.get(2).asString()
                : prompts.promptLine("@Email（可选，直接回车跳过）");
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        if (email != null && !email.isBlank()) {
            fields.put("@Email", email);
        }
        app.server().register(name, password, fields);
        return CommandResult.ok("注册请求已发送");
    }

    private CommandResult login(List<Arg> args, CommandDef.Context ctx) {
        if (app.mode() != SessionMode.SERVER || app.server() == null) {
            throw new Errors.Usage("未连接到服务器");
        }
        String name = args.get(0).asString();
        Text.requireServerName(name);
        String password = args.size() >= 2 ? args.get(1).asString() : prompts.promptSecret("密码（" + name + "）");
        app.server().login(name, password);
        return CommandResult.ok("登录请求已发送");
    }

    private CommandResult open(List<Arg> args, CommandDef.Context ctx) {
        String name = args.get(0).asString();
        for (ConversationRef ref : app.conversations()) {
            if (ref.title().equals(name) || ref.id().equals(name)) {
                app.openConversation(ref);
                return CommandResult.ok("已打开 " + ref.title());
            }
        }
        throw new Errors.NotFound("不存在名为 '" + name + "' 的会话");
    }

    private String[] friendLines() {
        List<String> out = new ArrayList<>();
        for (String friend : app.friends()) {
            FriendState state = app.friendState(friend);
            out.add(friend + " — " + friendStateLabel(state) + " — "
                    + peerStatusLabel(app.peerStatus(friend)));
        }
        if (out.isEmpty()) {
            out.add("（尚无好友 —— 使用 ${friend-request(name)} 发起联系）");
        }
        return out.toArray(String[]::new);
    }

    private CommandResult friendRequest(List<Arg> args, CommandDef.Context ctx) {
        String name = args.get(0).asString();
        boolean delivered = app.sendFriendRequest(name);
        return delivered
                ? CommandResult.ok("已向 " + name + " 发送好友请求（发送不等于送达 —— 请留意通知）")
                : CommandResult.ok("对 " + name + " 的好友请求无法送达（对端不可达）");
    }

    private CommandResult connect(List<Arg> args, CommandDef.Context ctx) {
        if (app.local() == null) {
            throw new Errors.Usage("本地模式未激活");
        }
        String address = args.get(0).asString();
        int colon = address.lastIndexOf(':');
        if (colon <= 0 || colon == address.length() - 1) {
            throw new Errors.Validation("地址格式须为 host:port");
        }
        String host = address.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(address.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new Errors.Validation("地址格式须为 host:port");
        }
        app.local().connect(host, port);
        return CommandResult.ok("已向 " + address + " 发起连接尝试");
    }

    private CommandResult createGroup(List<Arg> args, boolean encrypted) {
        if (app.mode() != SessionMode.SERVER || app.server() == null) {
            throw new Errors.Usage("群组在服务器模式下创建");
        }
        String name = args.get(0).asString();
        List<String> members = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            members.add(args.get(i).asString());
        }
        String groupId = app.server().createGroup(name, members, encrypted);
        String disclosure = encrypted
                ? "加密群组：服务器运营者无法读取其内容；运营者不对其无法解密的内容负责"
                : "运营者可见群组：服务器运营者可读取内容并进行审核";
        return CommandResult.ok("群组 '" + name + "' 已创建（" + groupId + "）", disclosure);
    }

    private CommandResult record(List<Arg> args, CommandDef.Context ctx) {
        String key = currentKeyOrError();
        if (args.isEmpty()) {
            return CommandResult.ok(app.recording(key)
                    ? "该会话记录已开启"
                    : "该会话记录已关闭 —— 新消息仅驻留内存，直至 ${persist}");
        }
        boolean on = requireOnOff(args.get(0));
        app.setRecording(key, currentTitle(), on);
        return CommandResult.ok(on
                ? "已开启记录 —— 新消息将自动持久化"
                : "已关闭记录 —— 新消息保留在内存缓冲中（已保存的历史记录不会被删除）");
    }

    private CommandResult historyDelete(List<Arg> args, CommandDef.Context ctx) {
        String scope = args.get(0).asString();
        if (!scope.equals("current") && !scope.equals("peer") && !scope.equals("all")) {
            throw new Errors.Usage("范围必须为 current、peer 或 all");
        }
        String arg = args.size() >= 2 ? args.get(1).asString() : null;
        String scopeLabel = switch (scope) {
            case "current" -> "当前会话";
            case "peer" -> "指定对端";
            default -> "全部";
        };
        String confirm = prompts.promptLine("删除" + scopeLabel + "历史记录？此操作不可撤销。输入 YES 确认：");
        if (!"YES".equals(confirm)) {
            return CommandResult.ok("已取消删除 —— 未删除任何内容");
        }
        if (scope.equals("all")) {
            // 全量删除属破坏性操作，需要第二次显式确认（P2）。
            String again = prompts.promptLine("删除全部本地历史记录？再次输入 YES 确认：");
            if (!"YES".equals(again)) {
                return CommandResult.ok("已取消删除 —— 未删除任何内容");
            }
        }
        int removed = app.deleteHistory(scope, arg);
        return CommandResult.ok("已删除 " + removed + " 个历史记录文件");
    }

    private CommandResult say(List<Arg> args, CommandDef.Context ctx) {
        String text = args.get(0).asString();
        app.say(text);
        return CommandResult.ok("已发送");
    }

    // ---------------- 辅助 ----------------

    private String[] statusLines() {
        List<String> out = new ArrayList<>();
        SessionMode mode = app.mode();
        out.add("模式：" + (mode == SessionMode.NONE ? "菜单" : modeLabel(mode)));
        if (mode == SessionMode.LOCAL && app.local() != null) {
            out.add("本地名称：" + app.local().myName());
            out.add("可被发现：" + (app.discoverable() ? "开启" : "关闭")
                    + "，可连接：" + (app.connectable() ? "开启" : "关闭"));
            ConversationRef cur = app.currentConversation();
            if (cur != null) {
                out.add("会话：" + cur.title() + "（记录 "
                        + (app.recording(app.local().convKey(cur.id())) ? "已开启" : "已关闭") + "）");
            }
        }
        if (mode == SessionMode.SERVER && app.server() != null) {
            out.add("服务器：" + app.server().serverName() + " @ " + app.server().serverAddress());
            out.add("用户：" + (app.server().loggedInUser() == null ? "（未登录）" : app.server().loggedInUser()));
            ConversationRef cur = app.currentConversation();
            if (cur != null) {
                out.add("会话：" + cur.title());
            }
        }
        out.add("通知：" + app.notifications().size());
        return out.toArray(String[]::new);
    }

    private static String modeLabel(SessionMode mode) {
        return switch (mode) {
            case LOCAL -> "本地";
            case SERVER -> "服务器";
            case NONE -> "菜单";
        };
    }

    private String[] notificationLines() {
        List<Notification> list = app.notifications();
        if (list.isEmpty()) {
            return new String[]{"当前无活动通知"};
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Notification n = list.get(i);
            out.add((i + 1) + ". [" + notificationKindLabel(n.kind()) + "] " + n.from() + ": " + n.text()
                    + (n.kind() == NotificationKind.FRIEND_REQUEST
                    ? "  → ${notify(" + (i + 1) + ", \"pass\")} 或 ${notify(" + (i + 1) + ", \"reject\")}"
                    : "  → ${notify(" + (i + 1) + ", \"over\")}"));
        }
        return out.toArray(String[]::new);
    }

    private String[] groupMemberLines() {
        ConversationRef cur = app.currentConversation();
        if (cur == null || cur.kind() != ConversationRef.Kind.GROUP || app.server() == null) {
            throw new Errors.Usage("当前不在群组会话中");
        }
        var g = app.server().group(cur.id());
        if (g == null) {
            throw new Errors.NotFound("群组已不存在");
        }
        List<String> out = new ArrayList<>();
        out.add("群组：" + g.name + " — " + g.privacy + "（群主：" + g.owner + "）");
        for (String m : g.members) {
            out.add("  " + m);
        }
        return out.toArray(String[]::new);
    }

    private String currentGroupId() {
        ConversationRef cur = app.currentConversation();
        if (cur == null || cur.kind() != ConversationRef.Kind.GROUP) {
            throw new Errors.Usage("请先打开群组会话");
        }
        return cur.id();
    }

    private String currentKeyOrError() {
        ConversationRef cur = app.currentConversation();
        if (cur == null) {
            throw new Errors.Usage("未选择会话");
        }
        if (app.mode() == SessionMode.LOCAL && app.local() != null) {
            return app.local().convKey(cur.id());
        }
        if (app.mode() == SessionMode.SERVER && app.server() != null) {
            return app.server().convKey(cur);
        }
        throw new Errors.Usage("未选择会话");
    }

    private String currentTitle() {
        ConversationRef cur = app.currentConversation();
        return cur == null ? "" : cur.title();
    }

    private static String friendStateLabel(FriendState state) {
        return switch (state) {
            case ACCEPTED -> "已接受";
            case PENDING_OUT -> "已发出请求";
            case PENDING_IN -> "待处理请求";
            case REJECTED -> "已拒绝";
            case EXPIRED -> "已过期";
            case UNDELIVERABLE -> "无法送达";
            case NONE -> "无关系";
        };
    }

    private static String peerStatusLabel(org.ourcamellia.common.domain.PeerStatus status) {
        return switch (status) {
            case CONNECTED -> "在线";
            case DISCOVERED -> "可被发现";
            case UNREACHABLE -> "不可达";
            case UNCONFIRMED -> "未知";
        };
    }

    private static String notificationKindLabel(NotificationKind kind) {
        return switch (kind) {
            case FRIEND_REQUEST -> "好友请求";
            case ANNOUNCEMENT -> "服务器公告";
            case INFO -> "通知";
        };
    }

    private static boolean requireOnOff(Arg arg) {
        String v = arg.asString();
        if (v.equals("on")) {
            return true;
        }
        if (v.equals("off")) {
            return false;
        }
        throw new Errors.Usage("取值必须为 on 或 off");
    }

    private static CommandDef def(String name, String summary, String usage,
                                  List<CommandDef.Param> params, CommandDef.Context... contexts) {
        EnumSet<CommandDef.Context> set = EnumSet.noneOf(CommandDef.Context.class);
        set.addAll(List.of(contexts));
        return new CommandDef(name, summary, usage, params, set);
    }

    private static CommandDef.Param param(String name, CommandDef.ParamType type, boolean optional,
                                          String description) {
        return new CommandDef.Param(name, type, optional, description);
    }
}