package org.ourcamellia.core.persist;

import com.fasterxml.jackson.databind.JsonNode;
import org.ourcamellia.common.AeadBox;
import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Ids;
import org.ourcamellia.common.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 JSON 会话历史记录（REQUIREMENTS §8）。
 *
 * - 每个会话一个 JSON 文件；原子写入；带 Schema 版本号。
 * - 每个会话单独记录录制开关（随文件持久化）：ON → 新消息立即落盘；
 *   OFF → 新消息只留在进程内存中（待持久化消息），直到显式 {@link #persist}
 *   或退出提示时才会写入磁盘。
 * - 切换录制开关绝不会删除已保存的历史记录。
 * - 稳定的消息 ID 用于跨重试/重启去重。
 * - 可选的二级密码保护（P4）：整个文件是以二级密码经 PBKDF2 推导出的
 *   AES-256-GCM 密封盒；密码丢失则文件无法恢复（已记录，无静默回退）。
 * - 损坏的文件会被隔离（绝不无备份地静默丢弃）。
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

    private final Path historyDir;
    private final boolean defaultRecording;
    private final String secondaryPassword;   // null = 无保护
    private final int iterations;
    private final Map<String, Conversation> open = new LinkedHashMap<>();

    public LocalHistoryStore(Path historyDir, boolean defaultRecording,
                             String secondaryPassword, int iterations) {
        this.historyDir = historyDir;
        this.defaultRecording = defaultRecording;
        this.secondaryPassword = secondaryPassword;
        this.iterations = iterations;
        try {
            java.nio.file.Files.createDirectories(historyDir);
        } catch (java.io.IOException e) {
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

    /** 记录新消息：录制开启时立即落盘，否则先缓存在内存。 */
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
        try {
            java.nio.file.Files.deleteIfExists(historyDir.resolve(fileName(key)));
        } catch (java.io.IOException e) {
            throw new Errors.Persistence("无法删除历史记录文件", e);
        }
    }

    /** 删除所有会话键匹配给定谓词的历史记录文件。 */
    public synchronized int deleteMatching(java.util.function.Predicate<String> matches) {
        int removed = 0;
        for (String key : new ArrayList<>(open.keySet())) {
            if (matches.test(key)) {
                open.remove(key);
                try {
                    if (java.nio.file.Files.deleteIfExists(historyDir.resolve(fileName(key)))) {
                        removed++;
                    }
                } catch (java.io.IOException e) {
                    throw new Errors.Persistence("无法删除历史记录文件", e);
                }
            }
        }
        // 对未打开的文件，通过解码文件名得到会话键再匹配。
        try (var stream = java.nio.file.Files.list(historyDir)) {
            for (Path f : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                String base = f.getFileName().toString();
                String key = decodeKey(base.substring(0, base.length() - 5));
                if (key != null && matches.test(key)) {
                    try {
                        if (java.nio.file.Files.deleteIfExists(f)) {
                            removed++;
                        }
                    } catch (java.io.IOException e) {
                        throw new Errors.Persistence("无法删除历史记录文件", e);
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new Errors.Persistence("无法列出历史记录目录", e);
        }
        return removed;
    }

    public synchronized int deleteAll() {
        int removed = 0;
        open.clear();
        try (var stream = java.nio.file.Files.list(historyDir)) {
            for (Path f : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    if (java.nio.file.Files.deleteIfExists(f)) {
                        removed++;
                    }
                } catch (java.io.IOException e) {
                    throw new Errors.Persistence("无法删除历史记录文件", e);
                }
            }
        } catch (java.io.IOException e) {
            throw new Errors.Persistence("无法列出历史记录目录", e);
        }
        return removed;
    }

    // ---------- 文件格式 ----------

    // 加载单个会话文件：支持二级密码加密的密封盒；解密失败按“非损坏”处理，
    // 其余解析错误则隔离文件并清空内存视图
    private void loadInto(Conversation c) {
        Path file = historyDir.resolve(fileName(c.key));
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

    // 保存：纯文本，或（启用二级密码时）AES-256-GCM 密封盒；均经原子写落盘
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
            sealed.put("salt", Base64.getEncoder().encodeToString(salt));
            sealed.put("iterations", iterations);
            sealed.put("box", Base64.getEncoder().encodeToString(box));
            payload = Json.writeBytes(sealed);
        } else {
            payload = plain;
        }
        AtomicFiles.write(historyDir.resolve(fileName(c.key)), payload);
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
