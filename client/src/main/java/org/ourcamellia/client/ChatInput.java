package org.ourcamellia.client;

/**
 * 聊天输入区的 Enter/Shift+Enter 判定。
 *
 * <p>把“按键 → 语义动作”的规则单独放在这里，便于单元测试；界面只负责把
 * 返回的动作落实为编辑或发送，避免同一次按键既换行又发送。
 *
 * <p>配置语义（terminal.shiftEnterToSend）：
 * <ul>
 *   <li>true（默认）：Enter 换行，Shift+Enter 发送；</li>
 *   <li>false：Enter 发送，Shift+Enter 换行。</li>
 * </ul>
 * 该规则只适用于聊天消息输入；菜单的 Enter 永远确认，命令栏的 Enter 永远提交，
 * 表单的 Enter 按表单流程处理。
 */
final class ChatInput {

    enum Action { NEWLINE, SEND }

    private ChatInput() {}

    /** 根据是否按下 Shift 与配置值，决定本次 Enter 的动作。 */
    static Action decideEnter(boolean shift, boolean shiftEnterToSend) {
        if (shiftEnterToSend) {
            return shift ? Action.SEND : Action.NEWLINE;
        }
        return shift ? Action.NEWLINE : Action.SEND;
    }

    /** 输入区提示文字；始终给出 Ctrl+S 作为无法区分组合键时的替代发送方式。 */
    static String hint(boolean shiftEnterToSend) {
        return shiftEnterToSend
                ? "Enter 换行   Shift+Enter 发送（终端不支持时按 Ctrl+S）   Tab 命令栏   Esc 返回"
                : "Enter 发送   Shift+Enter 换行   Ctrl+S 发送   Tab 命令栏   Esc 返回";
    }
}
