package org.ourcamellia.core.persist;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ourcamellia.common.Ids;
import org.ourcamellia.common.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 便携式聊天记录目录测试：
 * 局域网/服务器分离、同一 IP 多服务器、服务器改名/换 IP 识别、
 * 旧扁平历史只读兼容、加密与损坏处理。
 */
class PortableHistoryStoreTest {

    private static final String PREFIX_A = "server:127.0.0.1:54530:";

    private static LocalHistoryStore.Entry entry(String text) {
        return new LocalHistoryStore.Entry(Ids.newId(), "peer", text, System.currentTimeMillis(), "in");
    }

    private static LocalHistoryStore.Entry entry(String sender, String text) {
        return new LocalHistoryStore.Entry(Ids.newId(), sender, text, System.currentTimeMillis(), "in");
    }

    @Test
    void localHistoryLivesUnderChatsLocal(@TempDir Path dir) {
        LocalHistoryStore store = LocalHistoryStore.portable(dir, true, null, 12_000);
        store.onMessage("local:bob", "bob", entry("hello"));
        Path file = store.pathFor("local:bob");
        assertTrue(Files.exists(file), "本地会话应写入便携目录");
        assertTrue(file.toString().contains("/chats/local/"));
        assertEquals("conversation.json", file.getFileName().toString());
        assertEquals(1, store.messages("local:bob").size());
    }

    @Test
    void serverHistoryLivesUnderServersIpAndServerId(@TempDir Path dir) throws Exception {
        LocalHistoryStore store = LocalHistoryStore.portable(dir, true, null, 12_000);
        store.registerServer(PREFIX_A, "127.0.0.1:54530", "Weser Town", "FP-A");
        store.onMessage(PREFIX_A + "priv:@bob", "@bob", entry("@bob", "hi"));

        Path serverDir = store.serverDirectory(PREFIX_A);
        assertNotNull(serverDir);
        assertTrue(serverDir.toString().contains("/chats/servers/127.0.0.1/"));
        assertTrue(serverDir.getFileName().toString().startsWith("srv-"));
        assertTrue(Files.exists(serverDir.resolve("server.json")));

        Path conv = store.pathFor(PREFIX_A + "priv:@bob");
        assertTrue(conv.toString().contains("/conversations/"));
        assertTrue(conv.getFileName().toString().startsWith("priv-"));

        JsonNode meta = Json.parse(Files.readAllBytes(serverDir.resolve("server.json")));
        assertEquals("Weser Town", meta.path("name").asText());
        assertEquals("127.0.0.1:54530", meta.path("address").asText());
        assertEquals("FP-A", meta.path("fingerprint").asText());
        assertEquals(serverDir.getFileName().toString(), meta.path("serverId").asText());
        assertTrue(meta.path("createdAt").asLong() > 0);
    }

    @Test
    void sameIpCanHoldMultipleServers(@TempDir Path dir) {
        LocalHistoryStore store = LocalHistoryStore.portable(dir, true, null, 12_000);
        String prefixB = "server:127.0.0.1:54531:";
        store.registerServer(PREFIX_A, "127.0.0.1:54530", "Weser Town", "FP-A");
        store.registerServer(prefixB, "127.0.0.1:54531", "Weser Town", "FP-B");
        store.onMessage(PREFIX_A + "priv:@bob", "@bob", entry("from A"));
        store.onMessage(prefixB + "priv:@bob", "@bob", entry("from B"));

        Path a = store.serverDirectory(PREFIX_A);
        Path b = store.serverDirectory(prefixB);
        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals(a, b, "同一 IP 下的两个服务器必须有独立目录");
        assertEquals("from A", store.messages(PREFIX_A + "priv:@bob").get(0).text());
        assertEquals("from B", store.messages(prefixB + "priv:@bob").get(0).text());
    }

    @Test
    void serverRenameKeepsSameDirectory(@TempDir Path dir) {
        LocalHistoryStore store = LocalHistoryStore.portable(dir, true, null, 12_000);
        store.registerServer(PREFIX_A, "127.0.0.1:54530", "Old Name", "FP-A");
        Path before = store.serverDirectory(PREFIX_A);
        store.onMessage(PREFIX_A + "priv:@bob", "@bob", entry("kept"));
        LocalHistoryStore reopened = LocalHistoryStore.portable(dir, true, null, 12_000);
        reopened.registerServer(PREFIX_A, "127.0.0.1:54530", "New Name", "FP-A");
        assertEquals(before, reopened.serverDirectory(PREFIX_A),
                "服务器改名不能改变由证书指纹派生的目录");
        assertEquals("kept", reopened.messages(PREFIX_A + "priv:@bob").get(0).text());
        assertTrue(Files.exists(before.resolve("server.json")));
    }

