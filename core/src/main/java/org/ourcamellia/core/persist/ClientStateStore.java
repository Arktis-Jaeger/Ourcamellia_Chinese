package org.ourcamellia.core.persist;

import com.fasterxml.jackson.databind.JsonNode;
import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 客户端持久化状态（与配置无关的生成状态）：本地身份、好友关系、TOFU 证书
 * 固定值、本地通知、本地群组、服务器备注。以单个 JSON 文件持久化，采用原子
 * 替换与 Schema 版本号；文件损坏时先隔离（quarantine）再以空状态启动
 * （文档已记录的恢复行为——绝不静默截断一个完好的文件）。
 */
public final class ClientStateStore {

    public static final String FILE_NAME = "state.json";
    public static final int SCHEMA_VERSION = 1;

    /** 从本地观察者视角记录的好友记录。 */
    public record FriendRec(String name, String state, String address, String fingerprint, long ts) {}

    /** 首次信任（TOFU）固定：证书指纹 → 曾以该指纹出现的名称与地址。 */
    public record PinRec(String fingerprint, String name, List<String> addresses) {}

    public record NotifRec(String id, String kind, String state, String from, String text,
                           long createdAt, long expiresAt, String refId) {}

    public record GroupRec(String id, String name, String privacy, String owner,
                           List<String> members, long createdAt) {}

    private final Path file;
    private State state = new State();

    public static final class State {
        public int v = SCHEMA_VERSION;
        public String localName;
        public String localFingerprint;
        public String x25519PrivateKey; // base64 PKCS#8；用于加密群组密钥交换
        public String x25519PublicKey;  // base64 原始 32 字节
        public LinkedHashMap<String, FriendRec> friends = new LinkedHashMap<>();
        public LinkedHashMap<String, PinRec> pins = new LinkedHashMap<>();
        public List<NotifRec> notifications = new ArrayList<>();
        public List<GroupRec> groups = new ArrayList<>();
        public LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
    }

    public ClientStateStore(Path dataDir) {
        this.file = dataDir.resolve(FILE_NAME);
        load();
    }

    // 加载：解析并校验 Schema；损坏文件先隔离再重置为空状态，保证不会截断完好文件
    public synchronized void load() {
        byte[] raw = AtomicFiles.readOrNull(file);
        if (raw == null) {
            return;
        }
        try {
            JsonNode root = Json.parse(raw);
            long v = Json.reqLong(root, "v");
            if (v != SCHEMA_VERSION) {
                throw new Errors.Persistence("不支持的状态文件 Schema 版本 " + v);
            }
            State s = Json.convert(root, State.class);
            if (s.friends == null) s.friends = new LinkedHashMap<>();
            if (s.pins == null) s.pins = new LinkedHashMap<>();
            if (s.notifications == null) s.notifications = new ArrayList<>();
            if (s.groups == null) s.groups = new ArrayList<>();
            if (s.aliases == null) s.aliases = new LinkedHashMap<>();
            this.state = s;
        } catch (RuntimeException e) {
            AtomicFiles.quarantine(file, "状态文件损坏");
            this.state = new State();
        }
    }

    // 保存：整文件原子写入，避免写一半造成损坏
    public synchronized void save() {
        AtomicFiles.writeString(file, Json.write(state));
    }

    public synchronized State state() {
        return state;
    }

    // ---------- 辅助方法 ----------

    public synchronized FriendRec friend(String name) {
        return state.friends.get(name);
    }

    public synchronized void putFriend(FriendRec rec) {
        state.friends.put(rec.name(), rec);
        save();
    }

    public synchronized void removeFriend(String name) {
        if (state.friends.remove(name) != null) {
            save();
        }
    }

    public synchronized PinRec pin(String fingerprint) {
        return state.pins.get(fingerprint);
    }

