package org.ourcamellia.core.app;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Ids;
import org.ourcamellia.common.Text;
import org.ourcamellia.common.config.ClientConfig;
import org.ourcamellia.common.domain.ConversationRef;
import org.ourcamellia.common.domain.FriendState;
import org.ourcamellia.common.domain.Message;
import org.ourcamellia.common.domain.Notification;
import org.ourcamellia.common.domain.NotificationKind;
import org.ourcamellia.common.domain.NotificationState;
import org.ourcamellia.common.domain.PeerStatus;
import org.ourcamellia.common.protocol.Envelope;
import org.ourcamellia.common.protocol.Wire;
import org.ourcamellia.common.protocol.WireMessages;
import org.ourcamellia.core.crypto.GroupCrypto;
import org.ourcamellia.core.persist.ClientStateStore;
import org.ourcamellia.core.persist.LocalHistoryStore;
import org.ourcamellia.core.transport.ConnSession;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-mode client session: one connection to one independent server. Handles
 * login/registration, friendships, private messaging, groups (both privacy modes),
 * notifications, and the announcement channel. Group encryption is performed
 * client-side with {@link GroupCrypto}; the server never sees group keys.
 */
public final class ServerSession implements AutoCloseable {

    private final ClientConfig config;
    private final ClientStateStore store;
    private final LocalHistoryStore history;
    private final AppEvents events;
    private final ClientApp app;

    // connection state
    private volatile ConnSession session;
    private volatile String serverAddress;
    private volatile String serverName;
    private volatile String channelId;
    private volatile String loggedInUser;
    private volatile boolean bot = false;

    // friends + presence
    private final Map<String, FriendEntry> friends = new ConcurrentHashMap<>();
    private final Set<String> pendingOut = ConcurrentHashMap.newKeySet();
    private final List<WireMessages.NotificationDto> notifications = new CopyOnWriteArrayList<>();

    // groups
    private final Map<String, ClientGroup> groups = new ConcurrentHashMap<>();

    /** One group from this client's perspective (public for UI and tests). */
    public static final class ClientGroup {
        public volatile String id;
        public volatile String name;
        public volatile String privacy;
        public volatile String owner;
        public volatile List<String> members = List.of();
        public volatile byte[] groupKey;           // null until unwrapped
        public volatile long generation = 1;
        public volatile boolean rotationPending = false;
        public final Map<String, String> pubKeys = new ConcurrentHashMap<>();
    }

    record FriendEntry(String name, boolean online, boolean bot) {}

    public ServerSession(ClientConfig config, ClientStateStore store, LocalHistoryStore history,
                         AppEvents events, ClientApp app) {
        this.config = config;
        this.store = store;
        this.history = history;
        this.events = events;
        this.app = app;
    }

    // ---- wiring used by ServerConnection ----
    public ClientConfig config() { return config; }
    public ClientStateStore store() { return store; }
    public LocalHistoryStore history() { return history; }
    public AppEvents events() { return events; }
    public ClientApp app() { return app; }

    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    public String serverAddress() { return serverAddress; }
    public String serverName() { return serverName; }
    public String loggedInUser() { return loggedInUser; }
    public String channelId() { return channelId; }
    public boolean isBot() { return bot; }
    public ConnSession session() { return session; }

    // ---- lifecycle ----

    public void connect(String address) {
        Text.requireCleanText(address, "服务器地址", 255);
        int colon = address.lastIndexOf(':');
        if (colon <= 0 || colon == address.length() - 1) {
            throw new Errors.Validation("服务器地址必须为 host:port 格式");
        }
        String host = address.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(address.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new Errors.Validation("服务器地址必须为 host:port 格式");
        }
        ServerConnection.connect(this, host, port, address);
        this.serverAddress = address;
        events.onStatusChanged();
    }

    void setSession(ConnSession s) {
        this.session = s;
    }

    /** 连接已建立：服务器通过 WELCOME 帧告知服务器名称、公告频道、注册策略等。 */
    void setWelcome(String serverName, String channelId, int unread, String policy, String note) {
        this.serverName = serverName;
        this.channelId = channelId;
        events.onInfo("已连接到服务器 '" + serverName + "' —— 注册审批策略：" + policy
                + " —— " + note);
        events.onStatusChanged();
    }