    @Test
    void manualIpMoveIsRecognizedAfterReconnect(@TempDir Path dir) throws Exception {
        LocalHistoryStore store = LocalHistoryStore.portable(dir, true, null, 12_000);
        store.registerServer(PREFIX_A, "127.0.0.1:54530", "Weser Town", "FP-A");
        store.onMessage(PREFIX_A + "priv:@bob", "@bob", entry("migrated"));
        Path oldDir = store.serverDirectory(PREFIX_A);

        // 模拟用户手工把整个服务器目录移动到新 IP 下（不复制、不合并）。
        Path newIpRoot = dir.resolve("chats").resolve("servers").resolve("10.0.0.9");
        Files.createDirectories(newIpRoot);
        Path moved = newIpRoot.resolve(oldDir.getFileName().toString());
        Files.move(oldDir, moved);

        String prefixNew = "server:10.0.0.9:54530:";
        LocalHistoryStore reopened = LocalHistoryStore.portable(dir, true, null, 12_000);
        reopened.registerServer(prefixNew, "10.0.0.9:54530", "Weser Town", "FP-A");
        assertEquals(moved, reopened.serverDirectory(prefixNew));
        assertEquals("migrated", reopened.messages(prefixNew + "priv:@bob").get(0).text());
        // 元数据中的连接地址更新为新地址，但原始记录没有被删除或合并。
        JsonNode meta = Json.parse(Files.readAllBytes(moved.resolve("server.json")));
        assertEquals("10.0.0.9:54530", meta.path("address").asText());
    }

    @Test
    void movingOneServerDoesNotAffectOtherServersOnSameIp(@TempDir Path dir) throws Exception {
        LocalHistoryStore store = LocalHistoryStore.portable(dir, true, null, 12_000);
        String prefixB = "server:127.0.0.1:54531:";
        store.registerServer(PREFIX_A, "127.0.0.1:54530", "Server A", "FP-A");
        store.registerServer(prefixB, "127.0.0.1:54531", "Server B", "FP-B");
        store.onMessage(PREFIX_A + "priv:@bob", "@bob", entry("A record"));
        store.onMessage(prefixB + "priv:@bob", "@bob", entry("B record"));
        Path dirA = store.serverDirectory(PREFIX_A);
        Path dirB = store.serverDirectory(prefixB);

        Path newIp = dir.resolve("chats").resolve("servers").resolve("10.0.0.8");
        Files.createDirectories(newIp);
        Path movedA = newIp.resolve(dirA.getFileName().toString());
        Files.move(dirA, movedA);

        String prefixNewA = "server:10.0.0.8:54530:";
        LocalHistoryStore reopened = LocalHistoryStore.portable(dir, true, null, 12_000);
        reopened.registerServer(prefixNewA, "10.0.0.8:54530", "Server A", "FP-A");
        assertEquals(movedA, reopened.serverDirectory(prefixNewA));
        assertEquals("A record", reopened.messages(prefixNewA + "priv:@bob").get(0).text());
        // 重新连接 B 时，ServerSession 会按同样的指纹重新注册；之后 B 的记录仍可读。
        reopened.registerServer(prefixB, "127.0.0.1:54531", "Server B", "FP-B");
        assertTrue(Files.exists(dirB.resolve("server.json")));
        assertEquals("B record", reopened.messages(prefixB + "priv:@bob").get(0).text());
    }

    @Test
    void legacyFlatHistoryIsStillReadableAndNotDeleted(@TempDir Path dir) throws Exception {
        // 旧版本布局：<data>/history/<base64(key)>.json
        LocalHistoryStore legacy = new LocalHistoryStore(dir.resolve("history"), true, null, 12_000);
        legacy.onMessage("local:bob", "bob", entry("old record"));
        Path legacyFile = dir.resolve("history").resolve(LocalHistoryStore.fileName("local:bob"));
        assertTrue(Files.exists(legacyFile));

        LocalHistoryStore portable = LocalHistoryStore.portable(dir, true, null, 12_000);
        assertEquals("old record", portable.messages("local:bob").get(0).text(),
                "旧历史必须可读");
        assertTrue(Files.exists(legacyFile), "读取旧历史不得删除或移动原文件");

        // 下一次保存才写入新位置；旧文件仍然保留。
        portable.setRecording("local:bob", "bob", false);
        Path newFile = portable.pathFor("local:bob");
        assertTrue(Files.exists(newFile));
        assertTrue(Files.exists(legacyFile));
    }

