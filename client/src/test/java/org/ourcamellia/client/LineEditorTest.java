package org.ourcamellia.client;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

/** LineEditor logic tests: editing, cursor movement, UTF-8, and submit behavior. */
class LineEditorTest {

    private static void feed(LineEditor e, String text) {
        for (byte b : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            e.feed(b & 0xFF);
        }
    }

    @Test
    void typingAndSubmit() {
        LineEditor e = new LineEditor();
        feed(e, "hello");
        assertEquals("hello", e.text());
        assertEquals("hello", e.feed(13));
        assertEquals("", e.text());
    }

    @Test
    void backspaceAndCursor() {
        LineEditor e = new LineEditor();
        feed(e, "abc");
        e.feed(127);
        assertEquals("ab", e.text());
        e.escape("D"); // left
        e.escape("D");
        feed(e, "X"); // insert at cursor
        assertEquals("Xab", e.text());
        e.escape("F"); // end
        feed(e, "Y");
        assertEquals("XabY", e.text());
    }

    @Test
    void ctrlCAndCtrlU() {
        LineEditor e = new LineEditor();
        feed(e, "abc");
        e.feed(3);
        assertEquals("", e.text());
        feed(e, "xyz");
        e.feed(21);
        assertEquals("", e.text());
    }

    @Test
    void utf8Input() {
        LineEditor e = new LineEditor();
        feed(e, "你好");
        assertEquals("你好", e.text());
        feed(e, "组");
        assertEquals("你好组", e.text());
    }

    @Test
    void ctrlDOnEmptyIsEof() {
        LineEditor e = new LineEditor();
        assertEquals("", e.feed(4)); // EOF signal on empty buffer
        feed(e, "abc");
        e.escape("H"); // cursor to start
        assertNull(e.feed(4)); // ctrl-D deletes the char under the cursor
        assertEquals("bc", e.text());
    }

    @Test
    void renderStripsHostileControlChars() {
        LineEditor e = new LineEditor();
        feed(e, "hi\u001bthere"); // embedded ESC must never reach the terminal
        StringWriter sw = new StringWriter();
        e.render(new PrintWriter(sw), "> ", 120);
        assertFalse(sw.toString().contains("hi\u001bthere"));
        assertTrue(sw.toString().contains("hithere"),
                "hostile control characters must never be rendered");
    }

    // ---- 回归测试：终端宽度钳制（缺陷 B：拖动窗口导致 StringIndexOutOfBounds 崩溃）----

    @Test
    void renderWithNonPositiveWidthDoesNotCrash() {
        // 终端宽度 ≤ 0 时（极端窄窗/拖动瞬间）渲染必须稳健：不抛异常、不产生
        // 越界字符；旧实现 StringIndexOutOfBoundsException 会杀死整个进程。
        for (int width : new int[]{0, -1, -47}) {
            LineEditor e = new LineEditor();
            feed(e, "有些内容 here to render");
            StringWriter sw = new StringWriter();
            assertDoesNotThrow(() -> e.render(new PrintWriter(sw), "> ", width),
                    "width=" + width + " 时渲染不得抛异常");
            // 至少输出 prompt（或安全回退内容），且不得包含原始控制序列。
            assertFalse(sw.toString().contains("\u001b[?25h") || sw.toString().contains("有些内容 here to render\u0000"),
                    "width=" + width + " 时输出必须安全钳制");
        }
    }

    @Test
    void renderLongPromptInNarrowWindowDoesNotCrash() {
        // 场景：窄窗口(20 列) + 长提示/长内容 —— 拖动缩小时的典型崩溃路径。
        LineEditor e = new LineEditor();
        feed(e, "一个非常非常长的会话标题用于触发超长渲染路径");
        StringWriter sw = new StringWriter();
        assertDoesNotThrow(() -> e.render(new PrintWriter(sw), "▲ ", 20));
        e.escape("D"); // 光标移动也不得崩溃
        e.escape("5");
        assertDoesNotThrow(() -> e.render(new PrintWriter(sw), "▲ ", 20));
    }

    @Test
    void renderNeverMutatesBuffer() {
        // 渲染是只读视图：无论宽度如何，缓冲区文本与光标必须保持不变。
        LineEditor e = new LineEditor();
        feed(e, "保持原样 remain");
        e.escape("D");
        e.escape("D"); // 光标在中间
        String before = e.text();
        for (int width : new int[]{-5, 0, 1, 2, 10, 80}) {
            StringWriter sw = new StringWriter();
            e.render(new PrintWriter(sw), "> ", width);
            assertEquals(before, e.text(), "width=" + width + " 时缓冲区不得被渲染改变");
        }
    }
}