    /** 登入成功：记录当前账户（含 BOT 标记），并刷新状态栏。 */
    void setLoggedIn(String username, boolean bot, int unread) {
        this.loggedInUser = username;
        this.bot = bot;
        events.onInfo("已登录为 " + username + (bot ? " [BOT]" : ""));
        events.onStatusChanged();
    }

    /** 连接关闭/登出：清理会话内状态（好友、通知），并上报断开原因供用户查看。 */
    void onClosed(String reason) {
        loggedInUser = null;
        friends.clear();
        notifications.clear();
        events.onInfo("已断开与服务器的连接：" + reason);
        events.onStatusChanged();
    }

    // ---- operations ----

    public void register(String username, String password, Map<String, String> fields) {
        ensureConnected();
        Text.requireServerName(username);
        byte[] pub = GroupCrypto.publicKeyBytes(store.x25519KeyPair());
        session.conn().send(Wire.register(username, password, Ids.newId(), fields,
                Base64.getEncoder().encodeToString(pub)));
    }

    public void login(String username, String password) {
        ensureConnected();
        Text.requireServerName(username);
        session.conn().send(Wire.login(username, password));
    }

    /** Reconnects automatically when the transport session was closed (e.g. after logout). */
    /** 断线或登出后自动重连（登出后由 onClosed 触发清理，下一次操作再建新连接）。 */
    private void ensureConnected() {
        if (session == null || !session.isOpen()) {
            if (serverAddress == null) {
                throw new Errors.Network("未连接到服务器");
            }
            connect(serverAddress);
        }
    }

    public void logout() {
        if (session != null) {
            session.close(); // 关闭传输会话；onClosed 负责清理状态
        }
    }

    /** 注册审批回调：status 为服务器返回的审批结果（如 approved/pending），原样展示。 */
    void onRegisterResult(String status, String ticketId, String message) {
        events.onInfo("注册：" + status + (message.isBlank() ? "" : " —— " + message));
    }

    // ---- notifications ----

    public List<Notification> notificationList() {
        long now = System.currentTimeMillis();
        List<Notification> out = new ArrayList<>();
        for (WireMessages.NotificationDto n : notifications) {
            if (n.expiresAt() > 0 && now > n.expiresAt()) {
                continue;
            }
            String state = n.state();
            if (state.equals("unread") || state.equals("read")) {
                out.add(new Notification(n.id(), NotificationKind.valueOf(
                        kindName(n.kind())), NotificationState.valueOf(state.toUpperCase()),
                        n.from(), n.text(), n.createdAt(), n.expiresAt(), n.refId()));
            }
        }
        return out;
    }

    private static String kindName(String wireKind) {
        return switch (wireKind) {
            case "friend-request" -> "FRIEND_REQUEST";
            case "announcement" -> "ANNOUNCEMENT";
            default -> "INFO";
        };
    }

    /** 离线投递：服务器把未读通知（好友请求、公告等）在登录后批量推送（NOTIFY_LIST）。 */
    void onNotifications(List<WireMessages.NotificationDto> items) {
        for (WireMessages.NotificationDto n : items) {
            upsertNotification(n);
        }
        events.onNotificationsChanged();
    }

    /** 在线实时推送（NOTIFY_PUSH）：新通知到达时提示用户。 */
    void onNotification(WireMessages.NotificationDto n) {
        upsertNotification(n);
        events.onNotificationsChanged();
        events.onInfo("新通知：" + n.text());
    }

    private void upsertNotification(WireMessages.NotificationDto n) {
        for (int i = 0; i < notifications.size(); i++) {
            if (notifications.get(i).id().equals(n.id())) {
                notifications.set(i, n); // dedup by stable id
                return;
            }
        }
        notifications.add(0, n);
    }

    void onNotificationState(String notifId, String state) {
        for (int i = 0; i < notifications.size(); i++) {
            WireMessages.NotificationDto n = notifications.get(i);
            if (n.id().equals(notifId)) {
                notifications.set(i, new WireMessages.NotificationDto(n.id(), n.kind(), state,
                        n.from(), n.text(), n.createdAt(), n.expiresAt(), n.refId()));
                break;
            }
        }
        events.onNotificationsChanged();
    }

    /** 向服务器发送通知动作（如 pass/reject）；状态变化由服务器异步回推。 */
    public String notifyAction(Notification n, String action) {
        requireConnected();
        session.conn().send(Wire.notifyAction(n.id(), action));
        return "操作已发送";
    }

    // ---- friends ----

