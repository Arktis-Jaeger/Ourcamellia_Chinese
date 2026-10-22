package org.ourcamellia.core.persist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 便携式目录的命名与安全规则。 */
class ChatPathsTest {

    @Test
    void safeSegmentRemovesTraversalAndSeparators() {
        String segment = ChatPaths.safeSegment("../../etc/passwd");
        assertFalse(segment.contains("/"));
        assertFalse(segment.contains("\\"));
        assertFalse(segment.startsWith("."));
        assertFalse(segment.contains(".."));
        assertTrue(segment.length() <= 64);
    }

    @Test
    void differentNamesDoNotCollide() {
        assertNotEquals(ChatPaths.safeSegment("a/b"), ChatPaths.safeSegment("a?b"));
        assertNotEquals(ChatPaths.safeSegment("@bob"), ChatPaths.safeSegment("bob"));
        assertEquals(ChatPaths.safeSegment("bob"), ChatPaths.safeSegment("bob"));
    }

    @Test
    void localizedNamesRemainDistinct() {
        String alice = ChatPaths.safeSegment("阿丽丝");
        String bob = ChatPaths.safeSegment("鲍勃");
        assertNotEquals(alice, bob);
        assertTrue(alice.length() <= 64);
    }

    @Test
    void hostExtraction() {
        assertEquals("192.168.1.20", ChatPaths.hostOf("192.168.1.20:54530"));
        assertEquals("::1", ChatPaths.hostOf("[::1]:54530"));
        assertEquals("server.example", ChatPaths.hostOf("server.example:54530"));
        assertEquals("unknown", ChatPaths.hostOf(""));
    }

    @Test
    void conversationNamesAreCategorized() {
        String prefix = "server:127.0.0.1:54530:";
        assertTrue(ChatPaths.conversationName(prefix + "priv:@bob", prefix).startsWith("priv-"));
        assertTrue(ChatPaths.conversationName(prefix + "grp:g1", prefix).startsWith("group-"));
        assertEquals("channel", ChatPaths.conversationName(prefix + "channel", prefix));
        assertTrue(ChatPaths.conversationName("local:bob", null).startsWith("bob"));
    }

    @Test
    void serverIdUsesFingerprintNotAddress() {
        String a = ChatPaths.serverId("Weser Town", "FP-1");
        String b = ChatPaths.serverId("Renamed", "FP-1");
        assertEquals(a, b, "同一证书指纹改名后仍是同一服务器标识");
        assertNotEquals(a, ChatPaths.serverId("Weser Town", "FP-2"));
        assertTrue(a.startsWith("srv-"));
    }

    @Test
    void forbiddenLocationsAreRejected(@TempDir Path dir) {
        assertNotNull(ChatPaths.forbiddenLocation(dir.resolve("project").resolve("target").resolve("data")));
        assertNotNull(ChatPaths.forbiddenLocation(dir.resolve("Study").resolve("data")));
        assertNull(ChatPaths.forbiddenLocation(dir.resolve("data")));
    }

    @Test
    void pathsAreCreatedUnderChats(@TempDir Path dir) throws Exception {
        ChatPaths paths = new ChatPaths(dir);
        assertEquals(dir.resolve("chats").toAbsolutePath().normalize(), paths.chatsRoot());
        Files.createDirectories(paths.localRoot());
        assertTrue(Files.isDirectory(dir.resolve("chats").resolve("local")));
    }
}
