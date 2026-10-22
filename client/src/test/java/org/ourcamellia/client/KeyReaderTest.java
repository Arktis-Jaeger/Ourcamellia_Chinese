package org.ourcamellia.client;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 按键解码回归测试：方向键、独立 Esc、UTF-8、Shift+Enter 的 CSI-u 与
 * modifyOtherKeys 两种编码，以及括号粘贴。
 */
class KeyReaderTest {

    private static KeyReader reader(String bytes) {
        return new KeyReader(new ByteArrayInputStream(bytes.getBytes(StandardCharsets.UTF_8)));
    }

    private static KeyReader reader(byte[] bytes) {
        return new KeyReader(new ByteArrayInputStream(bytes));
    }

    @Test
    void plainEnterIsNotShiftEnter() throws Exception {
        KeyReader r = reader("\r");
        assertEquals(KeyReader.Type.ENTER, r.read(1000).type());
    }

    @Test
    void csiUShiftEnterIsRecognized() throws Exception {
        KeyReader r = reader("\u001b[13;2u");
        assertEquals(KeyReader.Type.SHIFT_ENTER, r.read(1000).type());
    }

    @Test
    void modifyOtherKeysShiftEnterIsRecognized() throws Exception {
        KeyReader r = reader("\u001b[27;2;13~");
        assertEquals(KeyReader.Type.SHIFT_ENTER, r.read(1000).type());
    }

    @Test
    void arrowsAndEditingKeys() throws Exception {
        assertEquals(KeyReader.Type.UP, reader("\u001b[A").read(1000).type());
        assertEquals(KeyReader.Type.DOWN, reader("\u001b[B").read(1000).type());
        assertEquals(KeyReader.Type.RIGHT, reader("\u001b[C").read(1000).type());
        assertEquals(KeyReader.Type.LEFT, reader("\u001b[D").read(1000).type());
        assertEquals(KeyReader.Type.HOME, reader("\u001b[H").read(1000).type());
        assertEquals(KeyReader.Type.END, reader("\u001b[F").read(1000).type());
        assertEquals(KeyReader.Type.DELETE, reader("\u001b[3~").read(1000).type());
        assertEquals(KeyReader.Type.PAGE_UP, reader("\u001b[5~").read(1000).type());
        assertEquals(KeyReader.Type.PAGE_DOWN, reader("\u001b[6~").read(1000).type());
        assertEquals(KeyReader.Type.SHIFT_TAB, reader("\u001b[Z").read(1000).type());
    }

    @Test
    void standaloneEscapeIsNotASequence() throws Exception {
        KeyReader r = reader(new byte[]{27});
        assertEquals(KeyReader.Type.ESC, r.read(1000).type());
    }

    @Test
    void utf8ChineseAndEmoji() throws Exception {
        KeyReader r = reader("你好😀");
        assertEquals("你", r.read(1000).text());
        assertEquals("好", r.read(1000).text());
        assertEquals("😀", r.read(1000).text());
    }

    @Test
    void ctrlShortcuts() throws Exception {
        assertEquals(KeyReader.Type.CTRL_C, reader(new byte[]{3}).read(1000).type());
        assertEquals(KeyReader.Type.CTRL_S, reader(new byte[]{19}).read(1000).type());
        assertEquals(KeyReader.Type.TAB, reader("\t").read(1000).type());
        assertEquals(KeyReader.Type.BACKSPACE, reader(new byte[]{127}).read(1000).type());
    }

    @Test
    void bracketedPasteIsOneEvent() throws Exception {
        KeyReader r = reader("\u001b[200~第一行\n第二行\u001b[201~");
        KeyReader.Key key = r.read(2000);
        assertEquals(KeyReader.Type.PASTE, key.type());
        assertTrue(key.text().contains("第一行\n第二行"));
    }

    @Test
    void unknownSequenceIsSwallowedNotLeaked() throws Exception {
        KeyReader r = reader("\u001b[999z");
        KeyReader.Key key = r.read(1000);
        assertEquals(KeyReader.Type.UNKNOWN, key.type());
    }
}
