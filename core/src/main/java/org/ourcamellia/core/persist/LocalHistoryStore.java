package org.ourcamellia.core.persist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ourcamellia.common.AeadBox;
import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Ids;
import org.ourcamellia.common.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 本地 JSON 会话历史记录（REQUIREMENTS §8）。
 *
 * <p>本类同时支持两种磁盘布局，目的是“升级不丢旧记录”：
 * <ul>
 *   <li><b>便携布局</b>（生产默认）：由 {@link #portable(Path, boolean, String, int)}
 *       创建，记录写入 <code>&lt;data&gt;/chats/local/...</code> 与
 *       <code>&lt;data&gt;/chats/servers/&lt;IP&gt;/&lt;服务器标识&gt;/conversations/...</code>；</li>
 *   <li><b>旧扁平布局</b>（兼容）：由测试和旧版本使用，所有会话文件直接放在
 *       <code>&lt;data&gt;/history/&lt;base64(会话键)&gt;.json</code>。</li>
 * </ul>
 *
 * <p>当便携布局找不到某个会话文件时，会尝试读取旧目录中的同名文件；读取成功后
 * 不会移动或删除旧文件，下一次保存时才写入新位置。这样旧的聊天记录始终可读，
 * 也不会因为目录调整而自动删除任何原始数据。
 *
 * <p>其余语义保持不变：
 * <ul>
 *   <li>每个会话单独记录录制开关：ON → 新消息立即落盘；OFF → 消息只在内存中，
 *       直到显式 {@link #persist} 或退出提示；关闭录制绝不删除已保存记录；</li>
 *   <li>稳定消息 ID 用于去重；</li>
 *   <li>可选二级密码保护：整个文件是 AES-256-GCM 密封盒；密码丢失则无法恢复；</li>
 *   <li>原子写入；损坏文件先隔离（改名保留），不静默覆盖。</li>
 * </ul>
 */
public final class LocalHistoryStore {

    public static final int SCHEMA = 1;

    public record Entry(String id, String sender, String text, long ts, String direction) {}

    public static final class Conversation {
        public String key;
        public String title;
        public boolean recording;
        public List<Entry> persisted = new ArrayList<>();
        public final List<Entry> buffered = new ArrayList<>();
    }

    /** 一个已注册服务器在磁盘上的位置：会话键前缀 → 目录。 */
    private record ServerBinding(String prefix, Path dir, String serverId, String host) {}

    private final Path historyDir;          // 旧扁平布局目录（便携模式下仅用于兼容读取）
    private final boolean portable;
    private final ChatPaths chats;
    private final boolean defaultRecording;
    private final String secondaryPassword; // null = 无保护
    private final int iterations;
    private final Map<String, Conversation> open = new LinkedHashMap<>();
    private final Map<String, ServerBinding> servers = new LinkedHashMap<>();

    /**
     * 旧扁平布局构造器（测试与旧代码使用）。行为与升级前完全一致：
     * 文件直接写入给定的 history 目录。
     */
    public LocalHistoryStore(Path historyDir, boolean defaultRecording,
                             String secondaryPassword, int iterations) {
        this(historyDir, defaultRecording, secondaryPassword, iterations, false, null);
    }

    /**
     * 创建便携式存储：聊天记录写入 dataDir/chats，旧 dataDir/history 只读兼容。
     * 这是客户端生产启动路径使用的构造方式。
     */
    public static LocalHistoryStore portable(Path dataDir, boolean defaultRecording,
                                             String secondaryPassword, int iterations) {
        return new LocalHistoryStore(dataDir.resolve("history"), defaultRecording,
                secondaryPassword, iterations, true, dataDir);
    }

    private LocalHistoryStore(Path historyDir, boolean defaultRecording, String secondaryPassword,
                              int iterations, boolean portable, Path dataDir) {
        this.historyDir = historyDir.toAbsolutePath().normalize();
        this.portable = portable;
        this.chats = portable ? new ChatPaths(dataDir) : null;
        this.defaultRecording = defaultRecording;
        this.secondaryPassword = secondaryPassword;
        this.iterations = iterations;
        try {
            if (portable) {
                Files.createDirectories(chats.localRoot());
                Files.createDirectories(chats.serversRoot());
            } else {
                Files.createDirectories(this.historyDir);
            }
        } catch (IOException e) {
            throw new Errors.Persistence("无法创建历史记录目录", e);
        }
    }

    /** 会话键的稳定文件名（base64url，不存在路径穿越的可能）。 */
    static String fileName(String key) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8)) + ".json";
    }

    public synchronized Conversation open(String key, String title) {
        Conversation existing = open.get(key);
        if (existing != null) {
            // messages()/recording() 为了按需加载会用会话键临时充当标题；
            // 之后业务层传入真实标题（对端名、群名、服务器名）时应补正，
            // 避免把内部键写进历史文件的 title 字段。
            if (title != null && !title.isBlank()
                    && (existing.title == null || existing.title.isBlank()
                    || existing.title.equals(key))) {
                existing.title = title;
            }
            return existing;
        }
        Conversation c = new Conversation();
        c.key = key;
        c.title = title;
        c.recording = defaultRecording;
        loadInto(c);
        open.put(key, c);
        return c;
    }

    public synchronized Conversation conversation(String key) {
        return open.get(key);
    }

    public synchronized boolean isOpen(String key) {
        return open.containsKey(key);
    }

    /**
     * 记录新消息：录制开启时立即落盘，否则先缓存在内存。
     *
     * @throws Errors.Persistence 磁盘不可写或文件系统出错时抛出；调用方（UI）
     *                            负责把错误显示给用户，不应静默丢弃消息。
     */
    public synchronized void onMessage(String key, String title, Entry entry) {
        Conversation c = open(key, title);
        if (contains(c, entry.id())) {
            return; // 重复投递
        }
        if (c.recording) {
            c.persisted.add(entry);
            save(c);
        } else {
            c.buffered.add(entry);
        }
    }

    private static boolean contains(Conversation c, String id) {
        return c.persisted.stream().anyMatch(e -> e.id().equals(id))
                || c.buffered.stream().anyMatch(e -> e.id().equals(id));
    }

    public synchronized List<Entry> messages(String key) {
        Conversation c = open.get(key);
        if (c == null) {
            c = open(key, key); // 按需加载（并隔离损坏的）持久化文件
        }
        return List.copyOf(c.persisted);
    }

    public synchronized boolean recording(String key) {
        Conversation c = open.get(key);
        if (c == null) {
            c = open(key, key);
        }
        return c.recording;
    }

    /** 切换单个会话的自动录制开关。绝不删除已保存的历史记录。 */
    public synchronized void setRecording(String key, String title, boolean on) {
        Conversation c = open(key, title);
        if (c.recording != on) {
            c.recording = on;
            save(c);
        }
    }

    public synchronized boolean hasBuffered(String key) {
        Conversation c = open.get(key);
        if (c == null) {
            c = open(key, key);
        }
        return !c.buffered.isEmpty();
    }

    /** 待持久化消息条数的线程安全快照（供 UI 显示，不暴露可变列表）。 */
    public synchronized int bufferedCount(String key) {
        Conversation c = open.get(key);
        if (c == null) {
            c = open(key, key);
        }
        return c.buffered.size();
    }

    /** 显式手动持久化：把待持久化消息写入文件，返回写入条数。 */
    public synchronized int persist(String key) {
        Conversation c = open.get(key);
        if (c == null || c.buffered.isEmpty()) {
            return 0;
        }
        int n = c.buffered.size();
        c.persisted.addAll(c.buffered);
        c.buffered.clear();
        save(c);
        return n;
    }

    /** 删除一个会话已保存的历史记录（文件 + 内存视图）。 */
    public synchronized void deleteConversation(String key) {
        open.remove(key);
        Path file = fileFor(key);
        try {
            Files.deleteIfExists(file);
            // 兼容：旧扁平布局中可能还有同名记录；用户明确要求删除时才一并删除。
            if (portable) {
                Files.deleteIfExists(historyDir.resolve(fileName(key)));
                removeIfEmpty(file.getParent());
            }
        } catch (IOException e) {
            throw new Errors.Persistence("无法删除历史记录文件", e);
        }
    }

    /** 删除所有会话键匹配给定谓词的历史记录文件。 */
    public synchronized int deleteMatching(Predicate<String> matches) {
        int removed = 0;
        if (portable) {
            for (Path file : conversationFiles()) {
                String key = keyOfFile(file);
                if (key != null && matches.test(key)) {
                    open.remove(key);
                    try {
                        if (Files.deleteIfExists(file)) {
                            removed++;
                        }
                    } catch (IOException e) {
                        throw new Errors.Persistence("无法删除历史记录文件", e);
                    }
                }
            }
            // 旧扁平文件：只有能读出会话键的才参与匹配，无法判断的保持原样。
            for (Path file : legacyConversationFiles()) {
                String key = keyOfFile(file);
                if (key != null && matches.test(key)) {
                    open.remove(key);
                    try {
                        if (Files.deleteIfExists(file)) {
                            removed++;
                        }
                    } catch (IOException e) {
                        throw new Errors.Persistence("无法删除历史记录文件", e);
                    }
                }
            }
            return removed;
        }

        for (String key : new ArrayList<>(open.keySet())) {
            if (matches.test(key)) {
                open.remove(key);
                try {
                    if (Files.deleteIfExists(historyDir.resolve(fileName(key)))) {
                        removed++;
                    }
                } catch (IOException e) {
                    throw new Errors.Persistence("无法删除历史记录文件", e);
                }
            }
        }
        // 对未打开的文件，通过解码文件名得到会话键再匹配。
        try (var stream = Files.list(historyDir)) {
            for (Path f : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                String base = f.getFileName().toString();
                String key = decodeKey(base.substring(0, base.length() - 5));
                if (key != null && matches.test(key)) {
                    try {
                        if (Files.deleteIfExists(f)) {
                            removed++;
                        }
                    } catch (IOException e) {
                        throw new Errors.Persistence("无法删除历史记录文件", e);
                    }
                }
            }
        } catch (IOException e) {
            throw new Errors.Persistence("无法列出历史记录目录", e);
        }
        return removed;
    }

    /**
     * 删除全部会话历史记录。
     *
     * <p>便携布局只删除会话文件（conversation.json 与 conversations/*.json）以及
     * 旧扁平布局中的历史文件，保留 server.json 元数据与 .corrupt-* 隔离文件。
     */
    public synchronized int deleteAll() {
        open.clear();
        int removed = 0;
        if (portable) {
            for (Path file : conversationFiles()) {
                try {
                    if (Files.deleteIfExists(file)) {
                        removed++;
                    }
                } catch (IOException e) {
                    throw new Errors.Persistence("无法删除历史记录文件", e);
                }
            }
            for (Path file : legacyConversationFiles()) {
                try {
                    if (Files.deleteIfExists(file)) {
                        removed++;
                    }
                } catch (IOException e) {
                    throw new Errors.Persistence("无法删除历史记录文件", e);
                }
            }
            return removed;
        }
        try (var stream = Files.list(historyDir)) {
            for (Path f : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    if (Files.deleteIfExists(f)) {
                        removed++;
                    }
                } catch (IOException e) {
                    throw new Errors.Persistence("无法删除历史记录文件", e);
                }
            }
        } catch (IOException e) {
            throw new Errors.Persistence("无法列出历史记录目录", e);
        }
        return removed;
    }

    // ---------------- 目录解析 ----------------

    /**
     * 注册一个服务器实例：由 ServerSession 在建立连接（以及收到 WELCOME）时调用。
     *
     * <p>之所以需要注册：会话键里只带地址，而目录需要“服务器身份”。服务器身份
     * 由证书指纹派生，这样服务器改名、换 IP 后仍能找回同一个目录。
     *
     * @param keyPrefix   业务层的会话键前缀，例如 <code>server:127.0.0.1:54530:</code>
     * @param address     实际连接地址（host:port），写入 server.json
     * @param serverName  服务器名称；连接早期可能为 null
     * @param fingerprint 服务器 TLS 证书指纹；非 TLS 测试链路可能为 null
     */
    public synchronized void registerServer(String keyPrefix, String address,
                                            String serverName, String fingerprint) {
        if (!portable || keyPrefix == null || keyPrefix.isEmpty()) {
            return;
        }
        String host = ChatPaths.hostOf(address);
        String serverId = ChatPaths.serverId(serverName, fingerprint);
        Path exact = chats.serverDir(host, serverId);
        Path chosen = exact;
        if (!Files.isRegularFile(exact.resolve("server.json"))) {
            // 手工迁移场景：用户把整个服务器目录从旧 IP 移到了新 IP；
            // 只要服务器标识一致，就在新 IP 下继续使用原有目录（不自动移动）。
            List<Path> matches = findServerDirs(serverId);
            if (matches.size() == 1) {
                chosen = matches.get(0);
            }
        }
        ServerBinding binding = new ServerBinding(keyPrefix, chosen, serverId, host);
        servers.put(keyPrefix, binding);
        writeServerMeta(binding, serverName, address, fingerprint);
    }

    /** 供 UI/测试查看某个会话键最终解析到的文件（不创建文件）。 */
    public synchronized Path pathFor(String key) {
        return fileFor(key);
    }

    /** 供 UI/测试查看服务器目录（未注册时返回 null）。 */
    public synchronized Path serverDirectory(String keyPrefix) {
        ServerBinding b = servers.get(keyPrefix);
        return b == null ? null : b.dir();
    }

    private Path fileFor(String key) {
        if (!portable) {
            return historyDir.resolve(fileName(key));
        }
        if (key.startsWith("local:")) {
            return chats.localConversationFile(key.substring("local:".length()));
        }
        ServerBinding binding = bindingFor(key);
        String name = ChatPaths.conversationName(key, binding.prefix());
        // binding.dir() 才是记录实际所在的目录；迁移识别后它可能仍位于旧 IP 目录下。
        return binding.dir().resolve("conversations")
                .resolve(ChatPaths.safeSegment(name) + ".json");
    }

    private ServerBinding bindingFor(String key) {
        ServerBinding best = null;
        for (ServerBinding b : servers.values()) {
            if (key.startsWith(b.prefix())
                    && (best == null || b.prefix().length() > best.prefix().length())) {
                best = b;
            }
        }
        if (best != null) {
            return best;
        }
        // 尚未注册（例如通过命令直接构造会话键）：按业务键推导前缀并建立兜底绑定。
        String prefix = deriveServerPrefix(key);
        String address = prefix.startsWith("server:")
                ? prefix.substring("server:".length(), Math.max("server:".length(), prefix.length() - 1))
                : "";
        registerServer(prefix, address, null, null);
        return servers.get(prefix);
    }

    private static String deriveServerPrefix(String key) {
        int priv = key.lastIndexOf(":priv:");
        int grp = key.lastIndexOf(":grp:");
        int channel = key.endsWith(":channel") ? key.length() - ":channel".length() : -1;
        int cut = Math.max(priv, Math.max(grp, channel));
        if (cut < 0) {
            return "server:unknown:";
        }
        return key.substring(0, cut + 1);
    }

    private List<Path> findServerDirs(String serverId) {
        List<Path> out = new ArrayList<>();
        Path root = chats.serversRoot();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (var ips = Files.list(root)) {
            for (Path ipDir : ips.filter(Files::isDirectory).toList()) {
                try (var serverDirs = Files.list(ipDir)) {
                    for (Path dir : serverDirs.filter(Files::isDirectory).toList()) {
                        Path meta = dir.resolve("server.json");
                        if (!Files.isRegularFile(meta)) {
                            continue;
                        }
                        if (dir.getFileName().toString().equals(serverId) || metaServerId(meta, serverId)) {
                            out.add(dir);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new Errors.Persistence("无法扫描服务器记录目录", e);
        }
        return out;
    }

    private static boolean metaServerId(Path meta, String serverId) {
        try {
            JsonNode root = Json.parse(Files.readAllBytes(meta));
            return serverId.equals(root.path("serverId").asText(""));
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    /** 写入/更新 server.json；若原文件损坏，先隔离再写新文件。 */
    private void writeServerMeta(ServerBinding binding, String serverName,
                                 String address, String fingerprint) {
        Path meta = binding.dir().resolve("server.json");
        JsonNode old = null;
        if (Files.isRegularFile(meta)) {
            try {
                old = Json.parse(Files.readAllBytes(meta));
            } catch (RuntimeException | IOException e) {
                AtomicFiles.quarantine(meta, "服务器元数据损坏：" + e.getMessage());
            }
        }
        long createdAt = old != null ? old.path("createdAt").asLong(0L) : 0L;
        if (createdAt <= 0) {
            createdAt = System.currentTimeMillis();
        }
        ObjectNode o = Json.object();
        o.put("v", ChatPaths.SERVER_SCHEMA);
        o.put("serverId", binding.serverId());
        String name = (serverName == null || serverName.isBlank())
                ? (old == null ? null : old.path("name").asText(null)) : serverName;
        if (name != null && !name.isBlank()) {
            o.put("name", name);
        }
        if (address != null && !address.isBlank()) {
            o.put("address", address);
        } else if (old != null && old.hasNonNull("address")) {
            o.put("address", old.get("address").asText());
        }
        if (fingerprint != null && !fingerprint.isBlank()) {
            o.put("fingerprint", fingerprint);
        } else if (old != null && old.hasNonNull("fingerprint")) {
            o.put("fingerprint", old.get("fingerprint").asText());
        }
        o.put("createdAt", createdAt);
        o.put("updatedAt", System.currentTimeMillis());
        AtomicFiles.write(meta, Json.writeBytes(o));
    }

    // ---------------- 文件格式 ----------------

    /**
     * 加载单个会话文件：支持二级密码加密的密封盒；解密失败按“非损坏”处理，
     * 其余解析错误则隔离文件并清空内存视图。
     */
    private void loadInto(Conversation c) {
        Path file = fileFor(c.key);
        if (portable && !Files.exists(file)) {
            // 旧版本把记录放在 data/history/<base64(键)>.json；只读加载，不移动。
            Path legacy = historyDir.resolve(fileName(c.key));
            if (Files.exists(legacy)) {
                file = legacy;
            }
        }
        byte[] raw = AtomicFiles.readOrNull(file);
        if (raw == null) {
            return;
        }
        boolean decryptFailed = false;
        try {
            JsonNode root = Json.parse(raw);
            JsonNode messages;
            if (root.has("protected") && root.get("protected").asBoolean()) {
                if (secondaryPassword == null) {
                    decryptFailed = true;
                    throw new Errors.Persistence(
                            "历史记录文件受二级密码保护，但未提供二级密码");
                }
                byte[] salt = Base64.getDecoder().decode(Json.reqStr(root, "salt", 256));
                int iter = (int) Json.reqLong(root, "iterations");
                byte[] box = Base64.getDecoder().decode(Json.reqStr(root, "box", 64 * 1024 * 1024));
                byte[] key = AeadBox.deriveKey(secondaryPassword, salt, iter);
                byte[] inner = AeadBox.open(key, AeadBox.aadFor("ourcamellia-history", fileName(c.key)), box);
                if (inner == null) {
                    decryptFailed = true;
                    throw new Errors.Persistence(
                            "历史记录文件无法解密（二级密码不正确？）；文件保持不变");
                }
                root = Json.parse(inner);
            }
            if ((int) Json.reqLong(root, "v") != SCHEMA) {
                throw new Errors.Persistence("不支持的历史记录 Schema");
            }
            c.recording = Json.optBool(root, "recording", defaultRecording);
            String storedTitle = Json.optStr(root, "title", 256);
            if (storedTitle != null) {
                c.title = storedTitle;
            }
            messages = root.get("messages");
            if (messages != null) {
                if (!messages.isArray() || messages.size() > 100_000) {
                    throw new Errors.Persistence("历史记录消息数组无效");
                }
                for (JsonNode m : messages) {
                    c.persisted.add(new Entry(
                            Ids.requireId(Json.reqStr(m, "id", 64), "message id"),
                            Json.reqStr(m, "sender", 65),
                            Json.reqStr(m, "text", 1024 * 1024),
                            Json.reqLong(m, "ts"),
                            Json.optStr(m, "direction", 8)));
                }
            }
        } catch (Errors.OcException e) {
            if (decryptFailed) {
                // 解密失败（密码错误/缺失）不算损坏：文件保持原样，
                // 以便用户用正确密码重试。
                c.persisted.clear();
                return;
            }
            AtomicFiles.quarantine(file, "历史记录损坏：" + e.getMessage());
            c.persisted.clear();
        }
    }

    /**
     * 保存：纯文本，或（启用二级密码时）AES-256-GCM 密封盒；均经原子写落盘。
     *
     * <p>密封盒额外保存会话键：删除时无需解密即可判断某个文件属于哪个会话。
     * 会话键只包含对端名/群 ID 等标识，不含消息正文；消息正文仍在密文中。
     */
    private void save(Conversation c) {
        var root = Json.object();
        root.put("v", SCHEMA);
        root.put("key", c.key);
        root.put("title", c.title);
        root.put("recording", c.recording);
        var arr = root.putArray("messages");
        for (Entry e : c.persisted) {
            var o = arr.addObject();
            o.put("id", e.id());
            o.put("sender", e.sender());
            o.put("text", e.text());
            o.put("ts", e.ts());
            o.put("direction", e.direction());
        }
        byte[] plain = Json.writeBytes(root);
        byte[] payload;
        if (secondaryPassword != null) {
            byte[] salt = org.ourcamellia.common.CryptoUtil.randomBytes(16);
            byte[] key = AeadBox.deriveKey(secondaryPassword, salt, iterations);
            byte[] box = AeadBox.seal(key, AeadBox.aadFor("ourcamellia-history", fileName(c.key)), plain);
            var sealed = Json.object();
            sealed.put("v", SCHEMA);
            sealed.put("protected", true);
            sealed.put("key", c.key);
            sealed.put("salt", Base64.getEncoder().encodeToString(salt));
            sealed.put("iterations", iterations);
            sealed.put("box", Base64.getEncoder().encodeToString(box));
            payload = Json.writeBytes(sealed);
        } else {
            payload = plain;
        }
        AtomicFiles.write(fileFor(c.key), payload);
    }

    // ---------------- 删除辅助 ----------------

    /** 便携布局中所有会话文件（不含 server.json / 隔离文件）。 */
    private List<Path> conversationFiles() {
        List<Path> out = new ArrayList<>();
        Path local = chats.localRoot();
        if (Files.isDirectory(local)) {
            try (var dirs = Files.list(local)) {
                for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                    Path f = dir.resolve("conversation.json");
                    if (Files.isRegularFile(f)) {
                        out.add(f);
                    }
                }
            } catch (IOException e) {
                throw new Errors.Persistence("无法列出本地会话目录", e);
            }
        }
        Path servers = chats.serversRoot();
        if (Files.isDirectory(servers)) {
            try (var walk = Files.walk(servers, 5)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals("conversation.json")
                                || (p.getParent() != null
                                && p.getParent().getFileName().toString().equals("conversations")
                                && p.getFileName().toString().endsWith(".json")))
                        .forEach(out::add);
            } catch (IOException e) {
                throw new Errors.Persistence("无法扫描服务器会话目录", e);
            }
        }
        return out;
    }

    /** 旧扁平布局中的 .json 历史文件。 */
    private List<Path> legacyConversationFiles() {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(historyDir)) {
            return out;
        }
        try (var stream = Files.list(historyDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(out::add);
        } catch (IOException e) {
            throw new Errors.Persistence("无法列出历史记录目录", e);
        }
        return out;
    }

    /** 读取文件中的会话键（明文文件或密封盒外层）；无法判断时返回 null。 */
    private static String keyOfFile(Path file) {
        try {
            JsonNode root = Json.parse(Files.readAllBytes(file));
            JsonNode key = root.get("key");
            return key != null && key.isTextual() ? key.asText() : null;
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    private static void removeIfEmpty(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // 目录非空时保留；这不是错误。
        }
    }

    private static String decodeKey(String base64urlNoPadding) {
        try {
            String padded = base64urlNoPadding + "=".repeat((4 - base64urlNoPadding.length() % 4) % 4);
            return new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
