package org.ourcamellia.core.persist;

import org.ourcamellia.common.Errors;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 便携式聊天记录目录的路径规则。
 *
 * <p>目录结构（相对于数据目录）：
 * <pre>
 * chats/
 *   local/
 *     &lt;本地会话标识&gt;/conversation.json
 *   servers/
 *     &lt;IP 或主机名&gt;/
 *       &lt;服务器标识&gt;/
 *         server.json              # 元数据：名称、地址、指纹、创建时间
 *         conversations/
 *           &lt;会话标识&gt;.json      # 每个私聊/群聊/公告频道一个文件
 * </pre>
 *
 * <p>为什么同时使用 IP 目录和服务器标识：
 * <ul>
 *   <li>只用服务器名称不行：不同服务器可能重名，名称也可能变化；</li>
 *   <li>只用 IP 不行：同一主机上可能运行多个服务器实例；</li>
 *   <li>因此 IP 目录用于“用户手工迁移时能直观找到位置”，服务器标识用于
 *       真正区分实例。服务器标识由 TLS 证书指纹派生（没有指纹时退化为名称的
 *       哈希），这样服务器改名或换 IP 都不会改变标识。</li>
 * </ul>
 *
 * <p>所有用户提供的名称（对端名、群名、服务器名、主机名）在拼接为路径前都经过
 * {@link #safeSegment(String)}：只保留 ASCII 字母、数字、点、下划线、连字符；
 * 其他字符替换为下划线。若替换改变了原值，或者结果过长，会追加原值的短哈希，
 * 以避免“两个不同名称被替换成同一个目录”而造成记录混淆。这样也排除了
 * <code>..</code>、<code>/</code>、反斜杠等路径穿越输入。
 */
public final class ChatPaths {

    /** server.json 的 Schema 版本；与聊天文件自身的版本相互独立。 */
    public static final int SERVER_SCHEMA = 1;

    private final Path dataDir;
    private final Path chatsRoot;
    private final Path localRoot;
    private final Path serversRoot;

    public ChatPaths(Path dataDir) {
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.chatsRoot = this.dataDir.resolve("chats");
        this.localRoot = chatsRoot.resolve("local");
        this.serversRoot = chatsRoot.resolve("servers");
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path chatsRoot() {
        return chatsRoot;
    }

    public Path localRoot() {
        return localRoot;
    }

    public Path serversRoot() {
        return serversRoot;
    }

    /** 本地会话目录：chats/local/&lt;安全标识&gt;/ */
    public Path localConversationDir(String peer) {
        return localRoot.resolve(safeSegment(peer));
    }

    /** 本地会话文件：chats/local/&lt;安全标识&gt;/conversation.json */
    public Path localConversationFile(String peer) {
        return localConversationDir(peer).resolve("conversation.json");
    }

    /** 服务器目录：chats/servers/&lt;IP&gt;/&lt;服务器标识&gt;/ */
    public Path serverDir(String host, String serverId) {
        return serversRoot.resolve(safeSegment(host)).resolve(safeSegment(serverId));
    }

    public Path serverMetaFile(String host, String serverId) {
        return serverDir(host, serverId).resolve("server.json");
    }

    public Path serverConversationsDir(String host, String serverId) {
        return serverDir(host, serverId).resolve("conversations");
    }

    /**
     * 服务器会话文件。会话标识必须由调用方生成，且已经区分类别
     * （priv-/group-/channel），避免私聊用户与同名群组落到同一文件。
     */
    public Path serverConversationFile(String host, String serverId, String conversationName) {
        return serverConversationsDir(host, serverId)
                .resolve(safeSegment(conversationName) + ".json");
    }

    /**
     * 从连接地址中取出用于目录的主机部分。
     *
     * <p>例如 <code>192.168.1.20:54530</code> → <code>192.168.1.20</code>；
     * <code>[::1]:54530</code> → <code>::1</code>。地址没有端口时原样返回。
     * 解析失败不抛异常——目录标识仍会经过安全化处理，避免启动失败。
     */
    public static String hostOf(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        String a = address.trim();
        if (a.startsWith("[")) {
            int end = a.indexOf(']');
            if (end > 1) {
                return a.substring(1, end);
            }
        }
        int colon = a.lastIndexOf(':');
        if (colon > 0 && colon < a.length() - 1 && a.indexOf(':') == colon) {
            return a.substring(0, colon);
        }
        return a;
    }

    /**
     * 生成稳定的服务器标识：<code>srv-</code> + 12 位十六进制哈希。
     *
     * <p>优先使用 TLS 证书指纹：它标识服务器实例，改 IP 或改名都不变；
     * 没有指纹（例如测试中的非 TLS 链路）时退化为名称哈希。没有名称与指纹时
     * 使用固定前缀，调用方应尽快在 WELCOME 后重新注册以补全信息。
     */
    public static String serverId(String serverName, String fingerprint) {
        String material = (fingerprint != null && !fingerprint.isBlank())
                ? "fp:" + fingerprint.trim()
                : "name:" + (serverName == null ? "" : serverName);
        return "srv-" + shortHash(material, 12);
    }

    /**
     * 把任意文本转换为安全的单层路径片段。
     *
     * <p>规则：
     * <ol>
     *   <li>只保留 <code>[A-Za-z0-9._-]</code>，其他字符逐个替换为下划线；</li>
     *   <li>去掉开头/结尾的点，防止 <code>.</code>/<code>..</code> 等特殊目录名；</li>
     *   <li>结果为空时使用 <code>_</code>；</li>
     *   <li>如果发生过替换、结果与原文不同，或长度超过 48，则追加原值的短哈希，
     *       保证不同的原始名称不会映射到同一路径；</li>
     *   <li>最终长度限制在 64 个字符以内。</li>
     * </ol>
     */
    public static String safeSegment(String raw) {
        String original = raw == null ? "" : raw;
        StringBuilder sb = new StringBuilder(original.length());
        boolean replaced = false;
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (allowed) {
                sb.append(c);
            } else {
                sb.append('_');
                replaced = true;
            }
        }
        String base = sb.toString().replaceAll("^\\.+", "").replaceAll("\\.+$", "");
        if (base.contains("..")) {
            base = base.replace("..", "_");
            replaced = true;
        }
        if (base.isBlank()) {
            base = "_";
        }
        if (replaced || !base.equals(original)) {
            base = base + "-" + shortHash(original, 8);
        }
        if (base.length() > 64) {
            base = base.substring(0, 55) + "-" + shortHash(original, 8);
        }
        return base;
    }

    /**
     * 由会话键生成文件名主干（不含 .json）。
     *
     * <p>会话语义由业务层定义（ServerSession.convKey / LocalSession.convKey）：
     * <ul>
     *   <li><code>local:&lt;peer&gt;</code></li>
     *   <li><code>server:&lt;addr&gt;:priv:&lt;peer&gt;</code></li>
     *   <li><code>server:&lt;addr&gt;:grp:&lt;groupId&gt;</code></li>
     *   <li><code>server:&lt;addr&gt;:channel</code></li>
     * </ul>
     * 这里只做命名，不解析地址（地址里可能含冒号）。
     */
    public static String conversationName(String key, String serverKeyPrefix) {
        if (key == null || key.isEmpty()) {
            return "unknown";
        }
        if (key.startsWith("local:")) {
            return safeSegment(key.substring("local:".length()));
        }
        String rest = key;
        if (serverKeyPrefix != null && !serverKeyPrefix.isEmpty() && key.startsWith(serverKeyPrefix)) {
            rest = key.substring(serverKeyPrefix.length());
        } else {
            // 无法取得前缀时，根据业务层的固定分隔标记去掉前半段。
            int priv = key.lastIndexOf(":priv:");
            int grp = key.lastIndexOf(":grp:");
            int channel = key.endsWith(":channel") ? key.length() - ":channel".length() : -1;
            if (channel > priv && channel > grp) {
                rest = "channel";
            } else if (priv > grp) {
                rest = "priv:" + key.substring(priv + ":priv:".length());
            } else if (grp >= 0) {
                rest = "grp:" + key.substring(grp + ":grp:".length());
            }
        }
        if (rest.equals("channel")) {
            return "channel";
        }
        if (rest.startsWith("priv:")) {
            return "priv-" + safeSegment(rest.substring(5));
        }
        if (rest.startsWith("grp:")) {
            return "group-" + safeSegment(rest.substring(4));
        }
        return "conv-" + safeSegment(rest);
    }

    /** 计算短哈希（SHA-256 前 N 个十六进制字符）；算法不可用时返回稳定占位符。 */
    public static String shortHash(String value, int hexChars) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.substring(0, Math.min(hexChars, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            // 标准 JDK 必定提供 SHA-256；此分支仅为极端裁剪运行时保留。
            return Integer.toHexString(value.hashCode());
        }
    }

    /**
     * 校验目录是否位于禁止写入的位置（target/ 编译输出或 Study/ 学习材料）。
     * 返回 null 表示允许，否则返回中文原因。
     */
    public static String forbiddenLocation(Path path) {
        for (Path part : path.toAbsolutePath().normalize()) {
            String name = part.toString();
            if (name.equals("target") || name.equals("Study")) {
                return "数据目录不能位于 " + name + "/（编译输出或学习材料目录）";
            }
        }
        return null;
    }

    /** 组合数据目录并拒绝明显错误的位置。 */
    public static Path resolveDataDir(Path dataDir) {
        Path absolute = dataDir.toAbsolutePath().normalize();
        String forbidden = forbiddenLocation(absolute);
        if (forbidden != null) {
            throw new Errors.Usage(forbidden);
        }
        return absolute;
    }
}
