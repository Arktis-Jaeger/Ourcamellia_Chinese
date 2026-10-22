package org.ourcamellia.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 多行编辑器：换行、光标移动、UTF-8、粘贴净化与草稿语义的底层行为。 */
class TextEditorTest {

    @Test
    void multilineInsertAndNewline() {
        TextEditor e = new TextEditor(true);
        e.insertText("hello");
        e.newline();
        e.insertText("world");
        assertEquals("hello\nworld", e.text());
        assertEquals(2, e.lineCount());
    }

    @Test
    void newlineIsIgnoredInSingleLineEditor() {
        TextEditor e = new TextEditor(false);
        e.insertText("a");
        e.newline();
        e.insertText("b");
        assertEquals("ab", e.text(), "单行命令栏不能插入换行");
    }

    @Test
    void cursorMovementAcrossLines() {
        TextEditor e = new TextEditor(true);
        e.setText("ab\ncd");
        assertEquals(5, e.cursor());
        e.up();
        assertEquals(2, e.cursor());
        e.home();
        assertEquals(0, e.cursor());
        e.end();
        assertEquals(2, e.cursor());
        e.down();
        assertEquals(5, e.cursor());
    }

    @Test
    void backspaceAndDeleteDoNotSplitSurrogatePairs() {
        TextEditor e = new TextEditor(true);
        e.insertText("😀x");
        e.backspace(); // 删除行尾的 x
        assertEquals("😀", e.text());
        e.backspace(); // 删除整个表情符号
        assertEquals("", e.text());

        e.setText("😀x");
        e.home();
        e.delete(); // 删除表情符号
        assertEquals("x", e.text());
    }

    @Test
    void controlCharactersAreStripped() {
        TextEditor e = new TextEditor(true);
        e.insertText("a\u001b[31mb\tc");
        assertEquals("a[31mb c", e.text());
        assertFalse(e.text().contains("\u001b"));
    }

    @Test
    void setTextAndClear() {
        TextEditor e = new TextEditor(true);
        e.setText("one\ntwo");
        assertEquals(2, e.lineCount());
        e.clear();
        assertTrue(e.isEmpty());
        assertEquals(0, e.cursor());
    }

    @Test
    void pasteWithNewlinesIsKeptInMultilineMode() {
        TextEditor e = new TextEditor(true);
        e.insertText("a\r\nb\nc");
        assertEquals("a\nb\nc", e.text());
    }
}
