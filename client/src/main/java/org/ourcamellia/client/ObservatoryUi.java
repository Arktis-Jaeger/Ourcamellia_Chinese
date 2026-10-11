package org.ourcamellia.client;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingInputStream;
import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Text;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Observatory 模式（需求 §2.1）：固定布局的终端界面——顶部固定高度的状态区、
 * 中部自适应高度的会话区、底部可交互的输入区。
 *
 * <p>只有一个渲染循环拥有显示权；异步网络事件只负责把视图标记为「脏」，
 * 这样输入光标永远不会被并发线程破坏。
 *
 * <p>本文件同时修复了两个已确认的缺陷（缺陷 A、缺陷 B）：
 * <ul>
 *   <li><b>输入失效</b>：原先的输入泵用 {@code input.available() > 0} 判断是否有输入，
 *       但 JLine 的 {@link NonBlockingInputStream} 并未重写 {@code available()}
 *       （基类 {@link java.io.InputStream#available()} 恒为 0），因此主循环永远读不到
 *       任何按键，"提示可见但无法输入"。改为直接调用非阻塞 {@code read(超时)} 读取。</li>
 *   <li><b>拖动窗口崩溃</b>：输入行的可用宽度在「长会话标题 + 窄窗口」时会被算成
 *       0 或负数，{@link LineEditor#render} 对其取子串抛出
 *       {@link StringIndexOutOfBoundsException}，且主循环未捕获 → 整个进程终止。
 *       修复为对宽度做下限钳制，并把尺寸变为 volatile、在渲染循环内兜底捕获异常并保留诊断。</li>
 * </ul>
 *
 * <p>支持调整大小（WINCH）、窄窗口（紧凑模式回退），以及对终端能力不足给出明确报错。
 * 消息内容在进入屏幕前会被净化（剥离控制字符），以抵御终端注入攻击。
 */
public final class ObservatoryUi {

    private static final int STATUS_LINES = 3;
    private static final int MAX_TRANSCRIPT_LINES = 2000;
    /** 连续渲染失败达到该次数即判定终端已不可恢复，保留诊断后退出（避免死循环）。 */
    private static final int MAX_RENDER_FAILURES = 5;

    private final ClientApp app;
    private final Terminal terminal;
    private final PrintWriter writer;
    private final CommandDispatcher dispatcher = new CommandDispatcher();
    private final LineEditor editor = new LineEditor();
    private final ConcurrentLinkedQueue<String> infoLines = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> chatLines = new ConcurrentLinkedQueue<>();
    private final List<String> transcript = new ArrayList<>();
    private volatile boolean dirty = true;       // 需要整屏重绘（状态、消息、尺寸变化等）
    private boolean inputDirty = false;          // 输入行本身有变化（按键/光标），只需重绘输入行
    private int renderFailures = 0;              // 连续渲染失败计数，用来自保护退出
    private boolean notifyMode = false;
    private String activePrompt;   // 非空表示正在等待某一提示的输入
    private String promptResult;   // 提示行被提交后填入
    private String pendingExitAnswer; // y/n/c 退出决策待定
    private StringBuilder escapeSeq;   // ANSI 转义序列累加器
    private volatile int width = 80;   // 由 JLine 信号线程更新，必须 volatile
    private volatile int height = 24;

    public static boolean supported() {
        try {
            TerminalBuilder.builder().system(true).dumb(true).build().close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public ObservatoryUi(ClientApp app) throws IOException {
        this.app = app;
        this.terminal = TerminalBuilder.builder().system(true).build();
        if (terminal.getType() == null || terminal.getType().equals("dumb")) {
            throw new Errors.Usage("当前终端不支持 Observatory 模式 —— 请使用 --mode old");
        }
        this.writer = terminal.writer();
        new CommandSet(app, new CommandSet.Prompts() {
            @Override
            public String promptSecret(String label) {
                return promptLine(label);
            }

            @Override
            public String promptLine(String label) {
                return awaitLine(label);
            }
        }).install(dispatcher);
    }

    public void run() {
        ClientAppFactory.attachEvents(app, new ObservatoryEvents());
        terminal.enterRawMode();
        InputStream input = terminal.input();
        // 注册 WINCH：终端尺寸变化时更新本地缓存的尺寸并标记整屏重绘。
        // getWidth/getHeight 在极端缩放瞬间可能失败，这里做兜底并保留旧值。
        terminal.handle(org.jline.terminal.Terminal.Signal.WINCH, signal -> {
            try {
                width = computeWidth();
                height = computeHeight();
            } catch (RuntimeException e) {
                diagnose("读取终端尺寸(WINCH)", e);
            }
            dirty = true;
        });
        width = computeWidth();
        height = computeHeight();
        addInfo("Observatory 模式。输入 ${help} 查看命令，${quit} 退出。");

        while (!app.exitRequested()) {
            try {
                pump(input, 100);
                if (dirty) {
                    safeRender();
                } else if (inputDirty) {
                    safeRenderInputRow();
                }
            } catch (IOException e) {
                // 终端/输入流层面的真实错误：保留诊断并退出（而非静默吞掉）。
                System.err.println("终端错误: " + e.getMessage());
                e.printStackTrace(System.err);
                app.requestExit();
            }
        }
        try {
            terminal.close();
        } catch (IOException ignored) {
        }
        app.close();
    }

    // ---------------- 输入泵 ----------------

    /**
     * 轮询读取输入至多 {@code timeoutMs} 毫秒。
     *
     * <p>关键修复：原先用 {@code input.available() > 0} 判断是否有输入，但 JLine 的
     * {@link NonBlockingInputStream} 不重写 {@code available()}（基类恒返回 0）——
     * 这曾导致整个终端永远读不到按键。现在改为使用其非阻塞 {@code read(剩余时间)}：
     * 该方法内部通过终端驱动的 VMIN/VTIME 在有限时间内等待，返回实际字节、
     * 无数据时的 {@code READ_EXPIRED} 或 EOF。
     */
    private void pump(InputStream input, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int b = readInputByte(input, deadline);
            if (b < 0) {
                sleepQuietly(10); // 无输入：短暂让出，保证异步事件能被及时重绘
                continue;
            }
            handleByte(b);
            if (dirty) {
                safeRender();
            } else if (inputDirty) {
                safeRenderInputRow();
            }
        }
    }

    /** 从输入流读取一个字节；返回 -1 表示本次没有可读字节。 */
    private int readInputByte(InputStream input, long deadline) throws IOException {
        if (input instanceof NonBlockingInputStream nb) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return -1;
            }
            // read(timeout)：在 timeout 内等待；无数据时返回 READ_EXPIRED(-2)/-1。
            return nb.read(remaining);
        }
        // 非 JLine 流（例如测试注入的流）：退化为原来的 available 判断。
        return input.available() > 0 ? input.read() : -1;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 处理单个字节（包括一部分按键的转义序列）。
     *
     * <p>按键被送入 {@link LineEditor#feed} 后，凡是没有「提交整行」的输入
     * （普通字符、退格、方向键、Ctrl-C/U 等）都会把 {@code inputDirty} 置为 true，
     * 从而仅重绘输入行；只有当提交了整行并进入命令/消息处理时，才可能触发整屏重绘。
     */
    private void handleByte(int b) {
        if (b == 27) { // ESC：开启一个转义序列
            escapeSeq = new StringBuilder();
            return;
        }
        if (escapeSeq != null) {
            if (escapeSeq.length() == 0 && (b == '[' || b == 'O')) {
                escapeSeq.append((char) b);
                return;
            }
            escapeSeq.append((char) b);
            if (escapeSeq.length() >= 2) {
                editor.escape(escapeSeq.substring(1));
                escapeSeq = null;
                inputDirty = true; // 光标可能已移动，重绘输入行以刷新光标位置
            }
            return;
        }
        String line = editor.feed(b);
        if (line == null) {
            inputDirty = true; // 任何修改缓冲区/光标的按键都需要重绘输入行
            return;
        }
        if (activePrompt != null) {
            promptResult = line;
            editor.reset();
            activePrompt = null;
            return;
        }
        if (pendingExitAnswer != null) {
            answerExit(line);
            return;
        }
        editor.reset();
        handleLine(line);
    }

    private void handleLine(String line) {
        ParsedInput parsed = org.ourcamellia.core.cmd.CommandParser.parse(line, false);
        if (parsed instanceof ParsedInput.Command cmd) {
            notifyMode = (cmd.name().equals("info") || cmd.name().equals("notify"))
                    && cmd.args().isEmpty();
            CommandResult result = dispatcher.dispatch(cmd, context());
            for (String out : result.lines()) {
                if (!out.isBlank()) {
                    addInfo(out);
                }
            }
            if (result.exitRequested()) {
                beginExit();
            }
            return;
        }
        if (parsed instanceof ParsedInput.Malformed mal) {
            notifyMode = false;
            addInfo("错误：命令格式有误 — " + mal.reason() + "（未发送或执行任何内容）");
            return;
        }
        if (parsed instanceof ParsedInput.Blank) {
            return;
        }
        ParsedInput.Message message = (ParsedInput.Message) parsed;
        if (notifyMode && message.text().matches("^\\d+\\s+(pass|reject|over)$")) {
            String[] parts = message.text().split("\\s+");
            addInfo(app.notifyAction(Integer.parseInt(parts[0]), parts[1]));
            return;
        }
        notifyMode = false;
        try {
            app.say(message.text());
            addChat(app.local() != null
                    ? app.local().myName() : String.valueOf(app.server() == null ? "我" : app.server().loggedInUser())
                    , message.text());
        } catch (Errors.OcException e) {
            addInfo("错误：" + e.getMessage());
        }
    }

    private void beginExit() {
        String key = currentKeyOrNull();
        boolean buffered = key != null && app.hasBuffered(key);
        ExitPolicy.Decision decision = ExitPolicy.decide(buffered, true);
        if (!decision.promptNeeded()) {
            app.requestExit();
            return;
        }
        addInfo(decision.message());
        pendingExitAnswer = "pending";
    }

    private void answerExit(String line) {
        pendingExitAnswer = null;
        switch (line.trim().toLowerCase()) {
            case "y" -> {
                String key = currentKeyOrNull();
                if (key != null) {
                    int n = app.persist(key);
                    addInfo("已持久化 " + n + " 条消息");
                }
                app.requestExit();
            }
            case "n" -> {
                addInfo("已丢弃缓冲消息");
                app.requestExit();
            }
            default -> addInfo("已取消退出");
        }
    }

    /** 阻塞等待一行被提交（在命令分发期间调用）。 */
    private String awaitLine(String label) {
        addInfo(label);
        activePrompt = label;
        promptResult = null;
        InputStream input = terminal.input();
        while (promptResult == null && !app.exitRequested()) {
            try {
                pump(input, 80);
                if (dirty) {
                    safeRender();
                } else if (inputDirty) {
                    safeRenderInputRow();
                }
            } catch (IOException e) {
                System.err.println("终端错误: " + e.getMessage());
                app.requestExit();
                break;
            }
        }
        return promptResult == null ? "" : promptResult;
    }

    private CommandDef.Context context() {
        if (app.mode() == SessionMode.NONE) {
            return CommandDef.Context.L1;
        }
        return app.currentConversation() == null ? CommandDef.Context.L2 : CommandDef.Context.L3;
    }

    private void addInfo(String text) {
        infoLines.add(Text.renderable(text));
        dirty = true;
    }

    private void addChat(String sender, String text) {
        chatLines.add(Text.renderable(sender) + "> " + Text.renderable(text));
        dirty = true;
    }

    // ---------------- 渲染 ----------------

    /** 带兜底的整屏渲染：异常被记录为诊断并尝试恢复，而非让进程崩溃。 */
    private void safeRender() {
        try {
            render();
            renderFailures = 0;
        } catch (RuntimeException e) {
            diagnose("整屏渲染", e);
            dirty = false;
            inputDirty = false;
            if (++renderFailures >= MAX_RENDER_FAILURES) {
                System.err.println("终端连续渲染失败，退出以便诊断：");
                e.printStackTrace(System.err);
                app.requestExit();
            }
        }
    }

    /** 带兜底的输入行渲染。 */
    private void safeRenderInputRow() {
        try {
            renderInputRow();
            renderFailures = 0;
        } catch (RuntimeException e) {
            diagnose("输入行重绘", e);
            inputDirty = false;
            if (++renderFailures >= MAX_RENDER_FAILURES) {
                System.err.println("输入行持续重绘失败，退出以便诊断：");
                e.printStackTrace(System.err);
                app.requestExit();
            }
        }
    }

    private void render() {
        // 把事件行导入有界 transcript。
        String line;
        while ((line = infoLines.poll()) != null) {
            transcript.addAll(wrap(line, width - 2));
        }
        while ((line = chatLines.poll()) != null) {
            transcript.addAll(wrap(line, width - 2));
        }
        while (transcript.size() > MAX_TRANSCRIPT_LINES) {
            transcript.remove(0);
        }
        // 尺寸是 volatile，可能在渲染间隙被信号线程修改；此处取一次快照保持一致。
        int w = width;
        int h = height;
        if (h < 8 || w < 30) {
            renderCompact(w, h);
            return;
        }
        writer.print("\u001b[2J\u001b[H"); // 清屏 + 光标回左上角
        int convLines = h - STATUS_LINES - 2;

        // 状态区。
        writer.println(Text.renderable(statusLine1()));
        writer.println(Text.renderable(statusLine2()));
        writer.println(Text.renderable(statusLine3()));

        // 会话区。
        List<String> view = conversationView(convLines);
        for (String l : view) {
            writer.println(l);
        }
        for (int i = view.size(); i < convLines; i++) {
            writer.println();
        }

        // 输入区：提示符号 + 行编辑器（此时编辑光标被定位到缓冲区末尾/光标处）。
        writer.print(promptPrefix() + " ");
        editor.render(writer, "", Math.max(1, w - promptPrefix().length() - 1));
        dirty = false;
        inputDirty = false;
    }

    private void renderCompact(int w, int h) {
        writer.print("\u001b[2J\u001b[H");
        writer.println("[终端过小 —— 紧凑模式] " + Text.renderable(statusLine1()));
        for (int i = 0; i < h - 3; i++) {
            writer.println();
        }
        writer.print(promptPrefix() + " ");
        editor.render(writer, "", Math.max(8, w - promptPrefix().length() - 1));
        dirty = false;
        inputDirty = false;
    }

    /**
     * 只重绘「底部的输入行」：把光标移到固定的输入行、清空该行，再重画
     * 提示符与当前输入内容。这样在键入时不至于整屏闪烁，也不会因为状态/消息
     * 到达而丢失正在输入的内容（缓冲区保存在 LineEditor 中，未被触碰）。
     */
    private void renderInputRow() {
        int w = width;
        int h = height;
        String prompt = promptPrefix();
        // 输入行固定在第 (h-1) 行（1 基）。普通模式：3 状态 + (h-5) 会话行 = h-2 行，
        // 输入行位于 h-1；紧凑模式同理。
        writer.print("\u001b[" + (h - 1) + ";1H\u001b[2K");
        writer.print(prompt + " ");
        editor.render(writer, "", Math.max(1, w - prompt.length() - 1));
        writer.flush();
        inputDirty = false;
    }

    private List<String> conversationView(int maxLines) {
        List<String> out = new ArrayList<>();
        if (app.currentConversation() != null) {
            int from = Math.max(0, transcript.size() - maxLines);
            out.addAll(transcript.subList(from, transcript.size()));
            return out;
        }
        // 第 2 层：会话列表。
        List<ConversationRef> conversations = app.conversations();
        if (conversations.isEmpty()) {
            out.add(app.mode() == SessionMode.NONE
                    ? "菜单 —— 进入一种模式：${mode(\"local\", name)} 或 ${mode(\"server\", \"host:port\")}"
                    : "尚无会话 —— 使用 ${friend-request(...)} 发起联系");
        } else {
            out.add("会话列表：");
            for (ConversationRef ref : conversations) {
                String status = switch (app.peerStatus(ref.id())) {
                    case CONNECTED -> "(已连接)";
                    case DISCOVERED -> "(在线可见)";
                    case UNREACHABLE -> "(不可达)";
                    case UNCONFIRMED -> "";
                };
                out.add("  " + (ref.pinned() ? "◆ " : "  ") + ref.title() + " " + status);
            }
        }
        int from = Math.max(0, out.size() - maxLines);
        return out.subList(from, out.size());
    }

    private String statusLine1() {
        return switch (app.mode()) {
            case NONE -> "Ourcamellia —— 主菜单";
            case LOCAL -> "本地模式 · 你：" + app.local().myName();
            case SERVER -> "服务器模式 · " + (app.server().serverName() == null ? app.server().serverAddress()
                    : app.server().serverName()) + " · "
                    + (app.server().loggedInUser() == null ? "(未登录)" : app.server().loggedInUser());
        };
    }

    private String statusLine2() {
        if (app.mode() == SessionMode.LOCAL && app.local() != null) {
            ConversationRef cur = app.currentConversation();
            if (cur == null) {
                return "可被发现 " + onOff(app.discoverable()) + " · 可连接 "
                        + onOff(app.connectable()) + " · " + app.local().friends().size() + " 位好友";
            }
            return "会话：" + cur.title() + " · " + app.peerStatus(cur.id()).name().toLowerCase()
                    + " · " + app.friendState(cur.id()).name().toLowerCase();
        }
        if (app.mode() == SessionMode.SERVER && app.server() != null) {
            ConversationRef cur = app.currentConversation();
            if (cur == null) {
                return app.server().friends().size() + " 位好友 · "
                        + app.server().groups().size() + " 个群组";
            }
            return "会话：" + cur.title()
                    + (cur.privacy() != null ? " · " + cur.privacy().name().toLowerCase() : "");
        }
        return "";
    }

    private String statusLine3() {
        ConversationRef cur = app.currentConversation();
        StringBuilder sb = new StringBuilder();
        if (cur != null) {
            String key = currentKeyOrNull();
            if (key != null) {
                sb.append("录制 ").append(app.recording(key) ? "on" : "off")
                        .append(app.hasBuffered(key) ? " · " + bufferedCount(key) + " 条待持久化" : "")
                        .append("   ");
            }
        }
        int n = app.notifications().size();
        sb.append("通知").append(n > 0 ? "[" + n + "]" : "").append("  ");
        if (app.mode() == SessionMode.LOCAL && app.local() != null) {
            sb.append("身份 ").append(shortFp(app.local().myFingerprint()));
        }
        return sb.toString();
    }

    private int bufferedCount(String key) {
        // 待持久化条目只保存在内存；计数由历史存储维护。
        var c = app.history().conversation(key);
        return c == null ? 0 : c.buffered.size();
    }

    private String promptPrefix() {
        return switch (context()) {
            case L1 -> "(菜单)";
            case L2 -> "(列表)";
            case L3 -> "(" + app.currentConversation().title() + ")";
            case ANY -> ">";
        };
    }

    private String shortFp(String fp) {
        return fp == null || fp.length() < 12 ? "?" : fp.substring(0, 12) + "…";
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    private static List<String> wrap(String text, int width) {
        if (width <= 0) {
            return List.of(text);
        }
        if (text.length() <= width) {
            return List.of(text);
        }
        List<String> out = new ArrayList<>();
        while (text.length() > width) {
            out.add(text.substring(0, width));
            text = text.substring(width);
        }
        out.add(text);
        return out;
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

    /** 安全的尺寸读取：失败时回退到旧尺寸，避免信号线程把尺寸改坏。 */
    private int computeWidth() {
        try {
            return Math.max(20, terminal.getWidth());
        } catch (RuntimeException e) {
            return width;
        }
    }

    private int computeHeight() {
        try {
            return Math.max(6, terminal.getHeight());
        } catch (RuntimeException e) {
            return height;
        }
    }

    /** 向 stderr 输出一条带上下文的诊断信息（保留在日志中，便于定位问题）。 */
    private void diagnose(String what, RuntimeException e) {
        System.err.println("[" + what + "] 渲染异常 (width=" + width + ", height=" + height + "): "
                + e);
        e.printStackTrace(System.err);
    }

    private final class ObservatoryEvents implements AppEvents {
        @Override
        public void onMessage(ConversationRef conversation, Message message) {
            addChat(message.sender(), message.text());
        }

        @Override
        public void onStatusChanged() {
            dirty = true;
        }

        @Override
        public void onNotificationsChanged() {
            dirty = true;
        }

        @Override
        public void onInfo(String text) {
            addInfo(text);
        }

        @Override
        public void onPeerStatus(String peerName, PeerStatus status) {
            dirty = true;
        }

        @Override
        public void onShutdown() {
            dirty = true;
        }
    }
}