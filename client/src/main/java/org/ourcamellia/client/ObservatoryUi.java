package org.ourcamellia.client;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Text;
import org.ourcamellia.common.config.ClientConfig;
import org.ourcamellia.common.domain.ConversationRef;
import org.ourcamellia.common.domain.Message;
import org.ourcamellia.common.domain.Notification;
import org.ourcamellia.common.domain.NotificationKind;
import org.ourcamellia.common.domain.PeerStatus;
import org.ourcamellia.common.domain.SessionMode;
import org.ourcamellia.core.app.AppEvents;
import org.ourcamellia.core.app.ClientApp;
import org.ourcamellia.core.app.ClientAppFactory;
import org.ourcamellia.core.app.CommandSet;
import org.ourcamellia.core.app.ExitPolicy;
import org.ourcamellia.core.cmd.CommandDef;
import org.ourcamellia.core.cmd.CommandDispatcher;
import org.ourcamellia.core.cmd.CommandParser;
import org.ourcamellia.core.cmd.CommandResult;
import org.ourcamellia.core.cmd.ParsedInput;
import org.ourcamellia.core.persist.LocalHistoryStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Observatory 模式：固定布局的终端界面。
 *
 * <p>界面由一条渲染循环独占终端。所有网络/应用事件先进入线程安全队列，再由
 * 主循环在渲染前统一应用；输入缓冲区（消息、命令、表单字段）不参与事件处理，
 * 因此异步消息不会覆盖正在输入的内容，也不会移动光标。
 *
 * <p>布局规则（每一页都遵守）：
 * <ul>
 *   <li>最上方与最下方各一条由 {@code =} 组成、占满终端宽度的横线；</li>
 *   <li>中间区域使用由 {@code -} 组成、同样占满宽度的横线；</li>
 *   <li>页面上方有可见的上下文标题（私聊除外）；</li>
 *   <li>菜单/列表用 {@code >} 指示焦点，↑↓ 移动、Enter 确认、Esc 返回；</li>
 *   <li>所有可交互页面都有可见的命令输入栏（帮助页是唯一的全屏例外，
 *       返回后恢复原页面）；</li>
 *   <li>聊天页把消息区、消息输入区、命令区明确分开。</li>
 * </ul>
 *
 * <p>对外行为：默认 Observatory；Old 模式由配置或命令行显式选择，二者共享同一
 * 套业务核心与命令系统。本类不直接执行任何网络或存储操作，只调用
 * {@link ClientApp} 的公开方法。
 */
public final class ObservatoryUi {

    private static final int MAX_RENDER_FAILURES = 5;
    private static final int MAX_TRANSCRIPT_ENTRIES = 4000;
    private static final int MAX_INFO_LINES = 100;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private enum Screen {
        MODE_MENU, SERVER_LOGIN, SERVER_REGISTER, LOCAL_LOGIN,
        LIST, CHAT, NOTIFICATIONS, NOTIFICATION_DETAIL, NOTIFICATION_ACTION,
        SETTINGS, ADD_CONTACT, CONNECT_ADDRESS, HELP
    }

    private enum ChatFocus { MESSAGE, COMMAND }

    private enum RowKind { CONVERSATION, NOTIFICATIONS, ADD_CONTACT, CONNECT_ADDRESS, SETTINGS }

    private record Row(String label, String right, RowKind kind, ConversationRef ref) {}

    private record ChatEntry(String id, String sender, String text, long ts,
                             boolean system, boolean outgoing) {}

    private record FormAction(String label, Runnable action) {}

    private static final class Field {
        final String label;
        final TextEditor editor;
        final boolean secret;

        Field(String label, boolean secret) {
            this.label = label;
            this.editor = new TextEditor(false);
            this.secret = secret;
        }
    }

    private static final class Frame {
        final List<String> rows = new ArrayList<>();
        int cursorRow = -1;   // 0 基
        int cursorCol = 0;    // 0 基
        boolean cursorVisible;
    }

    private final ClientApp app;
    private final Path dataDir;
    private final Terminal terminal;
    private final PrintWriter writer;
    private final KeyReader keys;
    private final CommandDispatcher dispatcher = new CommandDispatcher();
    private final TextEditor commandEditor = new TextEditor(false);
    private final TextEditor messageEditor = new TextEditor(true);
    private final TextEditor promptEditor = new TextEditor(false);

    private final Map<String, List<ChatEntry>> transcripts = new HashMap<>();
    private final Map<String, Set<String>> messageIds = new HashMap<>();
    private final Map<String, Integer> unread = new HashMap<>();
    private final Map<String, String> drafts = new HashMap<>();
    private final List<String> info = new ArrayList<>();
    private final ConcurrentLinkedQueue<Runnable> events = new ConcurrentLinkedQueue<>();

    private final List<Field> formFields = new ArrayList<>();
    private final List<FormAction> formActions = new ArrayList<>();
    private Runnable formSubmit = () -> {};
    private int formFocus = 0;

    private final List<String> helpLines = new ArrayList<>();
    private Screen helpReturnScreen = Screen.MODE_MENU;
    private int helpReturnFocus = 0;
    private ChatFocus helpReturnChatFocus = ChatFocus.MESSAGE;

    private Screen screen = Screen.MODE_MENU;
    private String chatKey = null; // 当前聊天页对应的会话键；草稿按它保存，避免切换会话时丢草稿
    private int focus = 0;
    private int listScroll = 0;
    private int notificationScroll = 0;
    private int chatScroll = 0;
    private int msgScroll = 0;
    private int helpScroll = 0;
    private ChatFocus chatFocus = ChatFocus.MESSAGE;

    private List<Notification> notificationCache = List.of();
    private int notificationIndex = -1;

    private boolean promptActive = false;
    private String promptLabel = "";
    private boolean promptSecret = false;
    private String promptResult = null;
    private boolean promptCancelled = false;

    private boolean exitPrompt = false;
    private String exitQuestion = "";

    private volatile boolean dirty = true;
    private int renderFailures = 0;
    private volatile int width = 80;
    private volatile int height = 24;
    private boolean firstRender = true;
    private final String localAddress;

