package org.ourcamellia.core.app;

/**
 * 退出确认策略（需求 §8.2，经批准的窄解释 P3）：
 * 仅当当前会话存在未持久化的缓冲消息时才提示；没有缓冲内容绝不提示；
 * 输入非交互时绝不提示（由调用方报告缓冲内容将丢失）。
 */
public final class ExitPolicy {

    public enum Action { PERSIST_AND_EXIT, DISCARD_AND_EXIT, CANCEL }

    public record Decision(boolean promptNeeded, Action defaultAction, String message) {}

    private ExitPolicy() {}

    public static Decision decide(boolean hasBufferedMessages, boolean interactive) {
        if (!hasBufferedMessages) {
            return new Decision(false, Action.DISCARD_AND_EXIT, null);
        }
        if (!interactive) {
            return new Decision(false, Action.DISCARD_AND_EXIT,
                    "提示：未持久化的缓冲消息将丢失（录制已关闭；"
                            + "退出前请使用 ${persist} 保留它们）");
        }
        return new Decision(true, Action.CANCEL,
                "当前会话存在未持久化的缓冲消息。退出前是否持久化？(y/n/c)");
    }
}