    public synchronized void putPin(String fingerprint, String name, String address) {
        // 记录某指纹出现过的所有地址（对端换地址后仍可识别）
        PinRec existing = state.pins.get(fingerprint);
        if (existing == null) {
            List<String> addrs = new ArrayList<>();
            if (address != null) addrs.add(address);
            state.pins.put(fingerprint, new PinRec(fingerprint, name, addrs));
        } else {
            List<String> addrs = new ArrayList<>(existing.addresses());
            if (address != null && !addrs.contains(address)) {
                addrs.add(address);
            }
            state.pins.put(fingerprint, new PinRec(fingerprint,
                    existing.name() != null ? existing.name() : name, addrs));
        }
        save();
    }

    public synchronized void addNotification(NotifRec n) {
        // 用稳定 id 去重
        for (NotifRec e : state.notifications) {
            if (e.id().equals(n.id())) {
                return;
            }
        }
        state.notifications.add(0, n);
        save();
    }

    public synchronized boolean updateNotification(String id, String newState) {
        for (int i = 0; i < state.notifications.size(); i++) {
            NotifRec n = state.notifications.get(i);
            if (n.id().equals(id)) {
                state.notifications.set(i, new NotifRec(n.id(), n.kind(), newState, n.from(),
                        n.text(), n.createdAt(), n.expiresAt(), n.refId()));
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized void purgeExpiredNotifications(long now) {
        boolean changed = state.notifications.removeIf(n -> n.expiresAt() > 0 && now > n.expiresAt());
        if (changed) {
            save();
        }
    }

    /** 供类外读者使用的线程安全快照。 */
    public synchronized List<NotifRec> notificationSnapshot() {
        return List.copyOf(state.notifications);
    }

    public synchronized GroupRec group(String id) {
        return state.groups.stream().filter(g -> g.id().equals(id)).findFirst().orElse(null);
    }

    public synchronized void putGroup(GroupRec rec) {
        state.groups.removeIf(g -> g.id().equals(rec.id()));
        state.groups.add(rec);
        save();
    }

    public synchronized void removeGroup(String id) {
        if (state.groups.removeIf(g -> g.id().equals(id))) {
            save();
        }
    }

    public synchronized String alias(String serverAddr) {
        return state.aliases.get(serverAddr);
    }

    public synchronized void putAlias(String serverAddr, String alias) {
        if (alias == null || alias.isBlank()) {
            state.aliases.remove(serverAddr);
        } else {
            state.aliases.put(serverAddr, alias);
        }
        save();
    }

    public synchronized void setLocalIdentity(String name, String fingerprint) {
        state.localName = name;
        state.localFingerprint = fingerprint;
        save();
    }

    /** 返回客户端的 X25519 密钥对；首次使用时创建并持久化。 */
    public synchronized java.security.KeyPair x25519KeyPair() {
        try {
            if (state.x25519PrivateKey != null && state.x25519PublicKey != null) {
                byte[] pkcs8 = java.util.Base64.getDecoder().decode(state.x25519PrivateKey);
                byte[] rawPub = java.util.Base64.getDecoder().decode(state.x25519PublicKey);
                var priv = java.security.KeyFactory.getInstance("X25519").generatePrivate(
                        new java.security.spec.PKCS8EncodedKeySpec(pkcs8));
                var pub = org.ourcamellia.core.crypto.GroupCrypto.publicKeyFrom(rawPub);
                return new java.security.KeyPair(pub, priv);
            }
            var kpg = java.security.KeyPairGenerator.getInstance("X25519");
            java.security.KeyPair kp = kpg.generateKeyPair();
            state.x25519PrivateKey = java.util.Base64.getEncoder()
                    .encodeToString(kp.getPrivate().getEncoded());
            state.x25519PublicKey = java.util.Base64.getEncoder()
                    .encodeToString(org.ourcamellia.core.crypto.GroupCrypto.publicKeyBytes(kp));
            save();
            return kp;
        } catch (java.security.GeneralSecurityException e) {
            throw new Errors.Internal("X25519 算法在当前运行环境不可用", e);
        }
    }
}
