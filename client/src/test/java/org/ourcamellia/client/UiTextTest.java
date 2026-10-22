package org.ourcamellia.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 终端宽度计算、分隔线、折行与左右对齐。 */
class UiTextTest {

    @Test
    void cjkCountsAsTwoColumns() {
        assertEquals(4, UiText.displayWidth("你好"));
        assertEquals(3, UiText.displayWidth("a你"));
        assertEquals(2, UiText.displayWidth("😀"));
        assertEquals(0, UiText.displayWidth(""));
    }

    @Test
    void separatorFillsExactWidth() {
        for (int w : new int[]{1, 10, 80, 120}) {
            assertEquals(w, UiText.line('=', w).length());
            assertEquals(w, UiText.line('-', w).length());
        }
        assertEquals(1, UiText.line('=', 0).length(), "非法宽度也必须生成至少 1 列");
    }

    @Test
    void truncateNeverSplitsWideCharacter() {
        assertEquals("你", UiText.truncate("你好", 2));
        assertEquals("你", UiText.truncate("你好", 3));
        assertEquals("", UiText.truncate("你好", 1));
    }

    @Test
    void wrapPreservesExplicitNewlines() {
        List<String> lines = UiText.wrap("a\nb", 10);
        assertEquals(List.of("a", "b"), lines);
    }

    @Test
    void wrapChineseByDisplayWidth() {
        List<String> lines = UiText.wrap("你好世界", 4);
        assertEquals(List.of("你好", "世界"), lines);
    }

    @Test
    void leftRightPadsToWidth() {
        String line = UiText.leftRight("abc", "在线", 10);
        assertEquals(10, UiText.displayWidth(line));
        assertTrue(line.startsWith("abc"));
        assertTrue(line.endsWith("在线"));
    }

    @Test
    void maskHidesSecretButKeepsSpaces() {
        assertEquals("•••", UiText.mask("abc"));
        assertEquals("• •", UiText.mask("a b"));
    }

    @Test
    void horizontalSliceKeepsRequestedWindow() {
        assertEquals("世界", UiText.slice("你好世界", 4, 4));
    }
}