    @Test
    void deleteAllKeepsServerMetadataAndCorruptFiles(@TempDir Path dir) throws Exception {
        LocalHistoryStore store = LocalHistoryStore.portable(dir, true, null, 12_000);
        store.registerServer(PREFIX_A, "127.0.0.1:54530", "Weser Town", "FP-A");
        store.onMessage("local:bob", "bob", entry("b1"));
        store.onMessage(PREFIX_A + "priv:@bob", "@bob", entry("s1"));
        Path serverDir = store.serverDirectory(PREFIX_A);

        // 制造一个隔离文件，删除全部历史时不得把它当作会话文件删除。
        Path corrupt = serverDir.resolve("conversations").resolve("x.json.corrupt-1");
        Files.createDirectories(corrupt.getParent());
        Files.writeString(corrupt, "x");

        int removed = store.deleteAll();
        assertEquals(2, removed);
        assertTrue(Files.exists(serverDir.resolve("server.json")),
                "server.json 是元数据，不是聊天记录");
        assertTrue(Files.exists(corrupt), "隔离的损坏文件必须保留");
        assertTrue(store.messages("local:bob").isEmpty());
    }

    @Test
    void temporaryKeyTitleIsReplacedByRealTitle(@TempDir Path dir) throws Exception {
        LocalHistoryStore store = LocalHistoryStore.portable(dir, true, null, 12_000);
        // 模拟 UI 先读取历史（messages 会用会话键作为临时标题），随后才发送消息。
        assertTrue(store.messages("local:bob").isEmpty());
        store.onMessage("local:bob", "bob", entry("hello"));
        JsonNode root = Json.parse(Files.readAllBytes(store.pathFor("local:bob")));
        assertEquals("bob", root.path("title").asText(),
                "历史文件的 title 不应残留 internal conversation key");
    }

    @Test
    void deleteMatchingUsesStoredKeys(@TempDir Path dir) {
        LocalHistoryStore store = LocalHistoryStore.portable(dir, true, null, 12_000);
        store.onMessage("local:bob", "bob", entry("b1"));
        store.onMessage("local:carol", "carol", entry("c1"));
        int removed = store.deleteMatching(key -> key.equals("local:carol"));
        assertEquals(1, removed);
        assertEquals(1, store.messages("local:bob").size());
        assertTrue(store.messages("local:carol").isEmpty());
    }

    @Test
    void encryptedPortableFileKeepsKeyButNotPlaintext(@TempDir Path dir) throws Exception {
        LocalHistoryStore store = LocalHistoryStore.portable(dir, true, "hunter2", 12_000);
        store.onMessage("local:bob", "bob", entry("secret text"));
        Path file = store.pathFor("local:bob");
        String raw = Files.readString(file);
        assertFalse(raw.contains("secret text"));
        assertTrue(raw.contains("\"protected\":true"));
        assertTrue(raw.contains("\"key\":\"local:bob\""),
                "密封盒外层保存会话键，删除时无需解密即可定位记录");

        LocalHistoryStore reopen = LocalHistoryStore.portable(dir, true, "hunter2", 12_000);
        assertEquals("secret text", reopen.messages("local:bob").get(0).text());
        LocalHistoryStore wrong = LocalHistoryStore.portable(dir, true, "wrong", 12_000);
        assertTrue(wrong.messages("local:bob").isEmpty());
        assertTrue(Files.exists(file), "密码错误绝不能修改/删除原文件");
    }

    @Test
    void corruptPortableFileIsQuarantinedNotOverwritten(@TempDir Path dir) throws Exception {
        LocalHistoryStore store = LocalHistoryStore.portable(dir, true, null, 12_000);
        Path file = store.pathFor("local:bob");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json");
        assertTrue(store.messages("local:bob").isEmpty());
        try (var stream = Files.list(file.getParent())) {
            List<Path> files = stream.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().contains(".corrupt-"));
        }
    }
}
