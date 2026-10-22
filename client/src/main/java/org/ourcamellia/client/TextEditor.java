package org.ourcamellia.client;

/**
 * 多行文本编辑器：只维护「文本 + 光标位置」这一份状态，不负责任何绘制。
 *
 * <p>界面刷新、窗口缩放、异步消息到达都不会修改这里的缓冲区；发送成功后由
 * 调用方显式 {@link #clear()}。这是“收到消息不覆盖正在输入的内容”的基础。
 *
 * <p>光标用 UTF-16 索引表示，但所有移动都按 Unicode 码点进行，避免把代理对
 * （表情符号等）拆成两半。换行符 {@code \n} 是普通字符，是否允许输入由
 * {@link #multiline} 决定。
 */
final class TextEditor {

    private final StringBuilder buffer = new StringBuilder();
    private int cursor = 0;
    private final boolean multiline;

    TextEditor(boolean multiline) {
        this.multiline = multiline;
    }

    boolean multiline() {
        return multiline;
    }

    String text() {
        return buffer.toString();
    }

    int cursor() {
        return cursor;
    }

    boolean isEmpty() {
        return buffer.length() == 0;
    }

    int length() {
        return buffer.length();
    }

    void clear() {
        buffer.setLength(0);
        cursor = 0;
    }

    void setText(String text) {
        buffer.setLength(0);
        insertText(text == null ? "" : text);
        cursor = buffer.length();
    }

    /**
     * 在光标处插入文本。控制字符会被丢弃；换行在单行编辑器中变成空格。
     * 这是终端注入防御的一部分：粘贴内容不能携带 ESC 序列。
     */
    void insertText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        StringBuilder cleaned = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\r') {
                continue;
            }
            if (cp == '\n') {
                cleaned.append(multiline ? '\n' : ' ');
                continue;
            }
            if (cp == '\t') {
                cleaned.append(' ');
                continue;
            }
            if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F)) {
                continue;
            }
            cleaned.appendCodePoint(cp);
        }
        buffer.insert(cursor, cleaned);
        cursor += cleaned.length();
    }

    void newline() {
        if (!multiline) {
            return;
        }
        buffer.insert(cursor, '\n');
        cursor++;
    }

    void insertChar(String text) {
        insertText(text);
    }

    boolean backspace() {
        if (cursor <= 0) {
            return false;
        }
        int start = cursor - 1;
        if (start > 0 && Character.isLowSurrogate(buffer.charAt(start))
                && Character.isHighSurrogate(buffer.charAt(start - 1))) {
            start--;
        }
        buffer.delete(start, cursor);
        cursor = start;
        return true;
    }

    boolean delete() {
        if (cursor >= buffer.length()) {
            return false;
        }
        int end = cursor + 1;
        if (end < buffer.length() && Character.isHighSurrogate(buffer.charAt(cursor))
                && Character.isLowSurrogate(buffer.charAt(end))) {
            end++;
        }
        buffer.delete(cursor, end);
        return true;
    }

    void left() {
        if (cursor > 0) {
            cursor--;
            if (cursor > 0 && Character.isLowSurrogate(buffer.charAt(cursor))
                    && Character.isHighSurrogate(buffer.charAt(cursor - 1))) {
                cursor--;
            }
        }
    }

    void right() {
        if (cursor < buffer.length()) {
            if (Character.isHighSurrogate(buffer.charAt(cursor))
                    && cursor + 1 < buffer.length()
                    && Character.isLowSurrogate(buffer.charAt(cursor + 1))) {
                cursor += 2;
            } else {
                cursor++;
            }
        }
    }

    void home() {
        cursor = lineStart(cursor);
    }

    void end() {
        cursor = lineEnd(cursor);
    }

    void up() {
        int start = lineStart(cursor);
        if (start == 0) {
            return;
        }
        int column = cursor - start;
        int prevEnd = start - 1; // 上一行的换行符位置
        int prevStart = lineStart(prevEnd);
        cursor = Math.min(prevStart + column, prevEnd);
    }

    void down() {
        int end = lineEnd(cursor);
        if (end >= buffer.length()) {
            return;
        }
        int column = cursor - lineStart(cursor);
        int nextStart = end + 1;
        int nextEnd = lineEnd(nextStart);
        cursor = Math.min(nextStart + column, nextEnd);
    }

    void deleteToLineStart() {
        int start = lineStart(cursor);
        buffer.delete(start, cursor);
        cursor = start;
    }

    void deleteToLineEnd() {
        int end = lineEnd(cursor);
        buffer.delete(cursor, end);
    }

    int lineStart(int index) {
        int i = Math.min(index, buffer.length());
        while (i > 0 && buffer.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    int lineEnd(int index) {
        int i = Math.min(index, buffer.length());
        while (i < buffer.length() && buffer.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /** 当前逻辑行号（从 0 开始）。 */
    int lineIndex() {
        int line = 0;
        for (int i = 0; i < Math.min(cursor, buffer.length()); i++) {
            if (buffer.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    int lineCount() {
        int count = 1;
        for (int i = 0; i < buffer.length(); i++) {
            if (buffer.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /** 光标在当前逻辑行中的列（UTF-16 偏移，用于渲染时再换算显示宽度）。 */
    int column() {
        return cursor - lineStart(cursor);
    }

    /** 按逻辑行拆分，供绘制使用。 */
    java.util.List<String> lines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        int start = 0;
        for (int i = 0; i <= buffer.length(); i++) {
            if (i == buffer.length() || buffer.charAt(i) == '\n') {
                out.add(buffer.substring(start, i));
                start = i + 1;
            }
        }
        return out;
    }
}
