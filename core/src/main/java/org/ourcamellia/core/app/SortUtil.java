package org.ourcamellia.core.app;

import java.util.Comparator;

/**
 * REQUIREMENTS §2.3 要求的会话列表排序：字母 A–Z 优先，其次数字 1–0；
 * 精确字符串比较作为确定性的并列兜底。字母比较不区分大小写
 * （并列时的稳定、已记录规则）。
 */
public final class SortUtil {

    private SortUtil() {}

    // 排序比较器：先按字符类别排名（字母 < 数字 < 其它），类别内不区分大小写，
    // 最后按长度、再按精确字符串比较作确定性兜底
    public static final Comparator<String> SPEC_ORDER = (a, b) -> {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            int ra = rank(a.charAt(i));
            int rb = rank(b.charAt(i));
            if (ra != rb) {
                return Integer.compare(ra, rb);
            }
            char ca = Character.toLowerCase(a.charAt(i));
            char cb = Character.toLowerCase(b.charAt(i));
            if (ca != cb) {
                return Character.compare(ca, cb);
            }
        }
        int len = Integer.compare(a.length(), b.length());
        if (len != 0) {
            return len;
        }
        return a.compareTo(b); // 确定性的并列兜底
    };

    // 字符类别排名：字母 0、数字 1、其它 2
    private static int rank(char c) {
        if (Character.isLetter(c)) {
            return 0;
        }
        if (Character.isDigit(c)) {
            return 1;
        }
        return 2;
    }
}
