package org.ourcamellia.client;

import org.ourcamellia.common.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 终端文本的显示宽度、截断、换行与分隔线工具。
 *
 * <p>为什么需要单独的类：Java 的 {@code String.length()} 统计的是 UTF-16 代码单元，
 * 而终端按“显示列”排版。中文、日文、韩文以及多数表情符号占两列；组合附加符号
 * 占零列。如果直接用 length() 计算宽度，中英文混排时右侧状态会错位，分隔线也会
 * 比窗口短或长。
 *
 * <p>本类只做显示层计算，不修改存储内容；调用方必须先通过
 * {@link Text#renderable(String)} 清理控制字符，防止终端注入。
 */
final class UiText {

    private UiText() {}

    /** 计算字符串在终端中占用的列数。 */
    static int displayWidth(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            width += charWidth(cp);
        }
        return width;
    }

    /** 单个 Unicode 码点的显示宽度（近似值，足以覆盖本客户端的中文界面）。 */
    static int charWidth(int cp) {
        if (cp == 0) {
            return 0;
        }
        if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F)) {
            return 0; // 控制字符不占列；正常路径已被 renderable 替换
        }
        if (Character.isISOControl(cp)) {
            return 0;
        }
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK) {
            return 0;
        }
        if (isWide(cp)) {
            return 2;
        }
        return 1;
    }

    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)      // 韩文字母
                || (cp >= 0x2E80 && cp <= 0xA4CF)  // CJK 部首、汉字、假名等
                || (cp >= 0xAC00 && cp <= 0xD7A3)  // 韩文音节
                || (cp >= 0xF900 && cp <= 0xFAFF)  // CJK 兼容汉字
                || (cp >= 0xFE30 && cp <= 0xFE6F)  // CJK 兼容形式
                || (cp >= 0xFF00 && cp <= 0xFF60)  // 全角 ASCII
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1FAFF) // 表情符号（近似）
                || (cp >= 0x20000 && cp <= 0x3FFFD);
    }

    /** 生成占满终端宽度的分隔线；宽度非法时退化为 1 列，避免空字符串。 */
    static String line(char ch, int width) {
        int n = Math.max(1, width);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }

    /** 按显示宽度截断；不拆分代理对。 */
    static String truncate(String s, int width) {
        String text = Text.renderable(s == null ? "" : s);
        if (width <= 0) {
            return "";
        }
        int used = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int cw = charWidth(cp);
            if (used + cw > width) {
                break;
            }
            used += cw;
            i += Character.charCount(cp);
        }
        return text.substring(0, i);
    }

    /** 左侧文本 + 右侧状态，用空格补足到给定宽度；放不下时优先保留左侧。 */
    static String leftRight(String left, String right, int width) {
        String l = Text.renderable(left == null ? "" : left);
        String r = Text.renderable(right == null ? "" : right);
        int lw = displayWidth(l);
        int rw = displayWidth(r);
        if (lw + rw + 1 <= width) {
            return l + " ".repeat(width - lw - rw) + r;
        }
        if (rw >= width) {
            return truncate(r, width);
        }
        return truncate(l, Math.max(0, width - rw - 1)) + " " + r;
    }

    /** 逻辑换行：保留原文中的换行符，并按显示宽度对每一段折行。 */
    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        String safe = Text.renderable(text == null ? "" : text);
        int w = Math.max(1, width);
        String[] paragraphs = safe.split("\n", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                out.add("");
                continue;
            }
            int used = 0;
            StringBuilder current = new StringBuilder();
            int i = 0;
            while (i < paragraph.length()) {
                int cp = paragraph.codePointAt(i);
                int cw = charWidth(cp);
                if (used + cw > w && current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                    used = 0;
                }
                if (cw > w) {
                    // 单字符比整行还宽（极端窄窗口）：仍然放置，避免死循环。
                    current.appendCodePoint(cp);
                    out.add(current.toString());
                    current.setLength(0);
                    used = 0;
                    i += Character.charCount(cp);
                    continue;
                }
                current.appendCodePoint(cp);
                used += cw;
                i += Character.charCount(cp);
            }
            out.add(current.toString());
        }
        return out;
    }

    /** 密码字段的掩码；只替换非空白字符，保留长度感知。 */
    static String mask(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            sb.append(cp == ' ' || cp == '\t' ? ' ' : '•');
        }
        return sb.toString();
    }

    /**
     * 单行输入的水平滚动：返回从 start 列开始的可见片段。
     * 用于命令栏和表单字段，保证光标不会跑出窗口。
     */
    static String slice(String text, int startColumn, int width) {
        if (width <= 0) {
            return "";
        }
        int skipped = 0;
        int i = 0;
        while (i < text.length() && skipped < startColumn) {
            int cp = text.codePointAt(i);
            skipped += charWidth(cp);
            i += Character.charCount(cp);
        }
        return truncate(text.substring(i), width);
    }
}
