package org.ourcamellia.core.cmd;

import java.util.ArrayList;
import java.util.List;

/**
 * 形式文法（docs/COMMAND_GRAMMAR.md）的确定性、非递归解析器：对字符做单趟
 * 扫描，不对载荷递归，严格整行匹配（允许尾部空白）。
 *
 * <p>语法语义（不因汉化改变）：仅当整行以 {@code "${"} 开头才按命令解析；
 * 消息文本中的 {@code "\${"} 恒被转义为字面量 {@code "${"}；
 * Old 模式下消息文本中的 {@code "\n"} 表示换行。
 */
public final class CommandParser {

    public static final int MAX_ARGS = 16;
    public static final int MAX_LINE = 64 * 1024;

    /**
     * 解析一行逻辑输入。
     *
     * @param oldModeNewlineEscape 为 true 时，消息文本中的 "\n" 会变成换行
     *                             （Old 模式约定）；"${" 在消息文本中恒被还原为 "${"。
     */
    public static ParsedInput parse(String line, boolean oldModeNewlineEscape) {
        if (line == null) {
            return new ParsedInput.Blank();
        }
        if (line.length() > MAX_LINE) {
            return new ParsedInput.Malformed("输入超过 " + MAX_LINE + " 个字符");
        }
        if (line.isBlank()) {
            return new ParsedInput.Blank();
        }
        if (line.startsWith("${")) {
            return parseCommand(line);
        }
        return new ParsedInput.Message(unescapeMessage(line, oldModeNewlineEscape));
    }

    public static ParsedInput parse(String line) {
        return parse(line, false);
    }

    // ---------- 消息转义处理 ----------

    /**
     * 处理消息文本的转义：未知转义保留反斜杠原样；"\${" 还原为 "${"（防止
     * 消息被误判成命令）；Old 模式下 "\n" 还原为换行。
     */
    public static String unescapeMessage(String text, boolean newlineEscape) {
        if (text.indexOf('\\') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '$' && i + 2 < text.length() && text.charAt(i + 2) == '{') {
                    sb.append("${");
                    i += 2;
                    continue;
                }
                if (next == 'n' && newlineEscape) {
                    sb.append('\n');
                    i += 1;
                    continue;
                }
                // 未知转义：反斜杠按字面保留
                sb.append('\\');
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ---------- 命令解析 ----------

    private static ParsedInput parseCommand(String line) {
        int i = 2; // 跳过 "${"
        int n = line.length();

        // 命令名
        int nameStart = i;
        while (i < n && isNameChar(line.charAt(i), i == nameStart)) {
            i++;
        }
        if (i == nameStart) {
            return malformed("缺少命令名");
        }
        String name = line.substring(nameStart, i);

        List<Arg> args = new ArrayList<>();

        if (i >= n) {
            return malformed("命令未闭合（缺少 '}'）");
        }
        char c = line.charAt(i);
        if (c == '}') {
            return finishAfterClose(line, i + 1, name, args);
        }
        if (c == ' ' || c == '\t') {
            return malformed("参数必须放在括号内：${" + name + "(...)}");
        }
        if (c != '(') {
            return malformed("命令名后出现意外字符 '" + c + "'");
        }
        i++; // 越过 '('

        boolean expectArg = true;
        while (true) {
            i = skipSpaces(line, i, n);
            if (i >= n) {
                return malformed("命令未闭合（缺少 ')'）");
            }
            c = line.charAt(i);
            if (c == ')') {
                if (expectArg && !args.isEmpty()) {
                    return malformed("')' 前有多余的逗号");
                }
                return finishAfterParen(line, i + 1, name, args);
            }
            if (args.size() >= MAX_ARGS) {
                return malformed("参数过多（最多 " + MAX_ARGS + " 个）");
            }
            int[] end = new int[1];
            Arg arg = parseArg(line, i, n, end);
            if (arg == null) {
                return malformed("位置 " + i + " 处的参数格式有误");
            }
            args.add(arg);
            i = end[0];
            expectArg = false;

            int j = skipSpaces(line, i, n);
            if (j >= n) {
                return malformed("命令未闭合（缺少 ')'）");
            }
            char sep = line.charAt(j);
            if (sep == ',') {
                i = j + 1;
                expectArg = true;
                continue;
            }
            if (sep == ')') {
                return finishAfterParen(line, j + 1, name, args);
            }
            if (isArgStart(sep)) {
                // 空格分隔的参数同样被接受（规范分隔符是 ','）
                i = j;
                continue;
            }
            return malformed("应出现 ',' 或 ')'，却遇到 '" + sep + "'");
        }
    }

    private static int skipSpaces(String line, int i, int n) {
        while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** i 位于 ')' 之后：跳过空白，要求 '}'，随后做尾部空白检查。 */
    private static ParsedInput finishAfterParen(String line, int i, String name, List<Arg> args) {
        int n = line.length();
        i = skipSpaces(line, i, n);
        if (i >= n) {
            return malformed("命令未闭合（缺少 '}'）");
        }
        if (line.charAt(i) != '}') {
            return malformed("命令参数后存在多余内容");
        }
        return finishAfterClose(line, i + 1, name, args);
    }

    /** i 位于 '}' 之后：只允许尾部空格/制表符。 */
    private static ParsedInput finishAfterClose(String line, int i, String name, List<Arg> args) {
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') {
                return malformed("命令后存在多余内容");
            }
            i++;
        }
        return new ParsedInput.Command(name, List.copyOf(args));
    }

