package org.ourcamellia.common;

/**
 * Text safety and identity validation. All user-supplied text crosses these rules
 * before it is stored, displayed, or sent — this is the terminal-escape-injection
 * defense (SECURITY.md threat item: terminal escape-sequence injection).
 */
public final class Text {

    public static final int MAX_MESSAGE = 16_384;      // 16 KiB per message
    public static final int MAX_NAME = 64;
    public static final int MAX_GROUP_NAME = 64;
    public static final int MAX_EMAIL = 254;

    private Text() {}

    /** True if the string contains C0/C1 control characters (including ESC). */
    public static boolean hasControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\t' && c != '\r') {
                return true;
            }
            if (c >= 0x7F && c <= 0x9F) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验自由文本（消息等内容）。允许换行，拒绝其它控制字符；
     * 校验失败时抛出带分类的 Validation 异常，消息供 UI 直接展示。
     */
    public static String requireCleanText(String text, String what, int maxLen) {
        if (text == null || text.isEmpty()) {
            throw new Errors.Validation(what + ": 文本不能为空");
        }
        if (text.length() > maxLen) {
            throw new Errors.Validation(what + ": 文本超过 " + maxLen + " 个字符");
        }
        if (hasControlChars(text)) {
            throw new Errors.Validation(what + ": 文本包含被禁止的控制字符");
        }
        return text;
    }

    /** Replaces unpaired surrogates, keeping the string valid UTF-8 after encoding. */
    public static String stripUnpairedSurrogates(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(i + 1));
                    i++;
                }
                // else drop the lone surrogate
            } else if (!Character.isLowSurrogate(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 身份名称规则：1-64 个字符，仅限保守的可打印字符集（不含空格与控制字符）。 */
    public static final java.util.regex.Pattern NAME_RE =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public static String requireLocalName(String name) {
        if (name == null || !NAME_RE.matcher(name).matches()) {
            throw new Errors.Validation(
                    "本地用户名须为 1-64 个 A-Z a-z 0-9 . _ - 字符");
        }
        return name;
    }

    public static String requireServerName(String name) {
        if (name == null || !name.startsWith("@") || name.length() > 65) {
            throw new Errors.Validation("服务器用户名须以 @ 开头且不超过 65 个字符");
        }
        requireLocalName(name.substring(1));
        return name;
    }

    public static String requireDisplayName(String name, String what) {
        return requireCleanText(name, what, MAX_NAME);
    }

    public static String requireGroupName(String name) {
        return requireCleanText(name, "群组名称", MAX_GROUP_NAME);
    }

    public static String requireEmail(String email) {
        if (email == null || email.length() > MAX_EMAIL) {
            throw new Errors.Validation("email: 无效地址");
        }
        if (hasControlChars(email)) {
            throw new Errors.Validation("email: 无效地址");
        }
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1
                || email.contains(" ")) {
            throw new Errors.Validation("email: 无效地址");
        }
        return email;
    }

    /** Renders text for terminal output, guaranteeing no raw ESC/control bytes reach the screen. */
    public static String renderable(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                sb.append('\n'); // deliberate newlines are fine
            } else if (c == '\t') {
                sb.append(' ');
            } else if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
                sb.append('\uFFFD');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