    void onFriendsList(List<WireMessages.FriendEntry> list) {
        friends.clear();
        for (WireMessages.FriendEntry f : list) {
            friends.put(f.name(), new FriendEntry(f.name(), f.status().equals("connected"), f.bot()));
        }
        events.onStatusChanged();
    }

    void onPeerState(String user, String status) {
        FriendEntry e = friends.get(user);
        if (e != null) {
            friends.put(user, new FriendEntry(user, status.equals("connected"), e.bot()));
            events.onPeerStatus(user, status.equals("connected")
                    ? PeerStatus.CONNECTED : PeerStatus.UNCONFIRMED);
            events.onStatusChanged();
        }
    }

    void onFriendState(WireMessages.FriendState fs) {
        if (fs.to().equals(loggedInUser) && fs.state().equals("accepted")) {
            friends.put(fs.from(), new FriendEntry(fs.from(), true, false));
            pendingOut.remove(fs.from());
            events.onStatusChanged();
        }
    }

    public List<String> friends() {
        List<String> out = new ArrayList<>(friends.keySet());
        out.sort(Comparator.naturalOrder());
        return out;
    }

    public FriendState friendState(String name) {
        if (friends.containsKey(name)) {
            return FriendState.ACCEPTED;
        }
        if (pendingOut.contains(name)) {
            return FriendState.PENDING_OUT;
        }
        for (Notification n : notificationList()) {
            if (n.kind() == NotificationKind.FRIEND_REQUEST && name.equals(n.from())) {
                return FriendState.PENDING_IN;
            }
        }
        return FriendState.NONE;
    }

    public PeerStatus peerStatus(String name) {
        FriendEntry e = friends.get(name);
        return e != null && e.online() ? PeerStatus.CONNECTED : PeerStatus.UNCONFIRMED;
    }

    public boolean sendFriendRequest(String name) {
        requireConnected();
        Text.requireServerName(name);
        if (name.equals(loggedInUser)) {
            throw new Errors.Usage("不能添加自己为好友");
        }
        if (friends.containsKey(name)) {
            throw new Errors.Conflict("你已经是 " + name + " 的好友");
        }
        if (pendingOut.contains(name)) {
            throw new Errors.Conflict("已向 " + name + " 发送的好友请求仍在等待处理");
        }
        pendingOut.add(name);
        session.conn().send(Wire.friendRequest(Ids.newId(), loggedInUser, name));
        events.onStatusChanged();
        return true;
    }

    public void acceptFriendRequest(String from, String requestId) {
        // 服务器中转：先找到对应的好友请求通知，再把通过动作（pass）发给服务器。
        for (Notification n : notificationList()) {
            if (n.kind() == NotificationKind.FRIEND_REQUEST && from.equals(n.from())
                    && requestId.equals(n.refId())) {
                notifyAction(n, "pass");
                return;
            }
        }
        throw new Errors.NotFound("未找到该好友请求");
    }

    public void rejectFriendRequest(String from, String requestId) {
        for (Notification n : notificationList()) {
            if (n.kind() == NotificationKind.FRIEND_REQUEST && from.equals(n.from())
                    && requestId.equals(n.refId())) {
                notifyAction(n, "reject");
                return;
            }
        }
        throw new Errors.NotFound("未找到该好友请求");
    }

    // ---- private messaging ----

    /** 收到私聊消息：仅处理发给自己的消息，写入本地历史并上抛给 UI。 */
    void onPrivateMessage(WireMessages.Message m) {
        if (!m.to().equals(loggedInUser)) {
            return;
        }
        String key = convKey(ConversationRef.privateChat(m.sender(), true));
        history.onMessage(key, m.sender(), new LocalHistoryStore.Entry(
                m.msgId(), m.sender(), m.text(), m.ts(), "in"));
        events.onMessage(ConversationRef.privateChat(m.sender(), true),
                new Message(m.msgId(), key, m.sender(), m.text(), m.ts()));
    }

    public void sendPrivate(String to, String text) {
        requireLoggedIn();
        Text.requireCleanText(text, "消息", Text.MAX_MESSAGE);
        if (!friends.containsKey(to)) {
            throw new Errors.Denied("你不是 " + to + " 的好友");
        }
        String msgId = Ids.newId();
        long ts = System.currentTimeMillis();
        session.conn().send(Wire.message(msgId, loggedInUser, to, text));
        String key = convKey(ConversationRef.privateChat(to, true));
        history.onMessage(key, to, new LocalHistoryStore.Entry(
                msgId, loggedInUser, text, ts, "out"));
        events.onMessage(ConversationRef.privateChat(to, true),
                new Message(msgId, key, loggedInUser, text, ts));
    }