    /** 解析一个参数；把结束位置写入 {@code end[0]}。 */
    private static Arg parseArg(String line, int i, int n, int[] end) {
        char c = line.charAt(i);
        if (c == '"') {
            return parseString(line, i, n, end);
        }
        if (c == '-' || Character.isDigit(c)) {
            Arg num = parseNumber(line, i, n, end);
            return num != null ? num : parseAtom(line, i, n, end);
        }
        if (isNameChar(c, true)) {
            // 布尔字面量必须是一个完整 token（其后须为参数分隔符）。
            if (line.startsWith("true", i)) {
                int e = i + 4;
                if (e >= n || isArgBoundary(line.charAt(e))) {
                    end[0] = e;
                    return new Arg.Bool(true);
                }
            }
            if (line.startsWith("false", i)) {
                int e = i + 5;
                if (e >= n || isArgBoundary(line.charAt(e))) {
                    end[0] = e;
                    return new Arg.Bool(false);
                }
            }
            return parseAtom(line, i, n, end);
        }
        // 原子可以以 '$'、'{'、'}'、'@'、'.'、'_'、'-'、数字等开头。
        if (isAtomChar(c)) {
            return parseAtom(line, i, n, end);
        }
        return null;
    }

    /** 解析带引号字符串（支持 \" \\ \n \t \$ \{ 转义）；未闭合或含控制字符/换行则返回 null。 */
    private static Arg parseString(String line, int i, int n, int[] end) {
        StringBuilder sb = new StringBuilder();
        i++; // 越过开引号
        while (i < n) {
            char c = line.charAt(i);
            if (c == '"') {
                end[0] = i + 1;
                return new Arg.Str(sb.toString());
            }
            if (c == '\\' && i + 1 < n) {
                char e = line.charAt(i + 1);
                switch (e) {
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '$' -> sb.append('$');
                    case '{' -> sb.append('{');
                    default -> sb.append('\\').append(e); // 未知转义按字面保留
                }
                i += 2;
                continue;
            }
            if (c == '\n' || c == '\r') {
                return null; // 字符串不允许跨行
            }
            if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
                return null; // 拒绝控制字符
            }
            sb.append(c);
            i++;
        }
        return null; // 未闭合
    }

    /** 解析数字原子（可选负号 + 整数/小数）；不符合则回退为原子（如 "12abc"、"12."、单独的 '-'）。 */
    private static Arg parseNumber(String line, int i, int n, int[] end) {
        int start = i;
        if (line.charAt(i) == '-') {
            i++;
            if (i >= n || !Character.isDigit(line.charAt(i))) {
                return null; // 单独的 '-' → 原子
            }
        }
        while (i < n && Character.isDigit(line.charAt(i))) {
            i++;
        }
        if (i < n && line.charAt(i) == '.') {
            i++;
            if (i >= n || !Character.isDigit(line.charAt(i))) {
                return null; // "12." → 原子
            }
            while (i < n && Character.isDigit(line.charAt(i))) {
                i++;
            }
        }
        if (i < n && !isArgBoundary(line.charAt(i))) {
            return null; // "12abc" → 原子
        }
        String raw = line.substring(start, i);
        try {
            double v = Double.parseDouble(raw);
            if (Double.isInfinite(v) || Double.isNaN(v)) {
                return null;
            }
            end[0] = i;
            return new Arg.Num(v, raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Arg parseAtom(String line, int i, int n, int[] end) {
        int j = i;
        while (j < n && isAtomChar(line.charAt(j))) {
            j++;
        }
        if (j == i) {
            return null;
        }
        end[0] = j;
        return new Arg.Atom(line.substring(i, j));
    }

    private static boolean isArgBoundary(char c) {
        return c == ' ' || c == '\t' || c == ',' || c == ')';
    }

    private static boolean isArgStart(char c) {
        return c != ' ' && c != '\t' && c != ',' && c != ')' && c != '(';
    }

    private static boolean isNameChar(char c, boolean first) {
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
            return true;
        }
        return !first && (Character.isDigit(c) || c == '-' || c == '_');
    }

    private static boolean isAtomChar(char c) {
        return c != ' ' && c != '\t' && c != '"' && c != ',' && c != '(' && c != ')'
                && c != '\\' && c != '\n' && c != '\r'
                && !(c < 0x20) && !(c >= 0x7F && c <= 0x9F);
    }

    private static ParsedInput malformed(String reason) {
        return new ParsedInput.Malformed(reason);
    }
}