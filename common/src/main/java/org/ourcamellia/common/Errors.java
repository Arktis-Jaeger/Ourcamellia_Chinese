package org.ourcamellia.common;

/**
 * Typed error hierarchy. Every user-facing failure carries a stable category so the
 * presentation layer can render consistent messages, and the API layer can map to
 * stable error codes. Messages must never contain secrets.
 */
public class Errors {

    /** 稳定的错误分类：BOT API 与 CLI 输出中保持一致，便于展示层统一渲染。 */
    public enum Category {
        USAGE,        // bad command line / wrong arguments — 命令行或参数错误
        VALIDATION,   // field content rejected by rules — 字段内容不符合规则
        AUTH,         // authentication failure — 身份认证失败
        DENIED,       // authorization failure — 授权被拒绝
        NOT_FOUND,    // target does not exist — 目标不存在
        CONFLICT,     // state conflict (duplicate, busy) — 状态冲突（重复、忙碌）
        NETWORK,      // connection / timeout / transport — 连接/超时/传输错误
        MALFORMED,    // hostile or broken protocol data — 敌对或损坏的协议数据
        PERSISTENCE,  // storage failure — 存储失败
        RATE_LIMITED, // too many attempts — 尝试过于频繁
        INTERNAL      // unexpected bug — 意外内部错误
    }

    public static class OcException extends RuntimeException {
        public final Category category;

        public OcException(Category category, String message) {
            super(message);
            this.category = category;
        }

        public OcException(Category category, String message, Throwable cause) {
            super(message, cause);
            this.category = category;
        }
    }

    public static class Usage extends OcException {
        public Usage(String message) { super(Category.USAGE, message); }
    }

    public static class Validation extends OcException {
        public Validation(String message) { super(Category.VALIDATION, message); }
    }

    public static class Auth extends OcException {
        public Auth(String message) { super(Category.AUTH, message); }
    }

    public static class Denied extends OcException {
        public Denied(String message) { super(Category.DENIED, message); }
    }

    public static class NotFound extends OcException {
        public NotFound(String message) { super(Category.NOT_FOUND, message); }
    }

    public static class Conflict extends OcException {
        public Conflict(String message) { super(Category.CONFLICT, message); }
    }

    public static class Network extends OcException {
        public Network(String message) { super(Category.NETWORK, message); }

        public Network(String message, Throwable cause) {
            super(Category.NETWORK, message, cause);
        }
    }

    /**
     * 套接字读取超时——仅内部用于驱动心跳，不面向用户展示。
     */
    public static class FrameTimeout extends Network {
        public FrameTimeout() { super("帧读取超时"); }
    }

    public static class Malformed extends OcException {
        public Malformed(String message) { super(Category.MALFORMED, message); }
    }

    public static class Persistence extends OcException {
        public Persistence(String message, Throwable cause) { super(Category.PERSISTENCE, message, cause); }

        public Persistence(String message) { super(Category.PERSISTENCE, message); }
    }

    public static class RateLimited extends OcException {
        public RateLimited(String message) { super(Category.RATE_LIMITED, message); }
    }

    public static class Internal extends OcException {
        public Internal(String message, Throwable cause) { super(Category.INTERNAL, message, cause); }
    }
}
