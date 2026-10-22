package org.ourcamellia.client;

import org.jline.utils.NonBlockingInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 把终端原始字节解析为按键。
 *
 * <p>为什么不能把字节直接交给行编辑器：
 * <ul>
 *   <li>方向键、Home/End、PageUp/PageDown 都以 ESC 开头，是多字节序列；</li>
 *   <li>Esc 键本身只有一个字节，必须与“ESC 开头的序列”区分；</li>
 *   <li>Shift+Enter 在不同终端中有两种常见编码：
 *       CSI-u（<code>ESC [ 13 ; 2 u</code>）与 modifyOtherKeys
 *       （<code>ESC [ 27 ; 2 ; 13 ~</code>）；普通 Enter 只有一个回车字节。</li>
 * </ul>
 *
 * <p>解析策略：
 * <ol>
 *   <li>读到 ESC 后，最多等待 {@link #ESC_WAIT_MS} 毫秒看是否有后续字节；
 *       没有就认作单独的 Esc，避免“按 Esc 没反应”；</li>
 *   <li>CSI/SS3 序列被完整读取后映射为语义按键；未知序列返回 UNKNOWN，
 *       绝不把原始控制字节交给编辑器；</li>
 *   <li>UTF-8 多字节字符按首字节推断长度后完整读取，畸形字节丢弃；</li>
 *   <li>支持括号粘贴（bracketed paste）：整段文本作为一个 PASTE 事件，
 *       其中的换行保留，控制字符被清理。</li>
 * </ol>
 *
 * <p>限制：如果终端把 Shift+Enter 与 Enter 发送成同一个字节（很多传统终端
 * 如此），本类无法凭字节区分二者。界面必须始终提供 Ctrl+S 作为发送替代，
 * 并在帮助页明确说明该限制。
 */
final class KeyReader {

    /** ESC 单独按键的等待窗口；过长会让 Esc 反应迟钝，过短会误判序列。 */
    private static final int ESC_WAIT_MS = 25;
    private static final int SEQ_WAIT_MS = 300;

    enum Type {
        CHAR, PASTE,
        ENTER, SHIFT_ENTER, CTRL_ENTER,
        TAB, SHIFT_TAB,
        BACKSPACE, DELETE,
        UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN,
        ESC, CTRL_C, CTRL_S, CTRL_U, CTRL_D, CTRL_A, CTRL_E, CTRL_K, CTRL_W,
        UNKNOWN
    }

    record Key(Type type, String text) {
        static Key of(Type type) {
            return new Key(type, "");
        }

        static Key text(Type type, String text) {
            return new Key(type, text == null ? "" : text);
        }
    }

    private final InputStream input;
    private boolean eof = false;

    KeyReader(InputStream input) {
        this.input = input;
    }

    boolean eof() {
        return eof;
    }

    /**
     * 读取一个按键；在 timeoutMs 内没有输入时返回 null。
     *
     * @throws IOException 终端输入流发生真实错误时抛出，由 UI 决定退出并报告
     */
    Key read(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        int b = readByte(deadline);
        if (b < 0) {
            return null;
        }
        if (b == 27) {
            return readEscape();
        }
        if (b == 13 || b == 10) {
            return Key.of(Type.ENTER);
        }
        if (b == 9) {
            return Key.of(Type.TAB);
        }
        if (b == 127 || b == 8) {
            return Key.of(Type.BACKSPACE);
        }
        switch (b) {
            case 1 -> {
                return Key.of(Type.CTRL_A);
            }
            case 3 -> {
                return Key.of(Type.CTRL_C);
            }
            case 4 -> {
                return Key.of(Type.CTRL_D);
            }
            case 5 -> {
                return Key.of(Type.CTRL_E);
            }
            case 11 -> {
                return Key.of(Type.CTRL_K);
            }
            case 19 -> {
                return Key.of(Type.CTRL_S);
            }
            case 21 -> {
                return Key.of(Type.CTRL_U);
            }
            case 23 -> {
                return Key.of(Type.CTRL_W);
            }
            default -> {
                // 继续按可打印字符或 UTF-8 处理
            }
        }
        if (b >= 0x20 && b <= 0x7E) {
            return Key.text(Type.CHAR, String.valueOf((char) b));
        }
        if (b >= 0x80) {
            String text = readUtf8(b);
            return text.isEmpty() ? Key.of(Type.UNKNOWN) : Key.text(Type.CHAR, text);
        }
        return Key.of(Type.UNKNOWN);
    }

    private Key readEscape() throws IOException {
        int next = readByte(System.currentTimeMillis() + ESC_WAIT_MS);
        if (next < 0) {
            return Key.of(Type.ESC); // 单独的 Esc
        }
        if (next == '[') {
            return readCsi();
        }
        if (next == 'O') {
            return readSs3();
        }
        // Alt+字符等组合：当前界面未使用；吞掉，避免控制字节泄漏。
        return Key.of(Type.UNKNOWN);
    }

    private Key readSs3() throws IOException {
        int c = readByte(System.currentTimeMillis() + SEQ_WAIT_MS);
        if (c < 0) {
            return Key.of(Type.UNKNOWN);
        }
        return switch (c) {
            case 'A' -> Key.of(Type.UP);
            case 'B' -> Key.of(Type.DOWN);
            case 'C' -> Key.of(Type.RIGHT);
            case 'D' -> Key.of(Type.LEFT);
            case 'H' -> Key.of(Type.HOME);
            case 'F' -> Key.of(Type.END);
            default -> Key.of(Type.UNKNOWN);
        };
    }

    private Key readCsi() throws IOException {
        StringBuilder params = new StringBuilder();
        char finalChar = 0;
        long deadline = System.currentTimeMillis() + SEQ_WAIT_MS;
        while (true) {
            int c = readByte(deadline);
            if (c < 0 || c == 27) {
                return Key.of(Type.UNKNOWN);
            }
            char ch = (char) c;
            if (ch >= 0x40 && ch <= 0x7E) {
                finalChar = ch;
                break;
            }
            params.append(ch);
            if (params.length() > 48) {
                return Key.of(Type.UNKNOWN);
            }
        }
        String p = params.toString();
        return switch (finalChar) {
            case 'A' -> Key.of(Type.UP);
            case 'B' -> Key.of(Type.DOWN);
            case 'C' -> Key.of(Type.RIGHT);
            case 'D' -> Key.of(Type.LEFT);
            case 'H' -> Key.of(Type.HOME);
            case 'F' -> Key.of(Type.END);
            case 'Z' -> Key.of(Type.SHIFT_TAB);
            case 'u' -> parseCsiU(p);
            case '~' -> parseTilde(p);
            default -> Key.of(Type.UNKNOWN);
        };
    }

    /** CSI-u：ESC [ 码点 ; 修饰符 u（修饰符 1=无，2=Shift，5=Ctrl）。 */
    private Key parseCsiU(String params) {
        String[] parts = params.split(";", -1);
        int code;
        int modifier = 1;
        try {
            code = parts.length > 0 && !parts[0].isEmpty() ? Integer.parseInt(parts[0]) : 0;
            modifier = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : 1;
        } catch (NumberFormatException e) {
            return Key.of(Type.UNKNOWN);
        }
        boolean shift = ((modifier - 1) & 1) != 0;
        boolean ctrl = ((modifier - 1) & 4) != 0;
        if (code == 13 || code == 10) {
            if (shift) {
                return Key.of(Type.SHIFT_ENTER);
            }
            if (ctrl) {
                return Key.of(Type.CTRL_ENTER);
            }
            return Key.of(Type.ENTER);
        }
        if (code == 9) {
            return Key.of(shift ? Type.SHIFT_TAB : Type.TAB);
        }
        if (code == 27) {
            return Key.of(Type.ESC);
        }
        if (code >= 0x20 && !ctrl) {
            return Key.text(Type.CHAR, new String(Character.toChars(code)));
        }
        return Key.of(Type.UNKNOWN);
    }

    /**
     * xterm 风格 ESC [ 数字 ~：
     * 3=Delete，5=PageUp，6=PageDown，1/7=Home，4/8=End，
     * 27;修饰符;13~ 为 modifyOtherKeys 编码的 Enter。
     */
    private Key parseTilde(String params) {
        if (params.startsWith("200")) {
            return readBracketedPaste();
        }
        String[] parts = params.split(";", -1);
        int code;
        try {
            code = parts.length > 0 && !parts[0].isEmpty() ? Integer.parseInt(parts[0]) : 0;
        } catch (NumberFormatException e) {
            return Key.of(Type.UNKNOWN);
        }
        if (code == 27 && parts.length >= 3) {
            int modifier;
            int keyCode;
            try {
                modifier = Integer.parseInt(parts[1]);
                keyCode = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                return Key.of(Type.UNKNOWN);
            }
            if (keyCode == 13) {
                boolean shift = ((modifier - 1) & 1) != 0;
                boolean ctrl = ((modifier - 1) & 4) != 0;
                if (shift) {
                    return Key.of(Type.SHIFT_ENTER);
                }
                if (ctrl) {
                    return Key.of(Type.CTRL_ENTER);
                }
                return Key.of(Type.ENTER);
            }
            return Key.of(Type.UNKNOWN);
        }
        return switch (code) {
            case 3 -> Key.of(Type.DELETE);
            case 5 -> Key.of(Type.PAGE_UP);
            case 6 -> Key.of(Type.PAGE_DOWN);
            case 1, 7 -> Key.of(Type.HOME);
            case 4, 8 -> Key.of(Type.END);
            default -> Key.of(Type.UNKNOWN);
        };
    }

    /**
     * 括号粘贴：读取直到 ESC [ 201 ~，把整段文本作为一次输入。
     * 超时或流结束则返回已读取的内容；清理 ESC/C0（换行与制表符保留）。
     */
    private Key readBracketedPaste() {
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        byte[] end = {27, '[', '2', '0', '1', '~'};
        long deadline = System.currentTimeMillis() + 5000;
        try {
            while (System.currentTimeMillis() < deadline) {
                int c = readByte(deadline);
                if (c < 0) {
                    break;
                }
                raw.write(c);
                byte[] bytes = raw.toByteArray();
                if (bytes.length >= end.length && endsWith(bytes, end)) {
                    raw.reset();
                    raw.write(bytes, 0, bytes.length - end.length);
                    break;
                }
                if (bytes.length > 128 * 1024) {
                    break; // 防御超大粘贴，避免占用无限内存
                }
            }
        } catch (IOException e) {
            // 返回已读取内容；终端错误由下一次 read 统一报告。
        }
        // 终端发送的是 UTF-8 字节，必须整体解码后再清理控制字符。
        String cleaned = new String(raw.toByteArray(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\u001b", "");
        StringBuilder safe = new StringBuilder(cleaned.length());
        for (int i = 0; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            if (ch == '\n' || ch == '\t') {
                safe.append(ch == '\t' ? ' ' : ch);
            } else if (ch >= 0x20 && !(ch >= 0x7F && ch <= 0x9F)) {
                safe.append(ch);
            }
        }
        return Key.text(Type.PASTE, safe.toString());
    }

    private static boolean endsWith(byte[] bytes, byte[] suffix) {
        if (bytes.length < suffix.length) {
            return false;
        }
        int offset = bytes.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (bytes[offset + i] != suffix[i]) {
                return false;
            }
        }
        return true;
    }

    /** 读取一个完整的 UTF-8 字符；首字节已消费。畸形序列返回空串。 */
    private String readUtf8(int first) throws IOException {
        int need;
        int min;
        int value;
        if ((first & 0xE0) == 0xC0) {
            need = 1;
            min = 0x80;
            value = first & 0x1F;
        } else if ((first & 0xF0) == 0xE0) {
            need = 2;
            min = 0x800;
            value = first & 0x0F;
        } else if ((first & 0xF8) == 0xF0) {
            need = 3;
            min = 0x10000;
            value = first & 0x07;
        } else {
            return "";
        }
        byte[] bytes = new byte[need + 1];
        bytes[0] = (byte) first;
        long deadline = System.currentTimeMillis() + SEQ_WAIT_MS;
        for (int i = 1; i <= need; i++) {
            int b = readByte(deadline);
            if (b < 0 || (b & 0xC0) != 0x80) {
                return ""; // 不完整或非法续字节：丢弃整个序列
            }
            bytes[i] = (byte) b;
            value = (value << 6) | (b & 0x3F);
        }
        if (value < min || value > 0x10FFFF
                || (value >= 0xD800 && value <= 0xDFFF)) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 底层读一个字节；超时返回 -1。JLine 的 NonBlockingInputStream 支持带超时的
     * read，但不能用 available() 判断（基类实现恒返回 0）。
     */
    private int readByte(long deadline) throws IOException {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            return -1;
        }
        if (input instanceof NonBlockingInputStream nb) {
            int r = nb.read(remaining);
            if (r == NonBlockingInputStream.READ_EXPIRED) {
                return -1;
            }
            if (r < 0) {
                eof = true;
                return -1;
            }
            return r;
        }
        int available = input.available();
        if (available <= 0) {
            return -1;
        }
        int r = input.read();
        if (r < 0) {
            eof = true;
        }
        return r;
    }

    /** 供帮助页显示：当前终端类型名，便于用户判断组合键编码能力。 */
    static String terminalDescription(String type) {
        if (type == null || type.isBlank()) {
            return "未知终端";
        }
        return type.toLowerCase(Locale.ROOT);
    }
}