    // ---- channel ----

    void onChannelMessage(WireMessages.ChannelMessage m) {
        if (!m.channelId().equals(channelId)) {
            return;
        }
        String key = convKey(ConversationRef.channel(channelId, serverName == null ? "server" : serverName));
        history.onMessage(key, serverName == null ? "server" : serverName,
                new LocalHistoryStore.Entry(m.msgId(), serverName == null ? "server" : serverName,
                        m.text(), m.ts(), "in"));
        events.onMessage(ConversationRef.channel(channelId, serverName == null ? "server" : serverName),
                new Message(m.msgId(), key, serverName == null ? "server" : serverName, m.text(), m.ts()));
    }

    // ---- groups ----

    public List<ConversationRef> conversations() {
        List<ConversationRef> out = new ArrayList<>();
        if (channelId != null) {
            out.add(ConversationRef.channel(channelId, serverName == null ? "server" : serverName));
        }
        for (String friend : friends()) {
            out.add(ConversationRef.privateChat(friend, true));
        }
        for (ClientGroup g : groups.values()) {
            out.add(ConversationRef.group(g.id, g.name,
                    g.privacy.equals("encrypted")
                            ? org.ourcamellia.common.domain.GroupPrivacy.ENCRYPTED
                            : org.ourcamellia.common.domain.GroupPrivacy.OPERATOR_VISIBLE));
        }
        out.sort(Comparator.comparing((ConversationRef r) -> r.pinned() ? 0 : 1)
                .thenComparing(ConversationRef::title, SortUtil.SPEC_ORDER));
        return out;
    }

    public List<ClientGroup> groups() {
        return new ArrayList<>(groups.values());
    }

    void onGroupUpdate(WireMessages.GroupInfo info) {
        ClientGroup g = groups.computeIfAbsent(info.id(), id -> new ClientGroup());
        g.id = info.id();
        g.name = info.name();
        g.privacy = info.privacy();
        g.owner = info.owner();
        g.members = info.members();
        g.pubKeys.putAll(info.pubKeys());
        if (!info.members().contains(loggedInUser) && !info.owner().equals(loggedInUser)) {
            groups.remove(info.id()); // we were removed
        }
        events.onStatusChanged();
    }

    /** 收到群消息：加密群组需先用群组密钥解密；解密失败时提示用户（可能是轮换中）。 */
    void onGroupMessage(WireMessages.GroupMessage m) {
        ClientGroup g = groups.get(m.groupId());
        if (g == null) {
            return;
        }
        String text;
        if (g.privacy.equals("encrypted")) {
            if (g.groupKey == null || m.box() == null) {
                events.onInfo("无法解密群组消息（缺少群组密钥 —— 请群主重新发送密钥）");
                return;
            }
            String decrypted = GroupCrypto.decryptMessage(g.groupKey, g.id, m.box());
            if (decrypted == null) {
                events.onInfo("群组消息解密失败（是否正在进行密钥轮换？）");
                return;
            }
            text = decrypted;
        } else {
            text = m.text();
        }
        ConversationRef ref = ConversationRef.group(g.id, g.name, g.privacy.equals("encrypted")
                ? org.ourcamellia.common.domain.GroupPrivacy.ENCRYPTED
                : org.ourcamellia.common.domain.GroupPrivacy.OPERATOR_VISIBLE);
        String key = convKey(ref);
        history.onMessage(key, g.name, new LocalHistoryStore.Entry(
                m.msgId(), m.sender(), text, m.ts(), "in"));
        events.onMessage(ref, new Message(m.msgId(), key, m.sender(), text, m.ts()));
    }

    /** 群组密钥分发：群主把密钥按每个成员的公钥分别包装（wrap）后经服务器转发。 */
    void onGroupKeyWrap(WireMessages.GroupKeyWrap w) {
        ClientGroup g = groups.get(w.groupId());
        if (g == null || !w.forUser().equals(loggedInUser)) {
            return;
        }
        byte[] senderPub = w.fromPub() == null ? null : Base64.getDecoder().decode(w.fromPub());
        if (senderPub == null || senderPub.length != 32) {
            events.onInfo("群组密钥包装包含无效的发送方密钥（已忽略）");
            return;
        }
        byte[] key = GroupCrypto.unwrapGroupKey(store.x25519KeyPair().getPrivate(),
                senderPub, w.groupId(), w.generation(), w.box());
        if (key == null) {
            events.onInfo("群组密钥包装无法解密（已忽略）");
            return;
        }
        g.groupKey = key;
        g.generation = w.generation();
        g.rotationPending = false;
        events.onInfo("已收到群组 '" + g.name + "' 的密钥");
    }