    public static boolean supported() {
        try {
            TerminalBuilder.builder().system(true).dumb(true).build().close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public ObservatoryUi(ClientApp app, Path dataDir) throws IOException {
        this.app = app;
        this.dataDir = dataDir;
        this.localAddress = LocalNet.localAddress();
        this.terminal = TerminalBuilder.builder().system(true).build();
        if (terminal.getType() == null || terminal.getType().equals("dumb")) {
            throw new Errors.Usage("当前终端不支持 Observatory 模式 —— 请使用 --mode old，"
                    + "或在配置中设置 terminal.mode=OLD");
        }
        this.writer = terminal.writer();
        this.keys = new KeyReader(terminal.input());
        new CommandSet(app, new CommandSet.Prompts() {
            @Override
            public String promptSecret(String label) {
                return awaitLine(label, true);
            }

            @Override
            public String promptLine(String label) {
                return awaitLine(label, false);
            }
        }).install(dispatcher);
    }

    public void run() {
        ClientAppFactory.attachEvents(app, new ObservatoryEvents());
        try {
            terminal.enterRawMode();
            writer.print("\u001b[?7l\u001b[2J\u001b[H\u001b[?25l");
            writer.flush();
            width = computeWidth();
            height = computeHeight();
            terminal.handle(Terminal.Signal.WINCH, signal -> {
                try {
                    width = computeWidth();
                    height = computeHeight();
                } catch (RuntimeException e) {
                    diagnose("读取终端尺寸(WINCH)", e);
                }
                dirty = true;
            });
            addInfo("Observatory 模式。输入 ${help} 查看帮助；终端无法区分 Shift+Enter 时用 Ctrl+S 发送。");
            while (!app.exitRequested()) {
                drainEvents();
                syncState();
                KeyReader.Key key = keys.read(80);
                if (key != null) {
                    handleKey(key);
                    dirty = true;
                }
                if (keys.eof() && !app.exitRequested()) {
                    app.requestExit();
                }
                if (dirty) {
                    renderSafely();
                }
            }
            saveDraft();
        } catch (IOException e) {
            System.err.println("终端错误：" + e.getMessage());
            e.printStackTrace(System.err);
            app.requestExit();
        } finally {
            try {
                writer.print("\u001b[0m\u001b[?25h\u001b[?7h\r\n");
                writer.flush();
                terminal.close();
            } catch (IOException ignored) {
            }
            app.close();
        }
    }

    // ================= 事件 =================

    /**
     * 网络线程只把动作放入队列；真正的状态修改发生在渲染线程。
     * 这样 {@link #transcripts}/{@link #info} 等集合不需要跨线程加锁，
     * 也不会在渲染中途被修改。
     */
    private final class ObservatoryEvents implements AppEvents {
        @Override
        public void onMessage(ConversationRef conversation, Message message) {
            events.add(() -> handleIncomingMessage(conversation, message));
            dirty = true;
        }

        @Override
        public void onStatusChanged() {
            events.add(() -> dirty = true);
            dirty = true;
        }

        @Override
        public void onNotificationsChanged() {
            events.add(() -> {
                notificationCache = safeNotifications();
                dirty = true;
            });
            dirty = true;
        }

        @Override
        public void onInfo(String text) {
            events.add(() -> addInfo(text));
            dirty = true;
        }

        @Override
        public void onPeerStatus(String peerName, PeerStatus status) {
            events.add(() -> dirty = true);
            dirty = true;
        }

        @Override
        public void onShutdown() {
            events.add(() -> dirty = true);
            dirty = true;
        }
    }

    private void drainEvents() {
        Runnable r;
        while ((r = events.poll()) != null) {
            r.run();
            dirty = true;
        }
    }

    private void syncState() {
        if (screen == Screen.SERVER_LOGIN || screen == Screen.SERVER_REGISTER) {
            if (app.mode() == SessionMode.SERVER && app.server() != null
                    && app.server().isLoggedIn()) {
                // 注册策略为 auto 时，服务器会在注册成功后直接完成登录；
                // 此时也必须进入会话列表，而不是停在注册表单上。
                openList();
            }
        }
        if ((screen == Screen.LIST || screen == Screen.CHAT) && app.mode() == SessionMode.NONE) {
            screen = Screen.MODE_MENU;
            messageEditor.clear();
            dirty = true;
        }
        if (screen == Screen.CHAT && app.currentConversation() == null) {
            saveDraft();
            chatKey = null;
            screen = Screen.LIST;
            dirty = true;
        }
    }

    // ================= 按键分发 =================

    private void handleKey(KeyReader.Key key) {
        if (exitPrompt) {
            handleExitKey(key);
            return;
        }
        if (promptActive) {
            handlePromptKey(key);
            return;
        }
        if (screen == Screen.HELP) {
            handleHelpKey(key);
            return;
        }
        if (screen == Screen.CHAT) {
            handleChatKey(key);
            return;
        }
        if (isFormScreen()) {
            handleFormKey(key);
            return;
        }
        handlePageKey(key);
    }

    private boolean isFormScreen() {
        return screen == Screen.SERVER_LOGIN || screen == Screen.SERVER_REGISTER
                || screen == Screen.LOCAL_LOGIN || screen == Screen.ADD_CONTACT
                || screen == Screen.CONNECT_ADDRESS;
    }

    private void handlePageKey(KeyReader.Key key) {
        switch (key.type()) {
            case CHAR -> {
                // 大写 I 是会话列表的“通知”快捷键；只有命令栏为空时生效，
                // 避免影响用户正在输入的命令。
                if (key.text().equals("I") && commandEditor.isEmpty() && screen == Screen.LIST) {
                    openNotifications();
                } else {
                    commandEditor.insertText(key.text());
                }
            }
            case PASTE -> commandEditor.insertText(key.text());
            case ENTER -> {
                if (!commandEditor.isEmpty()) {
                    submitCommand();
                } else {
                    activateFocus();
                }
            }
            case ESC -> {
                if (!commandEditor.isEmpty()) {
                    commandEditor.clear();
                } else {
                    back();
                }
            }
            case UP -> moveFocus(-1);
            case DOWN -> moveFocus(1);
            case PAGE_UP -> scrollPage(-1);
            case PAGE_DOWN -> scrollPage(1);
            case HOME -> commandEditor.home();
            case END -> commandEditor.end();
            case LEFT -> commandEditor.left();
            case RIGHT -> commandEditor.right();
            case BACKSPACE -> commandEditor.backspace();
            case DELETE -> commandEditor.delete();
            case CTRL_C, CTRL_U -> commandEditor.clear();
            case CTRL_A -> commandEditor.home();
            case CTRL_E -> commandEditor.end();
            case CTRL_K -> commandEditor.deleteToLineEnd();
            default -> {
                // Tab、Ctrl+S 等在当前上下文无动作；不改变任何状态。
            }
        }
    }

    private void handleFormKey(KeyReader.Key key) {
        int commandIndex = formFields.size() + formActions.size();
        if (formFocus >= commandIndex) {
            // 命令栏焦点：与普通页面的命令输入栏语义一致。
            switch (key.type()) {
                case CHAR -> commandEditor.insertText(key.text());
                case PASTE -> commandEditor.insertText(key.text());
                case ENTER -> {
                    if (!commandEditor.isEmpty()) {
                        submitCommand();
                    }
                }
                case ESC -> {
                    if (!commandEditor.isEmpty()) {
                        commandEditor.clear();
                    } else {
                        formFocus = Math.max(0, commandIndex - 1);
                    }
                }
                case TAB -> formFocus = 0;
                case SHIFT_TAB -> formFocus = Math.max(0, commandIndex - 1);
                case BACKSPACE -> commandEditor.backspace();
                case DELETE -> commandEditor.delete();
                case LEFT -> commandEditor.left();
                case RIGHT -> commandEditor.right();
                case HOME -> commandEditor.home();
                case END -> commandEditor.end();
                case CTRL_C, CTRL_U -> commandEditor.clear();
                default -> {
                }
            }
            return;
        }
        if (formFocus >= formFields.size()) {
            // 表单动作（如“注册新账户”“返回”）。
            switch (key.type()) {
                case ENTER -> {
                    int idx = formFocus - formFields.size();
                    if (idx >= 0 && idx < formActions.size()) {
                        formActions.get(idx).action().run();
                    }
                }
                case ESC -> back();
                case UP, SHIFT_TAB -> moveFormFocus(-1);
                case DOWN, TAB -> moveFormFocus(1);
                default -> {
                }
            }
            return;
        }
        TextEditor editor = formFields.get(formFocus).editor;
        switch (key.type()) {
            case CHAR -> editor.insertText(key.text());
            case PASTE -> editor.insertText(key.text());
            case ENTER -> {
                if (formFocus == formFields.size() - 1) {
                    formSubmit.run();
                } else {
                    formFocus++;
                }
            }
            case TAB -> moveFormFocus(1);
            case SHIFT_TAB -> moveFormFocus(-1);
            case ESC -> back();
            case UP -> moveFormFocus(-1);
            case DOWN -> moveFormFocus(1);
            case BACKSPACE -> editor.backspace();
            case DELETE -> editor.delete();
            case LEFT -> editor.left();
            case RIGHT -> editor.right();
            case HOME -> editor.home();
            case END -> editor.end();
            case CTRL_C, CTRL_U -> editor.clear();
            case CTRL_A -> editor.home();
            case CTRL_E -> editor.end();
            default -> {
            }
        }
    }

    private void moveFormFocus(int delta) {
        int count = formFields.size() + formActions.size() + 1; // +1 命令栏
        if (count <= 0) {
            return;
        }
        formFocus = Math.floorMod(formFocus + delta, count);
    }

    private void handleChatKey(KeyReader.Key key) {
        if (chatFocus == ChatFocus.COMMAND) {
            switch (key.type()) {
                case CHAR -> commandEditor.insertText(key.text());
                case PASTE -> commandEditor.insertText(key.text());
                case ENTER -> {
                    if (!commandEditor.isEmpty()) {
                        submitCommand();
                    }
                }
                case ESC -> {
                    if (!commandEditor.isEmpty()) {
                        commandEditor.clear();
                    } else {
                        chatFocus = ChatFocus.MESSAGE;
                    }
                }
                case TAB -> chatFocus = ChatFocus.MESSAGE;
                case BACKSPACE -> commandEditor.backspace();
                case DELETE -> commandEditor.delete();
                case LEFT -> commandEditor.left();
                case RIGHT -> commandEditor.right();
                case HOME -> commandEditor.home();
                case END -> commandEditor.end();
                case CTRL_C, CTRL_U -> commandEditor.clear();
                case CTRL_A -> commandEditor.home();
                case CTRL_E -> commandEditor.end();
                case CTRL_K -> commandEditor.deleteToLineEnd();
                default -> {
                }
            }
            return;
        }
        switch (key.type()) {
            case CHAR -> messageEditor.insertText(key.text());
            case PASTE -> messageEditor.insertText(key.text());
            case ENTER -> applyChatAction(ChatInput.decideEnter(false, app.config().shiftEnterToSend()));
            case SHIFT_ENTER -> applyChatAction(ChatInput.decideEnter(true, app.config().shiftEnterToSend()));
            case CTRL_S, CTRL_ENTER -> sendMessage();
            case TAB -> chatFocus = ChatFocus.COMMAND;
            case ESC -> {
                saveDraft();
                app.closeConversation();
                chatKey = null;
                screen = Screen.LIST;
                messageEditor.clear();
                chatScroll = 0;
            }
            case BACKSPACE -> messageEditor.backspace();
            case DELETE -> messageEditor.delete();
            case LEFT -> messageEditor.left();
            case RIGHT -> messageEditor.right();
            case HOME -> messageEditor.home();
            case END -> messageEditor.end();
            case UP -> messageEditor.up();
            case DOWN -> messageEditor.down();
            case PAGE_UP -> chatScroll += Math.max(1, (height - 12) / 2);
            case PAGE_DOWN -> chatScroll = Math.max(0, chatScroll - Math.max(1, (height - 12) / 2));
            case CTRL_C, CTRL_U -> messageEditor.clear();
            case CTRL_A -> messageEditor.home();
            case CTRL_E -> messageEditor.end();
            case CTRL_K -> messageEditor.deleteToLineEnd();
            case CTRL_W -> messageEditor.backspace();
            default -> {
            }
        }
    }

    private void applyChatAction(ChatInput.Action action) {
        if (action == ChatInput.Action.NEWLINE) {
            messageEditor.newline();
        } else {
            sendMessage();
        }
    }

    private void sendMessage() {
        String text = messageEditor.text();
        if (text.isBlank()) {
            addInfo("消息为空，未发送。");
            return;
        }
        // ${help} 是全局命令，即使在消息输入区输入也不能作为聊天内容发送。
        ParsedInput parsed = CommandParser.parse(text, false);
        if (parsed instanceof ParsedInput.Command cmd && cmd.name().equals("help")) {
            messageEditor.clear();
            openHelp();
            return;
        }
        try {
            app.say(text);
            messageEditor.clear();
            if (chatKey != null) {
                drafts.remove(chatKey);
            }
            chatScroll = 0;
            msgScroll = 0;
        } catch (Errors.OcException e) {
            addInfo("错误：" + e.getMessage());
        }
    }

    private void handleHelpKey(KeyReader.Key key) {
        int page = Math.max(1, height - 5);
        switch (key.type()) {
            case ENTER, ESC -> closeHelp();
            case UP -> helpScroll = Math.max(0, helpScroll - 1);
            case DOWN -> helpScroll = Math.min(maxHelpScroll(), helpScroll + 1);
            case PAGE_UP -> helpScroll = Math.max(0, helpScroll - page);
            case PAGE_DOWN -> helpScroll = Math.min(maxHelpScroll(), helpScroll + page);
            default -> {
            }
        }
    }

    private void handleExitKey(KeyReader.Key key) {
        if (key.type() == KeyReader.Type.CHAR) {
            String answer = key.text().toLowerCase();
            switch (answer) {
                case "y" -> {
                    String key2 = currentKeyOrNull();
                    if (key2 != null) {
                        int n = app.persist(key2);
                        addInfo("已持久化 " + n + " 条消息");
                    }
                    app.requestExit();
                }
                case "n" -> app.requestExit();
                case "c" -> {
                    exitPrompt = false;
                    addInfo("已取消退出");
                }
                default -> addInfo("请输入 y、n 或 c");
            }
            return;
        }
        if (key.type() == KeyReader.Type.ESC || key.type() == KeyReader.Type.CTRL_C) {
            exitPrompt = false;
            addInfo("已取消退出");
        }
    }

    // ================= 提示输入（命令需要密码/确认时） =================

    private String awaitLine(String label, boolean secret) {
        promptActive = true;
        promptLabel = label;
        promptSecret = secret;
        promptEditor.clear();
        promptResult = null;
        promptCancelled = false;
        dirty = true;
        try {
            while (promptActive && !app.exitRequested()) {
                drainEvents();
                renderSafely();
                KeyReader.Key key = keys.read(100);
                if (key != null) {
                    handlePromptKey(key);
                }
            }
        } catch (IOException e) {
            promptActive = false;
            throw new Errors.Usage("无法读取输入：" + e.getMessage());
        } finally {
            promptActive = false;
            dirty = true;
        }
        if (promptCancelled) {
            throw new Errors.Usage("已取消");
        }
        return promptResult == null ? "" : promptResult;
    }

    private void handlePromptKey(KeyReader.Key key) {
        switch (key.type()) {
            case CHAR -> promptEditor.insertText(key.text());
            case PASTE -> promptEditor.insertText(key.text());
            case ENTER -> {
                promptResult = promptEditor.text();
                promptActive = false;
            }
            case ESC, CTRL_C -> {
                promptCancelled = true;
                promptActive = false;
            }
            case BACKSPACE -> promptEditor.backspace();
            case DELETE -> promptEditor.delete();
            case LEFT -> promptEditor.left();
            case RIGHT -> promptEditor.right();
            case HOME -> promptEditor.home();
            case END -> promptEditor.end();
            case CTRL_U -> promptEditor.clear();
            default -> {
            }
        }
    }

    // ================= 命令 =================

    private void submitCommand() {
        String line = commandEditor.text();
        if (line.isBlank()) {
            return;
        }
        commandEditor.clear();
        ParsedInput parsed = CommandParser.parse(line, false);
        if (parsed instanceof ParsedInput.Command cmd) {
            if (cmd.name().equals("help")) {
                openHelp();
                return;
            }
            CommandResult result = dispatcher.dispatch(cmd, context());
            for (String out : result.lines()) {
                if (out != null && !out.isBlank()) {
                    addInfo(out);
                }
            }
            if (result.exitRequested()) {
                beginExit();
                return;
            }
            syncAfterCommand();
            return;
        }
        if (parsed instanceof ParsedInput.Malformed mal) {
            addInfo("错误：命令格式有误 — " + mal.reason() + "（未发送或执行任何内容）");
            return;
        }
        // 命令栏中的普通文本：绝不当作聊天消息发送，也不从中间提取命令。
        addInfo("命令栏只接受完整命令（以 ${ 开头、以 } 结束）。聊天内容请使用消息输入区。");
    }

    private void syncAfterCommand() {
        if (app.mode() == SessionMode.NONE) {
            screen = Screen.MODE_MENU;
            return;
        }
        if (app.currentConversation() != null) {
            String newKey = keyFor(app.currentConversation());
            if (screen != Screen.CHAT || !java.util.Objects.equals(newKey, chatKey)) {
                if (screen == Screen.CHAT) {
                    saveDraft();
                }
                openChat();
            }
            return;
        }
        if (screen == Screen.CHAT) {
            saveDraft();
            chatKey = null;
            screen = Screen.LIST;
            messageEditor.clear();
            return;
        }
        if (app.mode() == SessionMode.SERVER) {
            if (app.server() != null && !app.server().isLoggedIn()
                    && (screen == Screen.MODE_MENU || screen == Screen.SERVER_LOGIN
                    || screen == Screen.SERVER_REGISTER)) {
                openServerLogin();
                return;
            }
        }
        if (screen == Screen.MODE_MENU || screen == Screen.LOCAL_LOGIN
                || screen == Screen.SERVER_LOGIN || screen == Screen.SERVER_REGISTER) {
            openList();
        }
    }

    private CommandDef.Context context() {
        if (app.mode() == SessionMode.NONE) {
            return CommandDef.Context.L1;
        }
        return app.currentConversation() == null ? CommandDef.Context.L2 : CommandDef.Context.L3;
    }

    // ================= 导航 =================

    private void activateFocus() {
        switch (screen) {
            case MODE_MENU -> {
                switch (focus) {
                    case 0 -> openServerLogin();
                    case 1 -> openLocalLogin();
                    case 2 -> beginExit();
                    default -> {
                    }
                }
            }
            case LIST -> {
                List<Row> rows = currentRows();
                if (rows.isEmpty()) {
                    return;
                }
                focus = Math.min(focus, rows.size() - 1);
                Row row = rows.get(focus);
                switch (row.kind()) {
                    case CONVERSATION -> {
                        app.openConversation(row.ref());
                        openChat();
                    }
                    case NOTIFICATIONS -> openNotifications();
                    case ADD_CONTACT -> openAddContact();
                    case CONNECT_ADDRESS -> openConnectAddress();
                    case SETTINGS -> openSettings();
                }
            }
            case NOTIFICATIONS -> {
                if (!notificationCache.isEmpty()) {
                    focus = Math.min(focus, notificationCache.size() - 1);
                    openNotificationDetail(focus);
                }
            }
            case NOTIFICATION_DETAIL -> {
                if (focus == 0) {
                    applyNotificationAction("over");
                } else {
                    openNotifications();
                }
            }
            case NOTIFICATION_ACTION -> {
                switch (focus) {
                    case 0 -> applyNotificationAction("pass");
                    case 1 -> applyNotificationAction("reject");
                    default -> openNotifications();
                }
            }
            case SETTINGS -> {
                switch (focus) {
                    case 0 -> {
                        try {
                            app.setDiscoverable(!app.discoverable());
                        } catch (Errors.OcException e) {
                            addInfo("错误：" + e.getMessage());
                        }
                    }
                    case 1 -> {
                        try {
                            app.setConnectable(!app.connectable());
                        } catch (Errors.OcException e) {
                            addInfo("错误：" + e.getMessage());
                        }
                    }
                    default -> back();
                }
            }
            default -> {
            }
        }
    }

    private void back() {
        switch (screen) {
            case MODE_MENU -> addInfo("已在主菜单。使用 ↑↓ 选择项目，或输入命令。");
            case SERVER_LOGIN, SERVER_REGISTER, LOCAL_LOGIN -> {
                if (app.mode() != SessionMode.NONE) {
                    app.leaveMode();
                }
                screen = Screen.MODE_MENU;
                commandEditor.clear();
            }
            case LIST -> {
                if (app.mode() != SessionMode.NONE) {
                    app.leaveMode();
                }
                screen = Screen.MODE_MENU;
                commandEditor.clear();
            }
            case CHAT -> {
                saveDraft();
                app.closeConversation();
                chatKey = null;
                screen = Screen.LIST;
                messageEditor.clear();
                commandEditor.clear();
            }
            case NOTIFICATIONS, NOTIFICATION_DETAIL, NOTIFICATION_ACTION,
                 SETTINGS, ADD_CONTACT, CONNECT_ADDRESS -> {
                openList();
                commandEditor.clear();
            }
            case HELP -> closeHelp();
        }
    }

    private void moveFocus(int delta) {
        int count = focusCount();
        if (count <= 0) {
            return;
        }
        focus = Math.floorMod(focus + delta, count);
    }

    private int focusCount() {
        return switch (screen) {
            case MODE_MENU, SETTINGS -> 3;
            case LIST -> currentRows().size();
            case NOTIFICATIONS -> notificationCache.size();
            case NOTIFICATION_DETAIL -> 2;
            case NOTIFICATION_ACTION -> 3;
            default -> 0;
        };
    }

    private void scrollPage(int direction) {
        switch (screen) {
            case LIST -> listScroll = Math.max(0, listScroll + direction * 3);
            case NOTIFICATIONS -> notificationScroll = Math.max(0, notificationScroll + direction);
            default -> {
            }
        }
    }

    private void syncNotificationCache() {
        notificationCache = safeNotifications();
        if (notificationIndex >= notificationCache.size()) {
            notificationIndex = notificationCache.size() - 1;
        }
    }

    private List<Notification> safeNotifications() {
        try {
            return List.copyOf(app.notifications());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private void openList() {
        screen = Screen.LIST;
        focus = 0;
        listScroll = 0;
        syncNotificationCache();
        if (!isFormScreen()) {
            commandEditor.clear();
        }
        dirty = true;
    }

    private void openNotifications() {
        screen = Screen.NOTIFICATIONS;
        focus = 0;
        notificationScroll = 0;
        syncNotificationCache();
        dirty = true;
    }

    private void openNotificationDetail(int index) {
        if (index < 0 || index >= notificationCache.size()) {
            return;
        }
        notificationIndex = index;
        if (notificationCache.get(index).kind() == NotificationKind.FRIEND_REQUEST) {
            screen = Screen.NOTIFICATION_ACTION;
        } else {
            screen = Screen.NOTIFICATION_DETAIL;
        }
        focus = 0;
        dirty = true;
    }

    private void applyNotificationAction(String action) {
        if (notificationIndex < 0 || notificationIndex >= notificationCache.size()) {
            openNotifications();
            return;
        }
        try {
            String result = app.notifyAction(notificationIndex + 1, action);
            addInfo(result);
        } catch (Errors.OcException e) {
            addInfo("错误：" + e.getMessage());
        }
        openNotifications();
    }

    private void openChat() {
        ConversationRef ref = app.currentConversation();
        if (ref == null) {
            screen = Screen.LIST;
            return;
        }
        if (screen == Screen.CHAT && chatKey != null) {
            saveDraft();
        }
        String key = keyFor(ref);
        chatKey = key;
        screen = Screen.CHAT;
        chatFocus = ChatFocus.MESSAGE;
        chatScroll = 0;
        msgScroll = 0;
        ensureTranscript(key);
        String draft = drafts.get(key);
        if (draft != null) {
            messageEditor.setText(draft);
            addInfo("已恢复未发送的草稿。");
        } else {
            messageEditor.clear();
        }
        unread.remove(key);
        dirty = true;
    }

    private void openServerLogin() {
        screen = Screen.SERVER_LOGIN;
        buildAuthForm(false);
        dirty = true;
    }

    private void openServerRegister() {
        // 从登录表单进入注册时，保留已填写的服务器地址与用户名，避免用户重填；
        // 密码不保留（安全考虑，也避免误以为注册会复用登录密码）。
        String address = formFields.size() > 0 ? formFields.get(0).editor.text() : "";
        String user = formFields.size() > 1 ? formFields.get(1).editor.text() : "";
        screen = Screen.SERVER_REGISTER;
        buildAuthForm(true);
        if (!address.isBlank()) {
            formFields.get(0).editor.setText(address);
        }
        if (!user.isBlank()) {
            formFields.get(1).editor.setText(user);
        }
        dirty = true;
    }

    private void buildAuthForm(boolean register) {
        formFields.clear();
        formActions.clear();
        String address = app.server() != null && app.server().serverAddress() != null
                ? app.server().serverAddress() : lastServerAddress;
        Field addr = new Field("服务器地址", false);
        addr.editor.setText(address);
        formFields.add(addr);
        formFields.add(new Field("用户名（@开头；输入时自动补全）", false));
        formFields.add(new Field("密码", true));
        if (register) {
            formFields.add(new Field("邮箱（可选）", false));
        }
        formActions.add(new FormAction("返回", this::backToModeMenu));
        if (!register) {
            formActions.add(0, new FormAction("注册新账户", this::openServerRegister));
        }
        formSubmit = register ? this::submitRegistration : this::submitServerLogin;
        formFocus = 0;
        commandEditor.clear();
    }

    private void backToModeMenu() {
        if (app.mode() != SessionMode.NONE) {
            app.leaveMode();
        }
        screen = Screen.MODE_MENU;
        dirty = true;
    }

    private void openLocalLogin() {
        screen = Screen.LOCAL_LOGIN;
        formFields.clear();
        formActions.clear();
        formFields.add(new Field("本地用户名", false));
        formActions.add(new FormAction("返回", this::backToModeMenu));
        formSubmit = this::submitLocalLogin;
        formFocus = 0;
        commandEditor.clear();
        dirty = true;
    }

    private void openAddContact() {
        screen = Screen.ADD_CONTACT;
        formFields.clear();
        formActions.clear();
        formFields.add(new Field("好友用户名", false));
        formActions.add(new FormAction("返回", this::openList));
        formSubmit = this::submitAddContact;
        formFocus = 0;
        commandEditor.clear();
        dirty = true;
    }

    private void openConnectAddress() {
        screen = Screen.CONNECT_ADDRESS;
        formFields.clear();
        formActions.clear();
        formFields.add(new Field("地址（host:port）", false));
        formActions.add(new FormAction("返回", this::openList));
        formSubmit = this::submitConnectAddress;
        formFocus = 0;
        commandEditor.clear();
        dirty = true;
    }

    private void openSettings() {
        screen = Screen.SETTINGS;
        focus = 0;
        commandEditor.clear();
        dirty = true;
    }

    private String lastServerAddress = "";

    private void submitServerLogin() {
        String address = formFields.get(0).editor.text().trim();
        String user = normalizeServerUser(formFields.get(1).editor.text());
        String password = formFields.get(2).editor.text();
        if (address.isBlank()) {
            addInfo("请填写服务器地址（host:port）。");
            formFocus = 0;
            return;
        }
        if (user.isBlank() || password.isEmpty()) {
            addInfo("用户名与密码不能为空。");
            return;
        }
        try {
            if (app.mode() == SessionMode.NONE) {
                app.connectServer(address);
                lastServerAddress = address;
            } else if (app.mode() != SessionMode.SERVER || app.server() == null) {
                addInfo("当前不在服务器模式入口；请先返回主菜单。");
                return;
            }
            app.server().login(user, password);
            formFields.get(2).editor.clear();
            addInfo("登录请求已发送，等待服务器响应……");
        } catch (Errors.OcException e) {
            addInfo("错误：" + e.getMessage());
        }
    }

    private void submitRegistration() {
        String address = formFields.get(0).editor.text().trim();
        String user = normalizeServerUser(formFields.get(1).editor.text());
        String password = formFields.get(2).editor.text();
        String email = formFields.get(3).editor.text().trim();
        if (address.isBlank() || user.isBlank() || password.isEmpty()) {
            addInfo("服务器地址、用户名与密码不能为空。");
            return;
        }
        try {
            if (app.mode() == SessionMode.NONE) {
                app.connectServer(address);
                lastServerAddress = address;
            } else if (app.mode() != SessionMode.SERVER || app.server() == null) {
                addInfo("当前不在服务器模式入口；请先返回主菜单。");
                return;
            }
            Map<String, String> fields = new java.util.LinkedHashMap<>();
            if (!email.isBlank()) {
                fields.put("@Email", email);
            }
            app.server().register(user, password, fields);
            formFields.get(2).editor.clear();
            addInfo("注册请求已发送，等待服务器审批/响应……");
        } catch (Errors.OcException e) {
            addInfo("错误：" + e.getMessage());
        }
    }

    private static String normalizeServerUser(String raw) {
        String user = raw == null ? "" : raw.trim();
        if (user.isEmpty()) {
            return "";
        }
        return user.startsWith("@") ? user : "@" + user;
    }

    private void submitLocalLogin() {
        String name = formFields.get(0).editor.text().trim();
        if (name.isEmpty()) {
            addInfo("本地用户名不能为空。");
            return;
        }
        try {
            app.enterLocal(name);
            openList();
        } catch (Errors.OcException e) {
            addInfo("错误：" + e.getMessage());
        }
    }

    private void submitAddContact() {
        String name = formFields.get(0).editor.text().trim();
        if (name.isEmpty()) {
            addInfo("请输入好友用户名。");
            return;
        }
        try {
            boolean delivered = app.sendFriendRequest(name);
            addInfo(delivered
                    ? "已向 " + name + " 发送好友请求（发送不等于送达）。"
                    : "对 " + name + " 的好友请求无法送达（对端不可达）。");
            openList();
        } catch (Errors.OcException e) {
            addInfo("错误：" + e.getMessage());
        }
    }

    private void submitConnectAddress() {
        String address = formFields.get(0).editor.text().trim();
        int colon = address.lastIndexOf(':');
        if (colon <= 0 || colon == address.length() - 1) {
            addInfo("地址格式须为 host:port。");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(address.substring(colon + 1));
        } catch (NumberFormatException e) {
            addInfo("端口必须是数字。");
            return;
        }
        try {
            app.local().connect(address.substring(0, colon), port);
            addInfo("已向 " + address + " 发起连接尝试。");
            openList();
        } catch (Errors.OcException e) {
            addInfo("错误：" + e.getMessage());
        }
    }

    private void beginExit() {
        String key = currentKeyOrNull();
        boolean buffered = key != null && app.hasBuffered(key);
        ExitPolicy.Decision decision = ExitPolicy.decide(buffered, true);
        boolean hasDraft = !drafts.isEmpty()
                || (chatKey != null && !messageEditor.text().isBlank());
        if (!decision.promptNeeded() && !hasDraft) {
            app.requestExit();
            return;
        }
        exitPrompt = true;
        if (hasDraft) {
            // 草稿只保存在本次运行的内存中；退出前明确告知会丢失，不静默丢弃。
            exitQuestion = (decision.promptNeeded() ? decision.message() + "；" : "")
                    + "存在未发送草稿，退出后将丢失。按 y 退出、c 取消";
        } else {
            exitQuestion = decision.message();
        }
        addInfo(exitQuestion);
    }

    private void openHelp() {
        helpReturnScreen = screen;
        helpReturnFocus = focus;
        helpReturnChatFocus = chatFocus;
        helpLines.clear();
        helpLines.addAll(HelpContent.build(dispatcher, context(),
                KeyReader.terminalDescription(terminal.getType()),
                app.config().shiftEnterToSend(), dataDir.toString(),
                app.config().effectiveTerminalMode()));
        screen = Screen.HELP;
        helpScroll = 0;
        commandEditor.clear();
        dirty = true;
    }

    private void closeHelp() {
        screen = helpReturnScreen;
        focus = helpReturnFocus;
        chatFocus = helpReturnChatFocus;
        dirty = true;
    }

    private int maxHelpScroll() {
        return Math.max(0, wrappedHelp().size() - Math.max(1, height - 5));
    }

    // ================= 业务数据辅助 =================

    private String keyFor(ConversationRef ref) {
        if (ref == null) {
            return null;
        }
        if (app.mode() == SessionMode.LOCAL && app.local() != null) {
            return app.local().convKey(ref.id());
        }
        if (app.mode() == SessionMode.SERVER && app.server() != null) {
            return app.server().convKey(ref);
        }
        return null;
    }

    private String currentKeyOrNull() {
        return keyFor(app.currentConversation());
    }

    private List<Row> currentRows() {
        List<Row> rows = new ArrayList<>();
        if (app.mode() == SessionMode.SERVER) {
            for (ConversationRef ref : app.conversations()) {
                rows.add(new Row(serverRowLabel(ref), serverRowRight(ref), RowKind.CONVERSATION, ref));
            }
            rows.add(new Row("通知", notificationsBadge(), RowKind.NOTIFICATIONS, null));
        } else if (app.mode() == SessionMode.LOCAL) {
            for (ConversationRef ref : app.conversations()) {
                rows.add(new Row(localRowLabel(ref), localRowRight(ref), RowKind.CONVERSATION, ref));
            }
            rows.add(new Row("添加联系人", "", RowKind.ADD_CONTACT, null));
            rows.add(new Row("连接到地址", "", RowKind.CONNECT_ADDRESS, null));
            rows.add(new Row("设置", "", RowKind.SETTINGS, null));
            rows.add(new Row("通知", notificationsBadge(), RowKind.NOTIFICATIONS, null));
        }
        return rows;
    }

    private String serverRowLabel(ConversationRef ref) {
        String label;
        if (ref.kind() == ConversationRef.Kind.CHANNEL) {
            String name = app.server() == null || app.server().serverName() == null
                    ? "服务器" : app.server().serverName();
            label = name + " · 官方公告";
        } else {
            label = ref.title();
        }
        Integer pending = unread.get(keyFor(ref));
        if (pending != null && pending > 0) {
            label += "  [" + pending + "]";
        }
        return label;
    }

    private String localRowLabel(ConversationRef ref) {
        String label = ref.title();
        Integer pending = unread.get(keyFor(ref));
        if (pending != null && pending > 0) {
            label += "  [" + pending + "]";
        }
        return label;
    }

    private String serverRowRight(ConversationRef ref) {
        return switch (ref.kind()) {
            case PRIVATE -> statusText(app.peerStatus(ref.id()));
            case GROUP -> ref.privacy() == org.ourcamellia.common.domain.GroupPrivacy.ENCRYPTED
                    ? "加密群聊" : "运营者可见";
            case CHANNEL -> "只读";
        };
    }

    private String localRowRight(ConversationRef ref) {
        String endpoint = app.local() == null ? null : app.local().peerEndpoint(ref.id());
        String address = endpoint == null ? "地址未知" : endpoint;
        return address + "   " + statusText(app.peerStatus(ref.id()));
    }

    private String notificationsBadge() {
        int n = safeNotifications().size();
        return n == 0 ? "无" : n + " 条";
    }

    private static String statusText(PeerStatus status) {
        return switch (status) {
            case CONNECTED -> "● 在线";
            case DISCOVERED -> "◐ 可被发现";
            case UNREACHABLE -> "○ 不可达";
            case UNCONFIRMED -> "? 未知";
        };
    }

    private String meLine() {
        if (app.mode() == SessionMode.LOCAL && app.local() != null) {
            return "我：" + app.local().myName() + "  ● 在线";
        }
        if (app.mode() == SessionMode.SERVER && app.server() != null) {
            String user = app.server().loggedInUser();
            return "我：" + (user == null ? "（未登录）" : user)
                    + (user == null ? "" : "  ● 在线");
        }
        return "我：（未登录）";
    }

    private String pageTitle() {
        return switch (screen) {
            case MODE_MENU -> "连接方式";
            case SERVER_LOGIN -> "服务器登录";
            case SERVER_REGISTER -> "服务器注册";
            case LOCAL_LOGIN -> "局域网登录";
            case LIST -> {
                if (app.mode() == SessionMode.LOCAL) {
                    yield "本地@" + localAddress;
                }
                String name = app.server() == null || app.server().serverName() == null
                        ? "（等待服务器名称）" : app.server().serverName();
                yield "服务器@" + name;
            }
            case CHAT -> {
                ConversationRef ref = app.currentConversation();
                if (ref == null) {
                    yield "";
                }
                yield switch (ref.kind()) {
                    case GROUP -> ref.title();
                    case CHANNEL -> "官方公告";
                    case PRIVATE -> ""; // 私聊不显示单独标题
                };
            }
            case NOTIFICATIONS, NOTIFICATION_DETAIL, NOTIFICATION_ACTION -> "通知";
            case SETTINGS -> "设置";
            case ADD_CONTACT -> "添加联系人";
            case CONNECT_ADDRESS -> "连接到地址";
            case HELP -> "帮助";
        };
    }

    private void ensureTranscript(String key) {
        if (key == null || transcripts.containsKey(key)) {
            return;
        }
        List<ChatEntry> list = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try {
            for (LocalHistoryStore.Entry e : app.history().messages(key)) {
                list.add(new ChatEntry(e.id(), e.sender(), e.text(), e.ts(), false,
                        "out".equals(e.direction())));
                ids.add(e.id());
            }
        } catch (Errors.OcException e) {
            addInfo("无法读取聊天记录：" + e.getMessage());
        }
        transcripts.put(key, list);
        messageIds.put(key, ids);
    }

    private void handleIncomingMessage(ConversationRef conversation, Message message) {
        String key = keyFor(conversation);
        if (key == null) {
            return;
        }
        ensureTranscript(key);
        boolean self = isSelfSender(message.sender());
        // 无论当前是否打开该会话，都先记录到内存视图；否则录制关闭时，
        // 非当前会话的消息会在切换后消失。
        appendEntry(key, new ChatEntry(message.id(), message.sender(), message.text(),
                message.ts(), false, self));
        String currentKey = currentKeyOrNull();
        if (!key.equals(currentKey) && !self) {
            unread.merge(key, 1, Integer::sum);
        } else if (chatScroll == 0) {
            chatScroll = 0; // 停在底部时继续跟随最新消息
        }
        dirty = true;
    }

    private boolean isSelfSender(String sender) {
        if (sender == null) {
            return false;
        }
        if (app.mode() == SessionMode.LOCAL && app.local() != null) {
            return sender.equals(app.local().myName());
        }
        if (app.mode() == SessionMode.SERVER && app.server() != null) {
            return sender.equals(app.server().loggedInUser());
        }
        return false;
    }

    private void appendEntry(String key, ChatEntry entry) {
        List<ChatEntry> list = transcripts.computeIfAbsent(key, k -> new ArrayList<>());
        Set<String> ids = messageIds.computeIfAbsent(key, k -> new HashSet<>());
        if (entry.id() != null && !ids.add(entry.id())) {
            return; // 重复投递
        }
        list.add(entry);
        while (list.size() > MAX_TRANSCRIPT_ENTRIES) {
            ChatEntry removed = list.remove(0);
            if (removed.id() != null) {
                ids.remove(removed.id());
            }
        }
    }

    private void saveDraft() {
        String key = chatKey;
        if (key == null) {
            return;
        }
        if (messageEditor.text().isBlank()) {
            drafts.remove(key);
        } else {
            drafts.put(key, messageEditor.text());
        }
    }

    private void addInfo(String text) {
        String safe = Text.renderable(text == null ? "" : text);
        info.add(safe);
        while (info.size() > MAX_INFO_LINES) {
            info.remove(0);
        }
        if (screen == Screen.CHAT) {
            String key = currentKeyOrNull();
            if (key != null) {
                appendEntry(key, new ChatEntry(null, null, safe,
                        System.currentTimeMillis(), true, false));
            }
        }
        dirty = true;
    }

    private String infoLine() {
        int n = notificationCache.size();
        String last = info.isEmpty() ? "" : info.get(info.size() - 1);
        String base = "[INFO] " + n + " 条通知";
        if (!last.isBlank()) {
            base += "  ·  " + last;
        }
        return base;
    }

    private String recordLine(String key) {
        if (key == null) {
            return "记录：未知";
        }
        boolean recording = app.recording(key);
        int buffered = app.history().bufferedCount(key);
        return "记录：" + (recording ? "已开启" : "已关闭")
                + (buffered > 0 ? "（" + buffered + " 条待持久化）" : "");
    }

    private String privacyText(ConversationRef ref) {
        if (ref.privacy() == null) {
            return "";
        }
        return ref.privacy() == org.ourcamellia.common.domain.GroupPrivacy.ENCRYPTED
                ? "加密群聊" : "运营者可见";
    }

    // ================= 渲染：入口 =================

    private int computeWidth() {
        try {
            return Math.max(1, terminal.getWidth());
        } catch (RuntimeException e) {
            return width;
        }
    }

    private int computeHeight() {
        try {
            return Math.max(1, terminal.getHeight());
        } catch (RuntimeException e) {
            return height;
        }
    }

    private void renderSafely() {
        try {
            render();
            renderFailures = 0;
            dirty = false;
        } catch (RuntimeException e) {
            diagnose("整屏渲染", e);
            dirty = false;
            if (++renderFailures >= MAX_RENDER_FAILURES) {
                System.err.println("终端连续渲染失败，退出以便诊断：");
                e.printStackTrace(System.err);
                app.requestExit();
            }
        }
    }

    private void render() {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        Frame frame = buildFrame(w, h);
        StringBuilder sb = new StringBuilder(w * h + 64);
        if (firstRender) {
            sb.append("\u001b[2J");
            firstRender = false;
        }
        sb.append("\u001b[?25l\u001b[?2026h\u001b[H");
        for (int i = 0; i < h; i++) {
            sb.append("\u001b[2K");
            String row = i < frame.rows.size() ? frame.rows.get(i) : "";
            sb.append(UiText.truncate(row, w));
            if (i < h - 1) {
                sb.append("\r\n");
            }
        }
        sb.append("\u001b[0m");
        if (frame.cursorVisible) {
            int row = Math.min(Math.max(1, frame.cursorRow + 1), h);
            int col = Math.min(Math.max(1, frame.cursorCol + 1), w);
            sb.append("\u001b[").append(row).append(';').append(col).append('H');
            sb.append("\u001b[?25h");
        }
        sb.append("\u001b[?2026l");
        writer.print(sb);
        writer.flush();
        firstRender = false;
    }

    private void diagnose(String what, RuntimeException e) {
        System.err.println("[" + what + "] 渲染异常 (width=" + width + ", height=" + height + "): " + e);
        e.printStackTrace(System.err);
    }

    // ================= 渲染：页面构建 =================

    private Frame buildFrame(int w, int h) {
        if (promptActive) {
            return fit(buildPrompt(w, h), w, h);
        }
        if (screen == Screen.HELP) {
            return fit(buildHelp(w, h), w, h);
        }
        if (h < 14 || w < 30 || (screen == Screen.CHAT && h < 20)) {
            return fit(buildCompact(w, h), w, h);
        }
        return switch (screen) {
            case MODE_MENU -> fit(buildModeMenu(w, h), w, h);
            case LIST -> fit(buildList(w, h), w, h);
            case CHAT -> fit(buildChat(w, h), w, h);
            case NOTIFICATIONS -> fit(buildNotifications(w, h), w, h);
            case NOTIFICATION_DETAIL -> fit(buildNotificationDetail(w, h), w, h);
            case NOTIFICATION_ACTION -> fit(buildNotificationAction(w, h), w, h);
            case SETTINGS -> fit(buildSettings(w, h), w, h);
            case SERVER_LOGIN, SERVER_REGISTER, LOCAL_LOGIN, ADD_CONTACT, CONNECT_ADDRESS ->
                    fit(buildForm(w, h), w, h);
            default -> fit(buildModeMenu(w, h), w, h);
        };
    }

    /** 截断/补齐到恰好 h 行，并钳制光标位置。 */
    private Frame fit(Frame f, int w, int h) {
        while (f.rows.size() < h) {
            f.rows.add("");
        }
        // 行数超出时保留最后一行（底部 = 分隔线），从倒数第二行开始裁剪，
        // 避免极端小窗口把底边裁掉。
        while (f.rows.size() > h && f.rows.size() > 1) {
            f.rows.remove(f.rows.size() - 2);
        }
        if (f.cursorRow >= h) {
            f.cursorRow = h - 1;
        }
        if (f.cursorCol >= w) {
            f.cursorCol = Math.max(0, w - 1);
        }
        return f;
    }

    private Frame buildModeMenu(int w, int h) {
        Frame f = new Frame();
        f.rows.add(UiText.line('=', w));
        f.rows.add(fitText("连接方式", w));
        f.rows.add(UiText.line('-', w));
        int head = f.rows.size();
        int tail = tailSize();
        int area = Math.max(0, h - head - tail);
        String[] items = {"服务器模式", "局域网模式", "退出程序"};
        for (int i = 0; i < area; i++) {
            if (i == 0) {
                f.rows.add("");
            } else if (i - 1 < items.length) {
                int idx = i - 1;
                f.rows.add(menuLine(idx == focus, items[idx], w));
            } else {
                f.rows.add("");
            }
        }
        addTail(f, w, "↑↓ 选择    Enter 确认    Esc 返回", true);
        return f;
    }

    private Frame buildList(int w, int h) {
        Frame f = new Frame();
        f.rows.add(UiText.line('=', w));
        f.rows.add(fitText(pageTitle(), w));
        f.rows.add(fitText(meLine(), w));
        f.rows.add(UiText.line('-', w));
        f.rows.add(fitText("会话列表", w));
        f.rows.add("");
        int head = f.rows.size();
        int tail = tailSize();
        int area = Math.max(0, h - head - tail);
        List<Row> rows = currentRows();
        if (rows.isEmpty()) {
            f.rows.add("（暂无会话。可使用命令或下面的操作。）");
            fillTo(f, h - tailSize());
        } else {
            focus = Math.min(focus, rows.size() - 1);
            if (focus < listScroll) {
                listScroll = focus;
            }
            if (focus >= listScroll + Math.max(1, area)) {
                listScroll = focus - Math.max(1, area) + 1;
            }
            int maxScroll = Math.max(0, rows.size() - Math.max(1, area));
            listScroll = Math.min(listScroll, maxScroll);
            for (int i = 0; i < area; i++) {
                int idx = listScroll + i;
                if (idx < rows.size()) {
                    Row row = rows.get(idx);
                    String prefix = idx == focus ? "> " : "  ";
                    String content = row.right() == null || row.right().isBlank()
                            ? row.label()
                            : UiText.leftRight(row.label(), row.right(), Math.max(1, w - 2));
                    f.rows.add(prefix + content);
                } else {
                    f.rows.add("");
                }
            }
        }
        addTail(f, w, listHint(), true);
        return f;
    }

    private String listHint() {
        if (app.mode() == SessionMode.LOCAL) {
            return "↑↓ 选择    Enter 打开    I 通知    Esc 返回";
        }
        return "↑↓ 选择    Enter 打开    I 通知    Esc 返回";
    }

    private Frame buildChat(int w, int h) {
        Frame f = new Frame();
        ConversationRef ref = app.currentConversation();
        if (ref == null) {
            return buildList(w, h);
        }
        String key = chatKey != null ? chatKey : keyFor(ref);
        f.rows.add(UiText.line('=', w));
        if (ref.kind() == ConversationRef.Kind.GROUP) {
            f.rows.add(fitText(ref.title(), w));
            f.rows.add(fitText("我的身份：" + serverUser(), w));
            f.rows.add(fitText("成员：" + groupMembers(ref), w));
            f.rows.add(fitText("隐私：" + (privacyText(ref).isBlank() ? "未知" : privacyText(ref)), w));
            f.rows.add(fitText(recordLine(key), w));
        } else if (ref.kind() == ConversationRef.Kind.CHANNEL) {
            f.rows.add(fitText(meLine(), w));
            f.rows.add(fitText("频道：官方公告（只读）", w));
            f.rows.add(fitText(recordLine(key), w));
        } else {
            f.rows.add(fitText(meLine(), w));
            f.rows.add(fitText("对方：" + ref.title() + "  " + statusText(app.peerStatus(ref.id())), w));
            f.rows.add(fitText(recordLine(key), w));
        }
        f.rows.add(UiText.line('-', w));
        f.rows.add(fitText("消息", w));
        int head = f.rows.size();

        int editorRows = h >= 20 ? 3 : 1;
        int tail = editorRows + 8; // -、消息输入、(行)、-、命令、cmd、-、提示、=
        int transcriptRows = Math.max(1, h - head - tail);

        List<String> transcript = transcriptLines(key, w);
        int maxScroll = Math.max(0, transcript.size() - transcriptRows);
        chatScroll = Math.min(chatScroll, maxScroll);
        int start = Math.max(0, transcript.size() - transcriptRows - chatScroll);
        for (int i = 0; i < transcriptRows; i++) {
            int idx = start + i;
            f.rows.add(idx < transcript.size() ? transcript.get(idx) : "");
        }

        f.rows.add(UiText.line('-', w));
        f.rows.add(fitText("消息输入", w));
        Visual visual = layoutEditor(messageEditor, Math.max(1, w - 2));
        int maxMsgScroll = Math.max(0, visual.lines.size() - editorRows);
        if (visual.cursorLine < msgScroll) {
            msgScroll = visual.cursorLine;
        }
        if (visual.cursorLine >= msgScroll + editorRows) {
            msgScroll = visual.cursorLine - editorRows + 1;
        }
        msgScroll = Math.min(Math.max(0, msgScroll), maxMsgScroll);
        int editorStartRow = f.rows.size();
        for (int i = 0; i < editorRows; i++) {
            int idx = msgScroll + i;
            String text = idx < visual.lines.size() ? visual.lines.get(idx) : "";
            String prefix = i == 0 ? "> " : "  ";
            f.rows.add(prefix + text);
        }
        if (chatFocus == ChatFocus.MESSAGE) {
            f.cursorVisible = true;
            f.cursorRow = editorStartRow + (visual.cursorLine - msgScroll);
            f.cursorCol = 2 + visual.cursorCol;
        }

        f.rows.add(UiText.line('-', w));
        f.rows.add(fitText("命令", w));
        int cmdRow = f.rows.size();
        String cmdLine = commandLine(w);
        f.rows.add(cmdLine);
        if (chatFocus == ChatFocus.COMMAND) {
            f.cursorVisible = true;
            f.cursorRow = cmdRow;
            f.cursorCol = commandCursorCol;
        }
        f.rows.add(UiText.line('-', w));
        f.rows.add(fitText(exitPrompt ? exitQuestion : ChatInput.hint(app.config().shiftEnterToSend()), w));
        f.rows.add(UiText.line('=', w));
        return f;
    }

    private String serverUser() {
        if (app.server() == null || app.server().loggedInUser() == null) {
            return "（未登录）";
        }
        return app.server().loggedInUser();
    }

    private String groupMembers(ConversationRef ref) {
        if (app.server() == null) {
            return "未知";
        }
        var group = app.server().group(ref.id());
        if (group == null || group.members == null) {
            return "未知";
        }
        // 服务器的 GroupInfo 把群主与普通成员分开保存：members 不包含 owner。
        // 界面显示的是“成员总数”，因此需要把群主计入，否则只会显示 0。
        int total = group.members.size() + (group.owner == null ? 0 : 1);
        return String.valueOf(total);
    }

    private Frame buildNotifications(int w, int h) {
        Frame f = new Frame();
        f.rows.add(UiText.line('=', w));
        f.rows.add(fitText("通知", w));
        f.rows.add(UiText.line('-', w));
        f.rows.add(fitText("活动通知", w));
        int head = f.rows.size();
        int tail = tailSize();
        int area = Math.max(0, h - head - tail);
        if (notificationCache.isEmpty()) {
            f.rows.add("当前无活动通知。");
            for (int i = 1; i < area; i++) {
                f.rows.add("");
            }
        } else {
            focus = Math.min(focus, notificationCache.size() - 1);
            // 每项占 3 行（标题、详情、空行），按项滚动保证焦点可见。
            int perItem = 3;
            int visible = Math.max(1, area / perItem);
            if (focus < notificationScroll) {
                notificationScroll = focus;
            }
            if (focus >= notificationScroll + visible) {
                notificationScroll = focus - visible + 1;
            }
            int maxScroll = Math.max(0, notificationCache.size() - visible);
            notificationScroll = Math.min(notificationScroll, maxScroll);
            int used = 0;
            for (int i = notificationScroll; i < notificationCache.size() && used < area; i++) {
                Notification n = notificationCache.get(i);
                boolean selected = i == focus;
                String headLine = (selected ? "> " : "  ") + (i + 1) + ". [" + kindLabel(n.kind()) + "] " + n.from();
                f.rows.add(fitText(headLine, w));
                used++;
                if (used < area) {
                    f.rows.add(fitText("       " + n.text(), w));
                    used++;
                }
                if (used < area) {
                    String actions = n.kind() == NotificationKind.FRIEND_REQUEST
                            ? "       操作：通过 / 拒绝" : "       操作：标记已读";
                    f.rows.add(fitText(actions, w));
                    used++;
                }
            }
            while (used < area) {
                f.rows.add("");
                used++;
            }
        }
        addTail(f, w, "↑↓ 选择    Enter 查看    Esc 返回", true);
        return f;
    }

    private Frame buildNotificationDetail(int w, int h) {
        Frame f = new Frame();
        f.rows.add(UiText.line('=', w));
        f.rows.add(fitText("通知", w));
        f.rows.add(UiText.line('-', w));
        if (notificationIndex < 0 || notificationIndex >= notificationCache.size()) {
            f.rows.add("该通知已不存在。");
            f.rows.add("");
            f.rows.add(menuLine(focus == 0, "返回", w));
        } else {
            Notification n = notificationCache.get(notificationIndex);
            f.rows.add(fitText("[" + kindLabel(n.kind()) + "] 来自 " + n.from(), w));
            f.rows.add("");
            for (String line : UiText.wrap(n.text(), Math.max(1, w - 2))) {
                f.rows.add("  " + line);
            }
            f.rows.add("");
            f.rows.add(menuLine(focus == 0, "标记已读并移除", w));
            f.rows.add(menuLine(focus == 1, "返回", w));
        }
        fillTo(f, h - tailSize());
        addTail(f, w, "↑↓ 选择    Enter 确认    Esc 返回", true);
        return f;
    }

    private Frame buildNotificationAction(int w, int h) {
        Frame f = new Frame();
        f.rows.add(UiText.line('=', w));
        f.rows.add(fitText("通知", w));
        f.rows.add(UiText.line('-', w));
        if (notificationIndex < 0 || notificationIndex >= notificationCache.size()) {
            f.rows.add("该通知已不存在。");
            f.rows.add(menuLine(focus == 0, "返回", w));
        } else {
            Notification n = notificationCache.get(notificationIndex);
            f.rows.add(fitText("[" + kindLabel(n.kind()) + "] 来自 " + n.from(), w));
            for (String line : UiText.wrap(n.text(), Math.max(1, w - 2))) {
                f.rows.add("  " + line);
            }
            f.rows.add("");
            f.rows.add(menuLine(focus == 0, "通过", w));
            f.rows.add(menuLine(focus == 1, "拒绝", w));
            f.rows.add(menuLine(focus == 2, "返回", w));
        }
        fillTo(f, h - tailSize());
        addTail(f, w, "↑↓ 选择    Enter 确认    Esc 返回", true);
        return f;
    }

    private Frame buildSettings(int w, int h) {
        Frame f = new Frame();
        f.rows.add(UiText.line('=', w));
        f.rows.add(fitText("设置", w));
        f.rows.add(UiText.line('-', w));
        f.rows.add("");
        f.rows.add(menuLine(focus == 0,
                "可被发现：" + (app.discoverable() ? "开启" : "关闭"), w));
        f.rows.add(menuLine(focus == 1,
                "可连接：" + (app.connectable() ? "开启" : "关闭"), w));
        f.rows.add(menuLine(focus == 2, "返回", w));
        f.rows.add("");
        f.rows.add(fitText("说明：可被发现控制是否响应局域网发现；可连接控制是否接受入站连接。", w));
        fillTo(f, h - tailSize());
        addTail(f, w, "↑↓ 选择    Enter 切换    Esc 返回", true);
        return f;
    }

    private Frame buildForm(int w, int h) {
        Frame f = new Frame();
        f.rows.add(UiText.line('=', w));
        f.rows.add(fitText(pageTitle(), w));
        f.rows.add(UiText.line('-', w));
        int head = f.rows.size();
        int tail = tailSize();
        int area = Math.max(0, h - head - tail);

        int commandIndex = formFields.size() + formActions.size();
        formFocus = Math.min(formFocus, commandIndex);

        // 先计算完整表单行；如果放不下，只围绕当前字段显示。
        List<String> body = new ArrayList<>();
        List<Integer> fieldRows = new ArrayList<>();
        for (int i = 0; i < formFields.size(); i++) {
            Field field = formFields.get(i);
            fieldRows.add(body.size());
            boolean active = formFocus == i;
            if (active) {
                formFocus = i;
            }
            body.add("  " + field.label);
            body.add(fieldLine(i, w));            body.add("");
        }
        if (!formActions.isEmpty()) {
            body.add("");
            for (int i = 0; i < formActions.size(); i++) {
                boolean active = formFocus == formFields.size() + i;
                body.add(menuLine(active, formActions.get(i).label(), w));
            }
        }
        int start = 0;
        if (body.size() > area) {
            int focusRow = formFocus < fieldRows.size()
                    ? fieldRows.get(formFocus) : Math.max(0, body.size() - formActions.size() - 1);
            start = Math.max(0, focusRow - 1);
            start = Math.min(start, Math.max(0, body.size() - area));
        }
        for (int i = 0; i < area; i++) {
            int idx = start + i;
            f.rows.add(idx < body.size() ? body.get(idx) : "");
        }
        // 活动字段的光标位置。
        if (formFocus < formFields.size()) {
            Field field = formFields.get(formFocus);
            int bodyRow = fieldRows.get(formFocus) + 1;
            int displayRow = head + (bodyRow - start);
            if (displayRow >= head && displayRow < h - tail) {
                f.cursorVisible = true;
                f.cursorRow = displayRow;
                f.cursorCol = fieldCursorCol;
            }
        }
        addTail(f, w, "Tab 下一字段/命令栏    Enter 确认    Esc 返回", true);
        return f;
    }

    private Frame buildPrompt(int w, int h) {
        Frame f = new Frame();
        f.rows.add(UiText.line('=', w));
        f.rows.add(fitText("输入", w));
        f.rows.add(UiText.line('-', w));
        f.rows.add(fitText(promptLabel, w));
        f.rows.add("");
        String label = "> ";
        String text = promptEditor.text();
        String shown = promptSecret ? UiText.mask(text) : text;
        int col = UiText.displayWidth(promptSecret ? UiText.mask(text.substring(0, promptEditor.cursor())) : text.substring(0, promptEditor.cursor()));
        int avail = Math.max(1, w - label.length());
        int startCol = Math.max(0, col - avail + 1);
        f.rows.add(label + UiText.slice(shown, startCol, avail));
        fillTo(f, Math.max(f.rows.size(), h - 3));
        f.rows.add(UiText.line('-', w));
        f.rows.add(fitText("Enter 确认    Esc 取消", w));
        f.rows.add(UiText.line('=', w));
        f.cursorVisible = true;
        f.cursorRow = 5;
        f.cursorCol = 2 + (col - startCol);
        return f;
    }

    private Frame buildHelp(int w, int h) {
        Frame f = new Frame();
        f.rows.add(UiText.line('=', w));
        f.rows.add(fitText("帮助", w));
        f.rows.add(UiText.line('-', w));
        List<String> wrapped = wrappedHelp(w);
        int content = Math.max(1, h - 5);
        int maxScroll = Math.max(0, wrapped.size() - content);
        helpScroll = Math.min(Math.max(0, helpScroll), maxScroll);
        for (int i = 0; i < content; i++) {
            int idx = helpScroll + i;
            f.rows.add(idx < wrapped.size() ? wrapped.get(idx) : "");
        }
        f.rows.add(UiText.line('-', w));
        f.rows.add(fitText("↑↓/PgUp/PgDn 滚动    Enter 返回（回到打开帮助前的页面）", w));
        f.rows.add(UiText.line('=', w));
        return f;
    }

    private List<String> wrappedHelp() {
        return wrappedHelp(Math.max(1, width));
    }

    private List<String> wrappedHelp(int w) {
        List<String> out = new ArrayList<>();
        for (String line : helpLines) {
            out.addAll(UiText.wrap(line, Math.max(1, w - 2)));
        }
        return out;
    }

    private Frame buildCompact(int w, int h) {
        Frame f = new Frame();
        f.rows.add(UiText.line('=', w));
        f.rows.add(fitText(pageTitle().isBlank() ? (screen == Screen.CHAT ? "聊天" : "界面") : pageTitle(), w));
        f.rows.add(UiText.line('-', w));
        int tail = 5; // 命令、cmd、-、提示、=
        int content = Math.max(0, h - f.rows.size() - tail);
        for (int i = 0; i < content; i++) {
            String line = "";
            if (i == 0) {
                if (screen == Screen.CHAT) {
                    line = "（窗口过小，聊天内容暂时隐藏）";
                } else if (screen == Screen.LIST) {
                    List<Row> rows = currentRows();
                    if (!rows.isEmpty()) {
                        focus = Math.min(focus, rows.size() - 1);
                        line = ("> " + rows.get(focus).label());
                    }
                } else {
                    line = "窗口过小，已切换为紧凑布局。";
                }
            }
            f.rows.add(fitText(line, w));
        }
        f.rows.add(fitText("命令", w));
        int cmdRow = f.rows.size();
        f.rows.add(commandLine(w));
        f.rows.add(UiText.line('-', w));
        f.rows.add(fitText(exitPrompt ? exitQuestion : "Esc 返回    ${help} 帮助", w));
        f.rows.add(UiText.line('=', w));
        f.cursorVisible = true;
        f.cursorRow = cmdRow;
        f.cursorCol = commandCursorCol;
        return f;
    }

    // ---------------- 渲染辅助 ----------------

    private int commandCursorCol = 2;
    private int fieldCursorCol = 2;

    private int tailSize() {
        return 6 + (info.isEmpty() ? 0 : 1); // info?、-、命令、cmd、-、提示、=
    }

    private void addTail(Frame f, int w, String hint, boolean showInfo) {
        if (showInfo && !info.isEmpty()) {
            f.rows.add(fitText(infoLine(), w));
        }
        f.rows.add(UiText.line('-', w));
        f.rows.add(fitText("命令", w));
        int row = f.rows.size();
        f.rows.add(commandLine(w));
        if (!f.cursorVisible || exitPrompt) {
            f.cursorVisible = true;
            f.cursorRow = row;
            f.cursorCol = commandCursorCol;
        }
        f.rows.add(UiText.line('-', w));
        f.rows.add(fitText(exitPrompt ? exitQuestion : hint, w));
        f.rows.add(UiText.line('=', w));
    }

    private String commandLine(int w) {
        String label = "> ";
        int avail = Math.max(1, w - label.length());
        String text = commandEditor.text();
        int col = UiText.displayWidth(text.substring(0, Math.min(commandEditor.cursor(), text.length())));
        int start = Math.max(0, col - avail + 1);
        String shown = UiText.slice(text, start, avail);
        commandCursorCol = label.length() + (col - start);
        return label + shown;
    }

    private String fieldLine(int index, int w) {
        Field field = formFields.get(index);
        boolean active = formFocus == index;
        String label = active ? "> " : "  ";
        int avail = Math.max(1, w - label.length());
        String text = field.secret ? UiText.mask(field.editor.text()) : field.editor.text();
        int cursorRaw = field.secret
                ? UiText.displayWidth(UiText.mask(field.editor.text().substring(0, Math.min(field.editor.cursor(), field.editor.text().length()))))
                : UiText.displayWidth(field.editor.text().substring(0, Math.min(field.editor.cursor(), field.editor.text().length())));
        int start = Math.max(0, cursorRaw - avail + 1);
        String shown = UiText.slice(text, start, avail);
        if (active) {
            fieldCursorCol = label.length() + Math.max(0, cursorRaw - start);
        }
        return label + shown;
    }

    private String menuLine(boolean selected, String text, int w) {
        return fitText((selected ? "> " : "  ") + text, w);
    }

    private static String fitText(String text, int w) {
        return UiText.truncate(Text.renderable(text == null ? "" : text), Math.max(1, w));
    }

    private static void fillTo(Frame f, int targetRows) {
        while (f.rows.size() < targetRows) {
            f.rows.add("");
        }
    }

    private static String kindLabel(NotificationKind kind) {
        return switch (kind) {
            case FRIEND_REQUEST -> "好友请求";
            case ANNOUNCEMENT -> "服务器公告";
            case INFO -> "通知";
        };
    }

    // ---------------- 编辑器可视化 ----------------

    private record Visual(List<String> lines, int cursorLine, int cursorCol) {}

    /**
     * 把多行编辑器排版为可视行：按显示宽度折行，并计算光标所在的可视行与列。
     * 与 {@link UiText#wrap} 使用同一套宽度规则，保证光标落在正确字符上。
     */
    private static Visual layoutEditor(TextEditor editor, int width) {
        int w = Math.max(1, width);
        List<String> out = new ArrayList<>();
        int cursorLine = 0;
        int cursorCol = 0;
        List<String> logical = editor.lines();
        int cursorLogical = editor.lineIndex();
        int cursorInLine = editor.column();
        for (int li = 0; li < logical.size(); li++) {
            String line = logical.get(li);
            int lineStartVisual = out.size();
            int used = 0;
            StringBuilder current = new StringBuilder();
            int cursorVisualForLine = -1;
            int cursorColForLine = 0;
            int i = 0;
            while (i < line.length()) {
                int cp = line.codePointAt(i);
                int cw = UiText.charWidth(cp);
                if (used + cw > w && current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                    used = 0;
                }
                if (li == cursorLogical && i == cursorInLine) {
                    cursorVisualForLine = out.size();
                    cursorColForLine = used;
                }
                current.appendCodePoint(cp);
                used += cw;
                i += Character.charCount(cp);
            }
            if (li == cursorLogical && (cursorInLine >= line.length()
                    || cursorInLine == line.length())) {
                if (used >= w && !line.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                    used = 0;
                }
                cursorVisualForLine = out.size();
                cursorColForLine = used;
            }
            out.add(current.toString());
            if (li == cursorLogical && cursorVisualForLine >= 0) {
                cursorLine = cursorVisualForLine;
                cursorCol = cursorColForLine;
            }
            // 空行时上面的 out.add 已占一行；lineStartVisual 仅用于可读性。
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return new Visual(out, Math.min(cursorLine, out.size() - 1), cursorCol);
    }

    private List<String> transcriptLines(String key, int w) {
        List<ChatEntry> entries = transcripts.getOrDefault(key, List.of());
        List<String> out = new ArrayList<>();
        if (entries.isEmpty()) {
            out.add("（暂无消息）");
            return out;
        }
        for (ChatEntry entry : entries) {
            if (entry.system()) {
                for (String line : UiText.wrap("· " + entry.text(), Math.max(1, w - 2))) {
                    out.add(line);
                }
                continue;
            }
            String time = TIME_FORMAT.format(Instant.ofEpochMilli(entry.ts()));
            String who = entry.sender() == null ? "" : entry.sender();
            out.add(fitText(time + "  " + who, w));
            for (String line : UiText.wrap(entry.text(), Math.max(1, w - 8))) {
                out.add("        " + line);
            }
            out.add("");
        }
        return out;
    }
}
