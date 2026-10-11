package org.ourcamellia.client;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.PrintWriter;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Optional;

/**
 * 基于 JLine 终端的极简裸模式（raw mode）行编辑器：缓冲区 + 光标、
 * 退格、左右/Home/End、Ctrl-C（取消本行）、Ctrl-D（空行时表示 EOF）、
 * Ctrl-U（清空本行）。
 *
 * <p>UTF-8 输入采用增量解码；畸形字节会被丢弃。超长输入不做折行处理
 * （已记录的限制，见 README“已知限制”）。
 *
 * <p>注意：行编辑器只负责维护「缓冲区 + 光标」这一份状态，绘制由
 * {@link ObservatoryUi} 负责。窗口重绘（包括整屏重绘）绝不修改缓冲区内容，
 * 这是保证「刷新不清空正在输入的内容」的关键约束。
 */
final class LineEditor {

    private final StringBuilder buf = new StringBuilder();
    private int cursor = 0;
    private final byte[] pending = new byte[8];   // 尚未解码完整的 UTF-8 字节（多字节字符的余量）
    private int pendingLen = 0;
    private final java.nio.charset.CharsetDecoder decoder =
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);

    /** 清空缓冲区与光标（提交、取消或强制清空时调用）。 */
    void reset() {
        buf.setLength(0);
        cursor = 0;
        pendingLen = 0;
    }

    String text() {
        return buf.toString();
    }

    int cursor() {
        return cursor;
    }

    /**
     * 喂入一个字节；若用户按回车提交，则返回整行文本（空串表示“提交空行”），
     * 否则返回 null（包括退格、光标移动、普通字符等未提交输入）。
     *
     * <p>控制键约定（raw mode 下的字节值）：13/10 = 回车，127/8 = 退格，
     * 3 = Ctrl-C（取消本行），4 = Ctrl-D（空行 EOF / 非空行删除光标下字符），
     * 21 = Ctrl-U（清空），27 = ESC（由调用方解析转义序列），9 = 制表符（忽略）。
     */
    String feed(int b) {
        if (b < 0) {
            return null;
        }
        // --- 控制键 ---
        if (b == 13 || b == 10) { // 回车：提交整行
            String line = buf.toString();
            reset();
            return line;
        }
        if (b == 127 || b == 8) { // 退格：删除光标前一个字符
            if (cursor > 0) {
                buf.deleteCharAt(cursor - 1);
                cursor--;
            }
            return null;
        }
        if (b == 3) { // Ctrl-C：取消本行输入
            reset();
            return null;
        }
        if (b == 4) { // Ctrl-D
            if (buf.isEmpty()) {
                return ""; // 空行时视为 EOF 信号
            }
            if (cursor < buf.length()) {
                buf.deleteCharAt(cursor); // 非空行时删除光标所在的字符
            }
            return null;
        }
        if (b == 21) { // Ctrl-U：清空本行
            reset();
            return null;
        }
        if (b == 27) { // ESC：转义序列由调用方逐字节解析，这里不做处理
            return null;
        }
        if (b == 9) { // 制表符：忽略
            return null;
        }
        // --- 可打印 UTF-8（增量解码）---
        if (pendingLen >= pending.length) {
            pendingLen = 0; // 过长的字节序列直接放弃
        }
        pending[pendingLen++] = (byte) b;
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(pending, 0, pendingLen);
        java.nio.CharBuffer out = java.nio.CharBuffer.allocate(4);
        java.nio.charset.CoderResult r = decoder.decode(in, out, false);
        // 先输出所有已完整解码的字符；underflow 只表示解码器还希望接收更多字节
        // （当前字符可能仍未完整——它的剩余字节会在后续 feed 中继续解码）。
        out.flip();
        while (out.hasRemaining()) {
            char c = out.get();
            if (!Character.isISOControl(c) && c != 127) {
                buf.insert(cursor, c); // 在光标处插入（即“光标移动后输入”）
                cursor++;
            }
        }
        if (r.isError()) {
            pendingLen = 0; // 畸形字节丢弃
            return null;
        }
        if (in.remaining() == 0) {
            pendingLen = 0; // 输入已全部消费
        }
        return null;
    }

    /** ANSI 转义序列解析：D=左移 C=右移 A/B=上下（无历史，忽略）H=Home F=End。 */
    void escape(String seq) {
        switch (seq) {
            case "D" -> cursor = Math.max(0, cursor - 1);
            case "C" -> cursor = Math.min(buf.length(), cursor + 1);
            case "H" -> cursor = 0;
            case "F" -> cursor = buf.length();
            default -> {
                // 其余转义序列（如方向键之外的 CSI）一律忽略
            }
        }
    }

    /**
     * 绘制提示符 + 缓冲区，并把光标定位到缓冲区的当前编辑位置。
     *
     * <p>终端宽度参数必须安全处理：极窄的窗口或很长的会话标题会让调用方计算出的
     * 可用宽度为 0 甚至负数。历史上这里会直接对负数宽度取子串，抛出
     * {@link StringIndexOutOfBoundsException}，导致拖动窗口（缩小宽度）时程序崩溃——
     * 修复方式是对宽度做下限钳制，并在超宽内容时只显示「尾部」保证光标始终可见
     * （缺陷 B 的修复位置）。
     *
     * @param terminalWidth 留给输入行的可用列数；内部会钳制为至少 1
     */
    void render(PrintWriter writer, String prompt, int terminalWidth) {
        String content = prompt + buf;
        String safe = org.ourcamellia.common.Text.renderable(content);
        // 防御：可显示宽度至少为 1，避免窄窗口 / 长提示导致的越界（曾经的崩溃点）
        int width = Math.max(1, terminalWidth);
        if (safe.length() > width) {
            // 保持光标可见：只显示输入的尾部（末尾部分）。
            // 实际可用于“显示尾部”的列数最多为 width-1，避免把光标推到换行位置。
            int keep = Math.max(1, Math.min(safe.length(), width - 1));
            int tailStart = safe.length() - keep;
            writer.print(safe.substring(tailStart));
            // 若光标位于可见尾部内，把光标移到其在尾部中的正确位置；
            // 否则（光标被滚出可视区）把光标停在尾部最左端。
            int cursorPos = prompt.length() + cursor;
            int back = cursorPos >= tailStart
                    ? Math.min(safe.length() - cursorPos, keep)
                    : keep;
            if (back > 0) {
                writer.print("\u001b[" + back + "D");
            }
            writer.flush();
            return;
        }
        writer.print(safe);
        int back = buf.length() - cursor; // 光标与行末的差（光标在行中而非行尾时回移）
        if (back > 0) {
            writer.print("\u001b[" + back + "D");
        }
        writer.flush();
    }
}