    /** 创建群组。加密群组由群主在本地生成群组密钥并分发给各成员。 */
    public String createGroup(String name, List<String> members, boolean encrypted) {
        requireLoggedIn();
        Text.requireGroupName(name);
        if (members.size() > 64) {
            throw new Errors.Validation("成员过多（上限 64 人）");
        }
        String groupId = Ids.newId();
        String privacy = encrypted ? "encrypted" : "operator-visible";
        session.conn().send(Wire.groupCreate(groupId, name, privacy, members));
        if (encrypted) {
            // 群主现在生成群组密钥，并按每个成员分别包装（同时自己保留一份）。
            byte[] groupKey = GroupCrypto.randomGroupKey();
            ClientGroup g = groups.computeIfAbsent(groupId, id -> new ClientGroup());
            g.id = groupId;
            g.name = name;
            g.privacy = privacy;
            g.owner = loggedInUser;
            g.members = members;
            g.groupKey = groupKey;
            // 需要各成员的公钥；服务器在创建后通过 GROUP_UPDATE 下发（含 pubKeys）。
            // 等更新到达后，再把密钥包装分发各成员。
            g.rotationPending = true; // 待分发
            distributeKeysWhenReady(g);
        }
        return groupId;
    }

    /** 等成员公钥齐备后分发密钥包装。 */
    private void distributeKeysWhenReady(ClientGroup g) {
        Thread.ofVirtual().start(() -> {
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                boolean allKnown = true;
                for (String member : g.members) {
                    if (!g.pubKeys.containsKey(member)) {
                        allKnown = false;
                        break;
                    }
                }
                if (allKnown) {
                    sendWraps(g);
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            events.onInfo("警告：无法获得群组 '" + g.name
                    + "' 所有成员的公钥 —— 请手动发送密钥包装（${group-send-keys}）");
        });
    }

    /** 群主逐一为新成员包装密钥（用到各成员的公钥），完成后清除待分发标记。 */
    private void sendWraps(ClientGroup g) {
        var myKeys = store.x25519KeyPair();
        byte[] myPub = GroupCrypto.publicKeyBytes(myKeys);
        for (String member : g.members) {
            String pubB64 = g.pubKeys.get(member);
            if (pubB64 == null) {
                continue;
            }
            byte[] memberPub = Base64.getDecoder().decode(pubB64);
            String box = GroupCrypto.wrapGroupKey(myKeys.getPrivate(), memberPub, g.id,
                    g.generation, g.groupKey);
            session.conn().send(Wire.groupKeyWrap(g.id, member, box, g.generation,
                    loggedInUser, Base64.getEncoder().encodeToString(myPub)));
        }
        g.rotationPending = false;
    }

    /** 密钥轮换：群主生成新密钥并重新包装给其余成员（generation 递增）。 */
    public void rotateGroupKeys(String groupId) {
        requireLoggedIn();
        ClientGroup g = groups.get(groupId);
        if (g == null || !g.owner.equals(loggedInUser)) {
            throw new Errors.Denied("只有群主可以轮换群组密钥");
        }
        byte[] newKey = GroupCrypto.randomGroupKey();
        g.groupKey = newKey;
        g.generation += 1;
        sendWraps(g);
    }

    public void inviteMember(String groupId, String member) {
        requireLoggedIn();
        ClientGroup g = groups.get(groupId);
        if (g == null) {
            throw new Errors.NotFound("没有该群组");
        }
        session.conn().send(Wire.groupMemberOp(Wire.T_GROUP_INVITE, groupId, member));
        // 加密群组的新成员公钥会随 GROUP_UPDATE 到来；群主拿到后即分发密钥包装。
        if (g.privacy.equals("encrypted") && g.groupKey != null) {
            g.rotationPending = true;
            distributeKeysWhenReady(g);
        }
    }

    public void removeMember(String groupId, String member) {
        requireLoggedIn();
        session.conn().send(Wire.groupMemberOp(Wire.T_GROUP_REMOVE, groupId, member));
    }

    public void leaveGroup(String groupId) {
        requireLoggedIn();
        session.conn().send(Wire.groupOp(Wire.T_GROUP_LEAVE, groupId));
        groups.remove(groupId);
    }

    public void transferGroup(String groupId, String newOwner) {
        requireLoggedIn();
        session.conn().send(Wire.groupMemberOp(Wire.T_GROUP_TRANSFER, groupId, newOwner));
    }

    public List<String> groupMembers(String groupId) {
        ClientGroup g = groups.get(groupId);
        if (g == null) {
            throw new Errors.NotFound("没有该群组");
        }
        List<String> out = new ArrayList<>(g.members);
        out.add(0, g.owner);
        return out;
    }

    public ClientGroup group(String groupId) {
        return groups.get(groupId);
    }

    /** 发送群消息：加密群组在客户端本地加密后再上送（服务器看不到明文）。 */
    public void sendGroupMessage(String groupId, String text) {
        requireLoggedIn();
        Text.requireCleanText(text, "消息", Text.MAX_MESSAGE);
        ClientGroup g = groups.get(groupId);
        if (g == null) {
            throw new Errors.NotFound("没有该群组");
        }
        if (!g.owner.equals(loggedInUser) && !g.members.contains(loggedInUser)) {
            throw new Errors.Denied("你不是该群组的成员");
        }
        String msgId = Ids.newId();
        long ts = System.currentTimeMillis();
        if (g.privacy.equals("encrypted")) {
            if (g.groupKey == null) {
                throw new Errors.Denied("群组密钥不可用 —— 请群主重新发送");
            }
            String box = GroupCrypto.encryptMessage(g.groupKey, g.id,
                    text.getBytes(StandardCharsets.UTF_8));
            session.conn().send(Wire.groupMessage(msgId, groupId, loggedInUser, null, box));
            recordGroupOut(g, msgId, text, ts);
        } else {
            session.conn().send(Wire.groupMessage(msgId, groupId, loggedInUser, text, null));
            recordGroupOut(g, msgId, text, ts);
        }
    }

    private void recordGroupOut(ClientGroup g, String msgId, String text, long ts) {
        ConversationRef ref = ConversationRef.group(g.id, g.name, g.privacy.equals("encrypted")
                ? org.ourcamellia.common.domain.GroupPrivacy.ENCRYPTED
                : org.ourcamellia.common.domain.GroupPrivacy.OPERATOR_VISIBLE);
        String key = convKey(ref);
        history.onMessage(key, g.name, new LocalHistoryStore.Entry(
                msgId, loggedInUser, text, ts, "out"));
        events.onMessage(ref, new Message(msgId, key, loggedInUser, text, ts));
    }

    // ---- conversations / say routing ----

    /** 按会话类型把输入路由到私聊/群组/频道。 */
    public void sayIn(ConversationRef conversation, String text) {
        requireLoggedIn();
        if (conversation == null) {
            throw new Errors.Usage("未选择会话");
        }
        switch (conversation.kind()) {
            case PRIVATE -> sendPrivate(conversation.id(), text);
            case GROUP -> sendGroupMessage(conversation.id(), text);
            case CHANNEL -> throw new Errors.Denied(
                    "服务器公告频道为只读：只有运营者（管理员）可以发布公告");
        }
    }

    public String convKey(ConversationRef ref) {
        String prefix = convKeyPrefix();
        return switch (ref.kind()) {
            case PRIVATE -> prefix + "priv:" + ref.id();
            case GROUP -> prefix + "grp:" + ref.id();
            case CHANNEL -> prefix + "channel";
        };
    }

    public String convKeyPrefix() {
        return "server:" + sanitizeAddr(serverAddress) + ":";
    }

    static String sanitizeAddr(String address) {
        return address == null ? "?" : address.replaceAll("[^A-Za-z0-9.:-]", "_");
    }

    private void requireConnected() {
        if (session == null || !session.isOpen()) {
            throw new Errors.Network("未连接到服务器");
        }
    }

    private void requireLoggedIn() {
        requireConnected();
        if (loggedInUser == null) {
            throw new Errors.Denied("未登录 —— 请使用 ${login(...)}");
        }
    }

    @Override
    public void close() {
        if (session != null) {
            session.close(); // 会话处理器随后会调用 onClosed 清理状态
        } else {
            onClosed("本地关闭");
        }
    }
}
